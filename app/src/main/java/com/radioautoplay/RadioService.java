package com.radioautoplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.List;

/**
 * Foreground service that manages MediaPlayer for radio streaming.
 * Keeps a CPU wake-lock so playback isn't killed by Doze.
 */
public class RadioService extends Service {

    private static final String TAG          = "RadioService";
    private static final String CHANNEL_ID   = "radio_channel";
    private static final int    NOTIF_ID     = 1;
    private static final long   STREAM_START_TIMEOUT_MS = 60_000L;
    private static final long   INTRO_DELAY_MS = 5_000L;

    public static final String ACTION_PLAY   = "com.radioautoplay.PLAY";
    public static final String ACTION_STOP   = "com.radioautoplay.STOP";
    public static final String EXTRA_URL     = "stream_url";

    // Broadcast sent back to MainActivity
    public static final String BROADCAST_STATE = "com.radioautoplay.STATE";
    public static final String EXTRA_PLAYING   = "is_playing";
    public static final String EXTRA_URL_NOW   = "current_url";
    public static final String EXTRA_ERROR     = "error_msg";
    public static final String EXTRA_STATUS    = "status_msg";

    private MediaPlayer mediaPlayer;
    private MediaPlayer introPlayer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock  wifiLock;
    private StreamUrlManager urlManager;
    private Handler handler;
    private AudioManager audioManager;
    private String currentUrl;
    private Runnable introStartDelay;
    private Runnable streamStartTimeout;
    private boolean isPlaying = false;
    private int failoverAttempts = 0;
    private int playbackRequestId = 0;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> { };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        urlManager = new StreamUrlManager(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            if (url != null && !url.isEmpty()) {
                failoverAttempts = 0;
                playbackRequestId++;
                startPlaybackAfterIntro(url, playbackRequestId);
            }
        } else if (ACTION_STOP.equals(action)) {
            playbackRequestId++;
            stopPlayback();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopPlayback(false);
        releaseLocks();
        super.onDestroy();
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private void startPlaybackAfterIntro(String url, int requestId) {
        stopPlayback(false);
        currentUrl = url;
        startForeground(NOTIF_ID, buildNotification("Waiting before intro", url));
        broadcastState(false, null, "Waiting 5 seconds before intro");

        introStartDelay = () -> {
            if (requestId != playbackRequestId) return;
            playIntroTheme(url, requestId);
        };
        handler.postDelayed(introStartDelay, INTRO_DELAY_MS);
    }

    private void playIntroTheme(String url, int requestId) {
        releaseIntroPlayerOnly();
        if (requestId != playbackRequestId) return;

        try {
            introPlayer = new MediaPlayer();
            setPlayerAudioMode(introPlayer);
            introPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.initializing_system);
            if (afd == null) {
                Log.w(TAG, "Intro theme resource was not found; starting stream.");
                releaseIntroPlayerOnly();
                startPlayback(url, requestId);
                return;
            }
            try {
                introPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                try {
                    afd.close();
                } catch (IOException e) {
                    Log.w(TAG, "Could not close intro asset", e);
                }
            }
            introPlayer.setOnCompletionListener(mp -> {
                if (requestId != playbackRequestId) return;
                releaseIntroPlayerOnly();
                startPlayback(url, requestId);
            });
            introPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Intro theme error: " + what + ", " + extra);
                if (requestId == playbackRequestId) {
                    releaseIntroPlayerOnly();
                    startPlayback(url, requestId);
                }
                return true;
            });

            updateNotification("Playing intro theme", url);
            broadcastState(false, null, "Playing intro theme");
            requestAudioFocus();
            introPlayer.prepare();
            introPlayer.start();

        } catch (Exception e) {
            Log.e(TAG, "Error playing intro theme", e);
            releaseIntroPlayerOnly();
            startPlayback(url, requestId);
        }
    }

    private void startPlayback(String url, int requestId) {
        stopPlayback(false); // release previous player if any
        if (requestId != playbackRequestId) return;

        currentUrl = url;
        Log.d(TAG, "Starting playback: " + url);

        try {
            mediaPlayer = new MediaPlayer();

            setPlayerAudioMode(mediaPlayer);
            mediaPlayer.setOnPreparedListener(mp -> {
                if (requestId != playbackRequestId) return;
                cancelStreamWatchdog();
                requestAudioFocus();
                mp.start();
                isPlaying = true;
                broadcastState(true, null);
                updateNotification("Playing", currentUrl);
                Log.d(TAG, "Playback started");
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                isPlaying = false;
                if (requestId == playbackRequestId) {
                    switchToAnotherStream("Stream error (code " + what + ")");
                }
                return true;
            });

            mediaPlayer.setOnInfoListener((mp, what, extra) -> {
                Log.d(TAG, "MediaPlayer info: " + what);
                return false;
            });
            mediaPlayer.setDataSource(url);
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.prepareAsync(); // non-blocking
            streamStartTimeout = () -> {
                if (requestId == playbackRequestId && mediaPlayer != null && !isPlaying) {
                    Log.w(TAG, "Stream did not start within one minute: " + currentUrl);
                    switchToAnotherStream("Stream did not start in 1 minute");
                }
            };
            handler.postDelayed(streamStartTimeout, STREAM_START_TIMEOUT_MS);

            // Show "connecting…" notification immediately
            startForeground(NOTIF_ID, buildNotification("Connecting", url));
            broadcastState(false, null, "Connecting to music");

        } catch (Exception e) {
            Log.e(TAG, "Error setting up MediaPlayer", e);
            switchToAnotherStream("Cannot open stream: " + e.getMessage());
        }
    }

    private void stopPlayback() {
        stopPlayback(true);
    }

    private void stopPlayback(boolean broadcastIdle) {
        isPlaying = false;
        cancelIntroStartDelay();
        cancelStreamWatchdog();
        releaseIntroPlayerOnly();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing player", e);
            }
            mediaPlayer = null;
        }
        if (broadcastIdle) {
            broadcastState(false, null);
        }
        abandonAudioFocus();
    }

    private void switchToAnotherStream(String reason) {
        cancelStreamWatchdog();
        releaseMediaPlayerOnly();

        List<String> urls = urlManager.getUrls();
        if (urls.size() <= 1) {
            broadcastState(false, reason + ". No backup stream is saved.");
            updateNotification("No backup stream", currentUrl);
            stopSelf();
            return;
        }

        failoverAttempts++;
        if (failoverAttempts >= urls.size()) {
            broadcastState(false, "All saved streams failed to start.");
            updateNotification("All streams failed", currentUrl);
            stopSelf();
            return;
        }

        String nextUrl = urlManager.getNextUrl();
        if (nextUrl == null || nextUrl.equals(currentUrl)) {
            broadcastState(false, "No different backup stream is available.");
            updateNotification("No backup stream", currentUrl);
            stopSelf();
            return;
        }

        playbackRequestId++;
        currentUrl = nextUrl;
        broadcastState(false, null, reason + ". Trying another stream");
        updateNotification("Trying backup stream", nextUrl);
        startPlayback(nextUrl, playbackRequestId);
    }

    private void releaseMediaPlayerOnly() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing player", e);
            }
            mediaPlayer = null;
        }
        isPlaying = false;
    }

    private void releaseIntroPlayerOnly() {
        if (introPlayer != null) {
            try {
                if (introPlayer.isPlaying()) introPlayer.stop();
                introPlayer.reset();
                introPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing intro player", e);
            }
            introPlayer = null;
        }
    }

    private void cancelIntroStartDelay() {
        if (handler != null && introStartDelay != null) {
            handler.removeCallbacks(introStartDelay);
            introStartDelay = null;
        }
    }

    private void cancelStreamWatchdog() {
        if (handler != null && streamStartTimeout != null) {
            handler.removeCallbacks(streamStartTimeout);
            streamStartTimeout = null;
        }
    }

    private void setPlayerAudioMode(MediaPlayer player) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            player.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            );
        } else {
            //noinspection deprecation
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        //noinspection deprecation
        audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
        );
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        //noinspection deprecation
        audioManager.abandonAudioFocus(audioFocusChangeListener);
    }

    // ── Locks ─────────────────────────────────────────────────────────────────

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioAutoPlay::WakeLock");
            wakeLock.acquire(6 * 60 * 60 * 1000L); // 6-hour safety timeout
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RadioAutoPlay::WifiLock");
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Radio Playback",
                    NotificationManager.IMPORTANCE_LOW // no sound for media notifications
            );
            channel.setDescription("Shows while radio is streaming");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String status, String url) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp, flags);

        Intent stopIntent = new Intent(this, RadioService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Radio AutoPlay")
                .setContentText(status)
                .setSubText(shortenUrl(url))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String status, String url) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(status, url));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void broadcastState(boolean playing, String error) {
        broadcastState(playing, error, null);
    }

    private void broadcastState(boolean playing, String error, String status) {
        Intent i = new Intent(BROADCAST_STATE);
        i.putExtra(EXTRA_PLAYING, playing);
        i.putExtra(EXTRA_URL_NOW, currentUrl != null ? currentUrl : "");
        if (error != null) i.putExtra(EXTRA_ERROR, error);
        if (status != null) i.putExtra(EXTRA_STATUS, status);
        sendBroadcast(i);
    }

    private String shortenUrl(String url) {
        if (url == null) return "";
        return url.length() > 50 ? url.substring(0, 47) + "…" : url;
    }
}
