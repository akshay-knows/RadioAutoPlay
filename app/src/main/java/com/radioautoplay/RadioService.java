package com.radioautoplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * Foreground service that plays intro audio and direct radio stream URLs only.
 * No browser, WebView, or web-page player is used.
 */
public class RadioService extends Service {

    private static final String TAG = "RadioService";
    private static final String CHANNEL_ID = "radio_channel";
    private static final int NOTIF_ID = 1;
    private static final long PLAYBACK_START_DELAY_MS = 40_000L;
    private static final long STREAM_START_TIMEOUT_MS = 60_000L;
    private static final long STATUS_CHECK_INTERVAL_MS = 60_000L;
    private static final int LOW_BATTERY_PERCENT = 20;
    private static final int QUIET_HOURS_START_HOUR = 0;
    private static final int QUIET_HOURS_END_HOUR = 6;
    private static final int[] BUNDLED_INTRO_SOUNDS = {
            R.raw.playstation_3_slim,
            R.raw.samsung_galaxy_on,
            R.raw.soft_notify,
            R.raw.vzw_boot_sound,
            R.raw.win_longhorn_bootup,
            R.raw.windows_2000,
            R.raw.windows_2000_startup,
            R.raw.xbox_series_x_bootup,
            R.raw.xperia_startup_sound
    };

    public static final String ACTION_PLAY = "com.radioautoplay.PLAY";
    public static final String ACTION_STOP = "com.radioautoplay.STOP";
    public static final String EXTRA_URL = "stream_url";

    public static final String BROADCAST_STATE = "com.radioautoplay.STATE";
    public static final String EXTRA_PLAYING = "is_playing";
    public static final String EXTRA_URL_NOW = "current_url";
    public static final String EXTRA_ERROR = "error_msg";
    public static final String EXTRA_STATUS = "status_msg";

    private MediaPlayer introPlayer;
    private MediaPlayer streamPlayer;
    private MediaPlayer offlinePlayer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private StreamUrlManager urlManager;
    private IntroSoundManager introSoundManager;
    private DiagnosticsLogger diagnosticsLogger;
    private Handler handler;
    private AudioManager audioManager;
    private String currentUrl;
    private String activePlaybackUrl;
    private String lastRequestedUrl;
    private Runnable introStartDelay;
    private Runnable streamStartTimeout;
    private Runnable statusCheckRunnable;
    private boolean isPlaying = false;
    private boolean offlineMode = false;
    private boolean networkWasConnected = true;
    private boolean lowBatteryAnnounced = false;
    private int playbackRequestId = 0;
    private int failoverAttempts = 0;
    private final Random introRandom = new Random();
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> { };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                handleNetworkStatus();
            } else if (Intent.ACTION_BATTERY_LOW.equals(intent.getAction())
                    || Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                handleBatteryStatus(intent);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        urlManager = new StreamUrlManager(this);
        introSoundManager = new IntroSoundManager(this);
        diagnosticsLogger = new DiagnosticsLogger(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        acquireLocks();
        registerStatusReceiver();
        startStatusAnnouncements();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            lastRequestedUrl = url;
            if (urlManager != null && !urlManager.isAppEnabled()) {
                currentUrl = url;
                startForeground(NOTIF_ID, buildNotification("App disabled", currentUrl));
                broadcastState(false, null, "Radio AutoPlay is turned off");
                stopPlayback(false);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!StreamUrlManager.isPlayableStreamUrl(url)) {
                currentUrl = url;
                startForeground(NOTIF_ID, buildNotification("Unsupported link", currentUrl));
                broadcastState(false, "Only direct audio stream links are supported.", "Unsupported link");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (url.equals(activePlaybackUrl) && (isPlaying || introPlayer != null || streamPlayer != null
                    || offlinePlayer != null || introStartDelay != null)) {
                return START_STICKY;
            }
            if (isQuietHoursNow()) {
                currentUrl = url;
                startForeground(NOTIF_ID, buildNotification("Quiet hours", currentUrl));
                broadcastState(false, null, "Quiet hours: playback paused until 6:00 AM");
                stopPlayback(false);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!isNetworkConnected()) {
                startOfflineFallback("No network connection. Playing offline backup audio.");
                return START_STICKY;
            }
            failoverAttempts = 0;
            playbackRequestId++;
            scheduleDelayedPlayback(url, playbackRequestId);
            return START_STICKY;
        } else if (ACTION_STOP.equals(action)) {
            playbackRequestId++;
            stopPlayback();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopPlayback(false);
        unregisterStatusReceiver();
        releaseLocks();
        super.onDestroy();
    }

    private void scheduleDelayedPlayback(String url, int requestId) {
        cancelIntroStartDelay();
        stopPlayback(false);
        if (requestId != playbackRequestId) return;

        offlineMode = false;
        currentUrl = url;
        activePlaybackUrl = url;
        long startDelayMs = urlManager != null && urlManager.isStartDelayEnabled()
                ? PLAYBACK_START_DELAY_MS : 0L;
        String status = startDelayMs > 0 ? "Starting in " + (startDelayMs / 1000L) + " seconds" : "Starting now";
        startForeground(NOTIF_ID, buildNotification(status, url));
        broadcastState(false, null, status);

        introStartDelay = () -> {
            introStartDelay = null;
            if (requestId != playbackRequestId) return;
            if (urlManager != null && !urlManager.isAppEnabled()) {
                stopPlayback(false);
                stopSelf();
                return;
            }
            if (isQuietHoursNow()) {
                updateNotification("Quiet hours", url);
                broadcastState(false, null, "Quiet hours: playback paused until 6:00 AM");
                stopPlayback(false);
                stopSelf();
                return;
            }
            startPlaybackAfterIntro(url, requestId);
        };

        if (startDelayMs > 0) {
            handler.postDelayed(introStartDelay, startDelayMs);
        } else {
            handler.post(introStartDelay);
        }
    }

    private void startPlaybackAfterIntro(String url, int requestId) {
        stopPlayback(false);
        if (requestId != playbackRequestId) return;
        currentUrl = url;
        activePlaybackUrl = url;
        startForeground(NOTIF_ID, buildNotification("Starting intro", url));
        broadcastState(false, null, "Starting intro");
        playIntroTheme(url, requestId);
    }

    private void playIntroTheme(String url, int requestId) {
        releaseIntroPlayerOnly();
        if (requestId != playbackRequestId) return;

        try {
            introPlayer = new MediaPlayer();
            setPlayerAudioMode(introPlayer);
            introPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            setIntroDataSource(introPlayer);
            introPlayer.setOnCompletionListener(mp -> {
                releaseIntroPlayerOnly();
                if (requestId == playbackRequestId) startDirectStream(url, requestId);
            });
            introPlayer.setOnErrorListener((mp, what, extra) -> {
                logWarn("Intro theme error: " + what + ", " + extra);
                releaseIntroPlayerOnly();
                if (requestId == playbackRequestId) startDirectStream(url, requestId);
                return true;
            });

            updateNotification("Playing intro theme", url);
            broadcastState(false, null, "Playing intro theme");
            requestAudioFocus();
            introPlayer.prepare();
            introPlayer.start();
        } catch (Exception e) {
            logError("Error playing intro theme", e);
            releaseIntroPlayerOnly();
            startDirectStream(url, requestId);
        }
    }

    private void setIntroDataSource(MediaPlayer player) throws IOException {
        Uri customIntro = introSoundManager.getRandomIntroUri();
        if (customIntro != null) {
            try {
                player.setDataSource(getApplicationContext(), customIntro);
                return;
            } catch (Exception e) {
                throw new IOException("Custom intro could not be opened", e);
            }
        }

        int introResId = BUNDLED_INTRO_SOUNDS[introRandom.nextInt(BUNDLED_INTRO_SOUNDS.length)];
        android.content.res.AssetFileDescriptor afd = getResources().openRawResourceFd(introResId);
        if (afd == null) throw new IOException("Bundled intro sound resource was not found");
        try {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        } finally {
            afd.close();
        }
    }

    private void startDirectStream(String url, int requestId) {
        releaseStreamPlayerOnly();
        if (requestId != playbackRequestId) return;

        currentUrl = url;
        activePlaybackUrl = url;
        updateNotification("Tuning station", url);
        broadcastState(false, null, "Tuning station");

        try {
            streamPlayer = new MediaPlayer();
            setPlayerAudioMode(streamPlayer);
            streamPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            streamPlayer.setOnPreparedListener(mp -> {
                if (requestId != playbackRequestId) return;
                cancelStreamWatchdog();
                requestAudioFocus();
                mp.start();
                isPlaying = true;
                currentUrl = getStationName(url) + "\n" + url;
                broadcastState(true, null, "Playing music");
                updateNotification("Playing music", getStationName(url));
            });
            streamPlayer.setOnErrorListener((mp, what, extra) -> {
                logWarn("Stream error what=" + what + " extra=" + extra + " url=" + url);
                if (requestId == playbackRequestId) {
                    switchToAnotherStream("Stream error");
                }
                return true;
            });
            streamPlayer.setOnInfoListener((mp, what, extra) -> {
                logInfo("MediaPlayer info what=" + what + " extra=" + extra);
                return false;
            });
            streamPlayer.setDataSource(url);
            streamPlayer.prepareAsync();

            streamStartTimeout = () -> {
                if (requestId == playbackRequestId && streamPlayer != null && !isPlaying) {
                    switchToAnotherStream("Stream did not start in 1 minute");
                }
            };
            handler.postDelayed(streamStartTimeout, STREAM_START_TIMEOUT_MS);
        } catch (Exception e) {
            logError("Cannot open direct stream", e);
            switchToAnotherStream("Cannot open stream");
        }
    }

    private void switchToAnotherStream(String reason) {
        cancelStreamWatchdog();
        releaseStreamPlayerOnly();

        List<String> urls = urlManager.getAllPlaybackUrls();
        if (urls.size() <= 1) {
            broadcastState(false, reason + ". No backup stream is saved.", "No backup stream");
            updateNotification("No backup stream", currentUrl);
            stopSelf();
            return;
        }

        failoverAttempts++;
        if (failoverAttempts >= urls.size()) {
            broadcastState(false, "All saved streams failed to start.", "All streams failed");
            updateNotification("All streams failed", currentUrl);
            stopSelf();
            return;
        }

        String nextUrl = urlManager.getNextUrl();
        if (!StreamUrlManager.isPlayableStreamUrl(nextUrl) || nextUrl.equals(activePlaybackUrl)) {
            switchToAnotherStream(reason);
            return;
        }

        playbackRequestId++;
        broadcastState(false, null, reason + ". Trying another stream");
        updateNotification("Trying backup stream", nextUrl);
        startDirectStream(nextUrl, playbackRequestId);
    }

    private void stopPlayback() {
        stopPlayback(true);
    }

    private void stopPlayback(boolean broadcastIdle) {
        isPlaying = false;
        offlineMode = false;
        activePlaybackUrl = null;
        cancelIntroStartDelay();
        cancelStreamWatchdog();
        releaseIntroPlayerOnly();
        releaseStreamPlayerOnly();
        stopOfflineFallback(false);
        if (broadcastIdle) broadcastState(false, null);
        abandonAudioFocus();
    }

    private void releaseIntroPlayerOnly() {
        if (introPlayer != null) {
            try {
                if (introPlayer.isPlaying()) introPlayer.stop();
                introPlayer.reset();
                introPlayer.release();
            } catch (Exception e) {
                logError("Error releasing intro player", e);
            }
            introPlayer = null;
        }
    }

    private void releaseStreamPlayerOnly() {
        if (streamPlayer != null) {
            try {
                if (streamPlayer.isPlaying()) streamPlayer.stop();
                streamPlayer.reset();
                streamPlayer.release();
            } catch (Exception e) {
                logError("Error releasing stream player", e);
            }
            streamPlayer = null;
        }
        isPlaying = false;
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

    private void startOfflineFallback(String reason) {
        playbackRequestId++;
        stopPlayback(false);
        offlineMode = true;
        currentUrl = "Offline backup audio";
        startForeground(NOTIF_ID, buildNotification("Offline backup playing", currentUrl));
        broadcastState(true, null, reason);
        updateNotification("Offline backup playing", currentUrl);
        try {
            offlinePlayer = MediaPlayer.create(this, R.raw.kenny_g_songbird_gran_turismo_soundtrack);
            if (offlinePlayer == null) return;
            setPlayerAudioMode(offlinePlayer);
            offlinePlayer.setLooping(true);
            offlinePlayer.setOnErrorListener((mp, what, extra) -> {
                stopOfflineFallback(false);
                return true;
            });
            requestAudioFocus();
            offlinePlayer.start();
            isPlaying = true;
        } catch (Exception e) {
            logError("Could not start offline fallback player", e);
            stopOfflineFallback(false);
        }
    }

    private void stopOfflineFallback(boolean resumeRadio) {
        if (offlinePlayer != null) {
            try {
                if (offlinePlayer.isPlaying()) offlinePlayer.stop();
                offlinePlayer.reset();
                offlinePlayer.release();
            } catch (Exception e) {
                logError("Error releasing offline fallback player", e);
            }
            offlinePlayer = null;
        }
        if (!offlineMode) return;
        offlineMode = false;
        isPlaying = false;
        if (resumeRadio && StreamUrlManager.isPlayableStreamUrl(lastRequestedUrl)) {
            playbackRequestId++;
            startPlaybackAfterIntro(lastRequestedUrl, playbackRequestId);
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
        audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        //noinspection deprecation
        audioManager.abandonAudioFocus(audioFocusChangeListener);
    }

    private void startStatusAnnouncements() {
        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                stopForQuietHoursIfNeeded();
                handleNetworkStatus();
                Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) handleBatteryStatus(battery);
                handler.postDelayed(this, STATUS_CHECK_INTERVAL_MS);
            }
        };
        handler.postDelayed(statusCheckRunnable, STATUS_CHECK_INTERVAL_MS);
    }

    private boolean isQuietHoursNow() {
        if (urlManager != null && !urlManager.isQuietHoursEnabled()) return false;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= QUIET_HOURS_START_HOUR && hour < QUIET_HOURS_END_HOUR;
    }

    private void stopForQuietHoursIfNeeded() {
        if (!isQuietHoursNow()) return;
        if (!isPlaying && introPlayer == null && offlinePlayer == null && streamPlayer == null) return;
        broadcastState(false, null, "Quiet hours started. Playback paused until 6:00 AM");
        updateNotification("Quiet hours", currentUrl);
        playbackRequestId++;
        stopPlayback(false);
        stopSelf();
    }

    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    private void unregisterStatusReceiver() {
        if (handler != null && statusCheckRunnable != null) {
            handler.removeCallbacks(statusCheckRunnable);
            statusCheckRunnable = null;
        }
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {
        }
    }

    private void handleNetworkStatus() {
        boolean connected = isNetworkConnected();
        if (networkWasConnected && !connected) {
            if (!offlineMode) startOfflineFallback("Network disconnected. Playing offline backup audio.");
        } else if (!networkWasConnected && connected) {
            if (offlineMode) stopOfflineFallback(true);
        }
        networkWasConnected = connected;
    }

    private boolean isNetworkConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
        return info != null && info.isConnected();
    }

    private void handleBatteryStatus(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return;

        int percent = Math.round(level * 100f / scale);
        if (percent <= LOW_BATTERY_PERCENT && !lowBatteryAnnounced) {
            lowBatteryAnnounced = true;
            logWarn("Low battery: " + percent + "%");
        } else if (percent > LOW_BATTERY_PERCENT + 5) {
            lowBatteryAnnounced = false;
        }
    }

    private String getStationName(String url) {
        String knownName = StreamUrlManager.getRadioNameForUrl(url);
        return knownName.isEmpty() ? "your radio station" : knownName;
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioAutoPlay::WakeLock");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Radio Playback",
                    NotificationManager.IMPORTANCE_LOW
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

    private void broadcastState(boolean playing, String error) {
        broadcastState(playing, error, null);
    }

    private void broadcastState(boolean playing, String error, String status) {
        Intent i = new Intent(BROADCAST_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_PLAYING, playing);
        i.putExtra(EXTRA_URL_NOW, currentUrl != null ? currentUrl : "");
        if (error != null) i.putExtra(EXTRA_ERROR, error);
        if (status != null) i.putExtra(EXTRA_STATUS, status);
        sendBroadcast(i);
    }

    private String shortenUrl(String url) {
        if (url == null) return "";
        return url.length() > 50 ? url.substring(0, 47) + "..." : url;
    }

    private void logInfo(String msg) {
        Log.d(TAG, msg);
        if (diagnosticsLogger != null) diagnosticsLogger.i(TAG, msg);
    }

    private void logWarn(String msg) {
        Log.w(TAG, msg);
        if (diagnosticsLogger != null) diagnosticsLogger.w(TAG, msg);
    }

    private void logError(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        if (diagnosticsLogger != null) diagnosticsLogger.e(TAG, msg, t);
    }
}
