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
import android.content.res.AssetFileDescriptor;
import android.media.AudioFocusRequest;
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
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final long   STATUS_CHECK_INTERVAL_MS = 60_000L;
    private static final int    LOW_BATTERY_PERCENT = 20;

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
    private IntroSoundManager introSoundManager;
    private Handler handler;
    private AudioManager audioManager;
    private TextToSpeech textToSpeech;
    private String currentUrl;
    private Runnable introStartDelay;
    private Runnable streamStartTimeout;
    private Runnable statusCheckRunnable;
    private boolean isPlaying = false;
    private boolean ttsReady = false;
    private boolean networkWasConnected = true;
    private boolean lowBatteryAnnounced = false;
    private int failoverAttempts = 0;
    private int playbackRequestId = 0;
    private int lastTimeAnnouncementKey = -1;
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        urlManager = new StreamUrlManager(this);
        introSoundManager = new IntroSoundManager(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        acquireLocks();
        initTextToSpeech();
        registerStatusReceiver();
        startStatusAnnouncements();
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
        unregisterStatusReceiver();
        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }
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
            setIntroDataSource(introPlayer);
            introPlayer.setOnCompletionListener(mp -> {
                if (requestId != playbackRequestId) return;
                releaseIntroPlayerOnly();
                playThinkingBridgeThenStart(url, requestId);
            });
            introPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Intro theme error: " + what + ", " + extra);
                if (requestId == playbackRequestId) {
                    releaseIntroPlayerOnly();
                    playThinkingBridgeThenStart(url, requestId);
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
            playThinkingBridgeThenStart(url, requestId);
        }
    }

    private void setIntroDataSource(MediaPlayer player) throws IOException {
        Uri customIntro = introSoundManager.getRandomIntroUri();
        if (customIntro != null) {
            try {
                player.setDataSource(getApplicationContext(), customIntro);
                Log.d(TAG, "Playing custom intro sound: " + customIntro);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Custom intro could not be opened. Falling back to bundled intro.", e);
            }
        }

        AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.initializing_system);
        if (afd == null) throw new IOException("Bundled intro theme resource was not found");
        try {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        } finally {
            try {
                afd.close();
            } catch (IOException e) {
                Log.w(TAG, "Could not close intro asset", e);
            }
        }
    }

    private void playThinkingBridgeThenStart(String url, int requestId) {
        if (requestId != playbackRequestId) return;
        broadcastState(false, null, "Tuning station");
        updateNotification("Tuning station", url);
        speakVoiceAlert("Tuning your radio station. Please wait.", () -> startPlayback(url, requestId));
    }

    private void startPlayback(String url, int requestId) {
        stopPlayback(false); // release previous player if any
        if (requestId != playbackRequestId) return;

        currentUrl = url;
        Log.d(TAG, "Starting playback: " + url);

        streamStartTimeout = () -> {
            if (requestId == playbackRequestId && !isPlaying) {
                Log.w(TAG, "Stream did not start within one minute: " + currentUrl);
                switchToAnotherStream("Stream did not start in 1 minute");
            }
        };
        handler.postDelayed(streamStartTimeout, STREAM_START_TIMEOUT_MS);
        startForeground(NOTIF_ID, buildNotification("Resolving stream", url));
        broadcastState(false, null, "Resolving stream");

        new Thread(() -> {
            StreamSource source = resolveStreamSource(url);
            handler.post(() -> {
                if (requestId == playbackRequestId && !isPlaying) {
                    openResolvedPlayback(source, requestId);
                }
            });
        }, "StreamResolver").start();
    }

    private void openResolvedPlayback(StreamSource source, int requestId) {
        releaseMediaPlayerOnly();
        if (requestId != playbackRequestId) return;

        currentUrl = source.displayUrl;
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
                speakVoiceAlert("Now playing " + getStationName(currentUrl), null);
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
            mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(source.playUrl), source.headers);
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.prepareAsync(); // non-blocking

            // Show "connecting…" notification immediately
            startForeground(NOTIF_ID, buildNotification("Connecting", source.displayUrl));
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
            speakVoiceAlert(reason + ". No backup stream is saved.", null);
            stopSelf();
            return;
        }

        failoverAttempts++;
        if (failoverAttempts >= urls.size()) {
            broadcastState(false, "All saved streams failed to start.");
            updateNotification("All streams failed", currentUrl);
            speakVoiceAlert("Unable to play stream. All saved streams failed to start.", null);
            stopSelf();
            return;
        }

        String nextUrl = urlManager.getNextUrl();
        if (nextUrl == null || nextUrl.equals(currentUrl)) {
            broadcastState(false, "No different backup stream is available.");
            updateNotification("No backup stream", currentUrl);
            speakVoiceAlert("Unable to play stream. No different backup stream is available.", null);
            stopSelf();
            return;
        }

        playbackRequestId++;
        currentUrl = nextUrl;
        broadcastState(false, null, reason + ". Trying another stream");
        updateNotification("Trying backup stream", nextUrl);
        speakVoiceAlert(reason + ". Trying another stream.", null);
        startPlayback(nextUrl, playbackRequestId);
    }

    private StreamSource resolveStreamSource(String url) {
        Map<String, String> playerHeaders = createPlayerHeaders();
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(12_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", playerHeaders.get("User-Agent"));
            connection.setRequestProperty("Accept", playerHeaders.get("Accept"));
            connection.setRequestProperty("Icy-MetaData", "1");

            String contentType = connection.getContentType();
            String finalUrl = connection.getURL().toString();
            if (!isHtmlContent(contentType)) {
                return new StreamSource(finalUrl, finalUrl, playerHeaders);
            }

            String html = readSmallTextResponse(connection.getInputStream());
            String sourceUrl = extractMediaSourceUrl(finalUrl, html);
            if (sourceUrl != null) {
                Log.d(TAG, "Resolved HTML player page to media source: " + sourceUrl);
                return new StreamSource(sourceUrl, sourceUrl, playerHeaders);
            }

            Log.w(TAG, "HTML page did not expose a media source. Retrying URL with audio headers.");
            return new StreamSource(finalUrl, finalUrl, playerHeaders);
        } catch (Exception e) {
            Log.w(TAG, "Could not pre-resolve stream URL. Trying direct playback.", e);
            return new StreamSource(url, url, playerHeaders);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Map<String, String> createPlayerHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 13) RadioAutoPlay/1.0");
        headers.put("Accept", "audio/mpeg,audio/aac,audio/ogg,audio/*;q=0.9,video/*;q=0.4,*/*;q=0.1");
        headers.put("Icy-MetaData", "1");
        return headers;
    }

    private boolean isHtmlContent(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.US).contains("html");
    }

    private String readSmallTextResponse(InputStream inputStream) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(inputStream);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1 && total < 65536) {
                int allowed = Math.min(read, 65536 - total);
                out.write(buffer, 0, allowed);
                total += allowed;
            }
            return out.toString("UTF-8");
        }
    }

    private String extractMediaSourceUrl(String baseUrl, String html) {
        if (html == null || html.isEmpty()) return null;

        Pattern mediaSourcePattern = Pattern.compile(
                "(?i)<(?:audio|video|source)[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]");
        Matcher matcher = mediaSourcePattern.matcher(html);
        if (!matcher.find()) return null;

        try {
            return new URL(new URL(baseUrl), matcher.group(1)).toString();
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve media source URL", e);
            return matcher.group(1);
        }
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

    // ── Voice announcements ──────────────────────────────────────────────────

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS && textToSpeech != null) {
                int result = textToSpeech.setLanguage(Locale.US);
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED;
                textToSpeech.setSpeechRate(0.92f);
                textToSpeech.setPitch(1.02f);
            } else {
                ttsReady = false;
                Log.w(TAG, "TextToSpeech initialization failed");
            }
        });
    }

    private void speakVoiceAlert(String message, Runnable afterDone) {
        if (!ttsReady || textToSpeech == null || message == null || message.trim().isEmpty()) {
            if (afterDone != null) handler.post(afterDone);
            return;
        }

        final boolean[] finished = {false};
        if (afterDone != null) {
            handler.postDelayed(() -> {
                if (!finished[0]) {
                    finished[0] = true;
                    restoreMusicVolume();
                    afterDone.run();
                }
            }, 8_000L);
        }

        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.setVolume(0.28f, 0.28f);
            } catch (Exception e) {
                Log.w(TAG, "Could not duck stream for announcement", e);
            }
        }

        String utteranceId = "voice_" + System.currentTimeMillis();
        textToSpeech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override
            public void onStart(String id) { }

            @Override
            public void onDone(String id) {
                handler.post(() -> {
                    if (finished[0]) return;
                    finished[0] = true;
                    restoreMusicVolume();
                    if (afterDone != null) {
                        afterDone.run();
                    }
                });
            }

            @Override
            public void onError(String id) {
                handler.post(() -> {
                    if (finished[0]) return;
                    finished[0] = true;
                    restoreMusicVolume();
                    if (afterDone != null) {
                        afterDone.run();
                    }
                });
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.os.Bundle params = new android.os.Bundle();
            textToSpeech.speak(message, TextToSpeech.QUEUE_ADD, params, utteranceId);
        } else {
            java.util.HashMap<String, String> params = new java.util.HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            //noinspection deprecation
            textToSpeech.speak(message, TextToSpeech.QUEUE_ADD, params);
        }
    }

    private void restoreMusicVolume() {
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.setVolume(1f, 1f);
            } catch (Exception e) {
                Log.w(TAG, "Could not restore stream volume", e);
            }
        }
    }

    private void startStatusAnnouncements() {
        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                announceTimeIfNeeded();
                handleNetworkStatus();
                Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) handleBatteryStatus(battery);
                handler.postDelayed(this, STATUS_CHECK_INTERVAL_MS);
            }
        };
        handler.postDelayed(statusCheckRunnable, STATUS_CHECK_INTERVAL_MS);
    }

    private void announceTimeIfNeeded() {
        if (!isPlaying) return;
        Calendar calendar = Calendar.getInstance();
        int minute = calendar.get(Calendar.MINUTE);
        if (minute != 0 && minute != 30) return;

        int key = calendar.get(Calendar.DAY_OF_YEAR) * 10000
                + calendar.get(Calendar.HOUR_OF_DAY) * 100
                + minute;
        if (key == lastTimeAnnouncementKey) return;
        lastTimeAnnouncementKey = key;

        String hourText = String.format(Locale.US, "%d:%02d",
                calendar.get(Calendar.HOUR), minute);
        if (hourText.startsWith("0:")) {
            hourText = "12:" + hourText.substring(2);
        }
        speakVoiceAlert("The time is " + hourText + ".", null);
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
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
        boolean connected = info != null && info.isConnected();
        if (networkWasConnected && !connected) {
            speakVoiceAlert("Network disconnected. Wi Fi or mobile data is not working.", null);
        } else if (!networkWasConnected && connected) {
            speakVoiceAlert("Network is connected again.", null);
        }
        networkWasConnected = connected;
    }

    private void handleBatteryStatus(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return;

        int percent = Math.round(level * 100f / scale);
        if (percent <= LOW_BATTERY_PERCENT && !lowBatteryAnnounced) {
            lowBatteryAnnounced = true;
            speakVoiceAlert("Battery low. Battery is " + percent + " percent.", null);
        } else if (percent > LOW_BATTERY_PERCENT + 5) {
            lowBatteryAnnounced = false;
        }
    }

    private String getStationName(String url) {
        if (url == null || url.isEmpty()) return "your radio station";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "your radio station";
            return host.replace("www.", "").replace(".", " dot ");
        } catch (Exception e) {
            return "your radio station";
        }
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
        i.setPackage(getPackageName());
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

    private static class StreamSource {
        final String playUrl;
        final String displayUrl;
        final Map<String, String> headers;

        StreamSource(String playUrl, String displayUrl, Map<String, String> headers) {
            this.playUrl = playUrl;
            this.displayUrl = displayUrl;
            this.headers = headers;
        }
    }
}
