package com.radioautoplay;

import android.annotation.SuppressLint;
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
import android.media.audiofx.LoudnessEnhancer;
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
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
import java.util.Random;
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
    private static final long   PLAYBACK_START_DELAY_MS = 2_000L;
    private static final long   STREAM_START_TIMEOUT_MS = 17_000L;
    private static final long   WEB_POST_LOAD_START_TIMEOUT_MS = 20_000L;
    private static final long   WEB_HEALTHCHECK_INTERVAL_MS = 15_000L;
    private static final int    WEB_AUTOPLAY_PROBE_ATTEMPTS = 20;
    private static final long   STATUS_CHECK_INTERVAL_MS = 60_000L;
    private static final int    LOW_BATTERY_PERCENT = 20;
    private static final int    QUIET_HOURS_START_HOUR = 0;
    private static final int    QUIET_HOURS_END_HOUR = 6;
    private static final float  NORMALIZED_STREAM_VOLUME = 0.82f;
    private static final int    LOUDNESS_ENHANCER_GAIN_MB = 450;
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
    private MediaPlayer waitingPlayer;
    private MediaPlayer offlinePlayer;
    private LoudnessEnhancer loudnessEnhancer;
    private WebView webViewPlayer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock  wifiLock;
    private StreamUrlManager urlManager;
    private IntroSoundManager introSoundManager;
    private DiagnosticsLogger diagnosticsLogger;
    private Handler handler;
    private AudioManager audioManager;
    private TextToSpeech textToSpeech;
    private String currentUrl;
    private String activePlaybackUrl;
    private Runnable introStartDelay;
    private Runnable streamStartTimeout;
    private Runnable webPlaybackMonitor;
    private Runnable statusCheckRunnable;
    private boolean isPlaying = false;
    private boolean introFinished = false;
    private boolean streamPrepared = false;
    private boolean webFallbackStarted = false;
    private boolean offlineMode = false;
    private boolean ttsReady = false;
    private boolean networkWasConnected = true;
    private boolean lowBatteryAnnounced = false;
    private int failoverAttempts = 0;
    private int playbackRequestId = 0;
    private int lastTimeAnnouncementKey = -1;
    private int webSilentChecks = 0;
    private int webNoMediaChecks = 0;
    private String lastRequestedUrl;
    private int pendingWebRequestId = -1;
    private String pendingWebPageUrl;
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
        initTextToSpeech();
        registerStatusReceiver();
        startStatusAnnouncements();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            logInfo("ACTION_PLAY received");
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
            if (url != null && url.equals(activePlaybackUrl)
                    && (isPlaying
                    || mediaPlayer != null
                    || webViewPlayer != null
                    || introPlayer != null
                    || waitingPlayer != null
                    || offlinePlayer != null
                    || introStartDelay != null)) {
                logInfo("Ignoring duplicate ACTION_PLAY for active url=" + url);
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
            if (url != null && !url.isEmpty()) {
                if (!isNetworkConnected()) {
                    startOfflineFallback("No network connection. Playing offline backup audio.");
                    return START_STICKY;
                }
                logInfo("Starting playback requestId=" + (playbackRequestId + 1) + " url=" + url);
                failoverAttempts = 0;
                playbackRequestId++;
                scheduleDelayedPlayback(url, playbackRequestId);
            }
        } else if (ACTION_STOP.equals(action)) {
            logInfo("ACTION_STOP received");
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

    private void scheduleDelayedPlayback(String url, int requestId) {
        cancelIntroStartDelay();
        stopPlayback(false);
        if (requestId != playbackRequestId) return;

        offlineMode = false;
        currentUrl = url;
        activePlaybackUrl = url;
        startForeground(NOTIF_ID, buildNotification("Starting in 2 seconds", url));
        broadcastState(false, null, "Starting in 2 seconds");
        logInfo("Delaying playback start by " + PLAYBACK_START_DELAY_MS + "ms requestId="
                + requestId + " url=" + url);

        introStartDelay = () -> {
            introStartDelay = null;
            if (requestId != playbackRequestId) return;
            if (urlManager != null && !urlManager.isAppEnabled()) {
                updateNotification("App disabled", currentUrl);
                broadcastState(false, null, "Radio AutoPlay is turned off");
                stopPlayback(false);
                stopSelf();
                return;
            }
            if (isQuietHoursNow()) {
                currentUrl = url;
                updateNotification("Quiet hours", currentUrl);
                broadcastState(false, null, "Quiet hours: playback paused until 6:00 AM");
                stopPlayback(false);
                stopSelf();
                return;
            }
            startPlaybackAfterIntro(url, requestId);
        };
        handler.postDelayed(introStartDelay, PLAYBACK_START_DELAY_MS);
    }

    private void startPlaybackAfterIntro(String url, int requestId) {
        stopPlayback(false);
        offlineMode = false;
        currentUrl = url;
        activePlaybackUrl = url;
        logInfo("startPlaybackAfterIntro requestId=" + requestId + " url=" + url);
        introFinished = false;
        streamPrepared = false;
        startForeground(NOTIF_ID, buildNotification("Starting intro", url));
        broadcastState(false, null, "Starting intro and buffering stream");

        startPlayback(url, requestId);
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
                if (requestId != playbackRequestId) return;
                onIntroFinished(url, requestId);
            });
            introPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Intro theme error: " + what + ", " + extra);
                if (requestId == playbackRequestId) {
                    releaseIntroPlayerOnly();
                    onIntroFinished(url, requestId);
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
            onIntroFinished(url, requestId);
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

    private void onIntroFinished(String url, int requestId) {
        if (requestId != playbackRequestId) return;
        introFinished = true;
        broadcastState(false, null, "Tuning station");
        updateNotification("Tuning station", url);
        if (webViewPlayer != null) {
            startDeferredWebAutoplay(requestId);
            return;
        }
        if (streamPrepared && mediaPlayer != null) {
            startPreparedStream(requestId);
        } else {
            startPlayback(url, requestId);
        }
    }

    private void startPlayback(String url, int requestId) {
        cancelStreamWatchdog();
        releaseMediaPlayerOnly();
        releaseWebViewOnly();
        streamPrepared = false;
        webFallbackStarted = false;
        if (requestId != playbackRequestId) return;

        currentUrl = url;
        activePlaybackUrl = url;
        logInfo("startPlayback requestId=" + requestId + " url=" + url);

        if (urlManager.isWebStreamUrl(url)) {
            if (!introFinished) {
                logInfo("Deferring web station page load until intro finishes requestId=" + requestId + " url=" + url);
                broadcastState(false, null, "Waiting for intro");
                updateNotification("Waiting for intro", url);
                return;
            }
            cancelStreamWatchdog();
            startForeground(NOTIF_ID, buildNotification("Resolving web station", url));
            broadcastState(false, null, "Resolving web station");
            new Thread(() -> {
                StreamSource source = resolveStreamSource(url);
                handler.post(() -> {
                    if (requestId != playbackRequestId || isPlaying) return;
                    if (isLikelyDirectStreamSource(url, source)) {
                        logInfo("Resolved webpage station to direct stream requestId=" + requestId
                                + " playUrl=" + source.playUrl + " displayUrl=" + source.displayUrl);
                        openResolvedPlayback(source, requestId);
                    } else {
                        startWebPageFallback(url, requestId, "Opening web station");
                    }
                });
            }, "WebStreamResolver").start();
            return;
        }

        streamStartTimeout = () -> {
            if (requestId == playbackRequestId && !isPlaying && !streamPrepared) {
                Log.w(TAG, "Stream did not start within 17 seconds: " + currentUrl);
                logWarn("Direct stream timeout requestId=" + requestId + " url=" + currentUrl);
                startWebPageFallback(currentUrl, requestId, "Direct stream did not start in 17 seconds");
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
        logInfo("openResolvedPlayback requestId=" + requestId + " source=" + source.playUrl);
        try {
            mediaPlayer = new MediaPlayer();

            setPlayerAudioMode(mediaPlayer);
            mediaPlayer.setOnPreparedListener(mp -> {
                if (requestId != playbackRequestId) return;
                cancelStreamWatchdog();
                streamPrepared = true;
                logInfo("MediaPlayer prepared requestId=" + requestId + " introFinished=" + introFinished);
                if (introFinished) {
                    startPreparedStream(requestId);
                } else {
                    broadcastState(false, null, "Stream ready, finishing intro");
                    updateNotification("Stream ready", currentUrl);
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                logError("MediaPlayer error requestId=" + requestId + " what=" + what + " extra=" + extra, null);
                isPlaying = false;
                if (requestId == playbackRequestId) {
                    startWebPageFallback(currentUrl, requestId, "Stream error (code " + what + ")");
                }
                return true;
            });

            mediaPlayer.setOnInfoListener((mp, what, extra) -> {
                Log.d(TAG, "MediaPlayer info: " + what);
                return false;
            });
            mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(source.playUrl), source.headers);
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            applyDirectAudioNormalizer(mediaPlayer);
            mediaPlayer.prepareAsync(); // non-blocking

            // Show "connecting…" notification immediately
            startForeground(NOTIF_ID, buildNotification("Connecting", source.displayUrl));
            broadcastState(false, null, "Connecting to music");

        } catch (Exception e) {
            logError("Error setting up MediaPlayer requestId=" + requestId, e);
            startWebPageFallback(currentUrl, requestId, "Cannot open stream");
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startWebPageFallback(String url, int requestId, String reason) {
        if (requestId != playbackRequestId) return;
        if (webFallbackStarted) {
            logWarn("Web fallback already started. requestId=" + requestId + " reason=" + reason);
            switchToAnotherStream(reason + ". Web player fallback also failed");
            return;
        }

            webFallbackStarted = true;
            streamPrepared = false;
            releaseMediaPlayerOnly();
            requestAudioFocus();
            if (introFinished) {
                startWaitingLoop();
            }
            broadcastState(false, null, reason + ". Opening web player");
            updateNotification("Opening web player", url);
            logInfo("startWebPageFallback requestId=" + requestId + " url=" + url + " reason=" + reason);

        handler.post(() -> {
            if (requestId != playbackRequestId) return;
            releaseWebViewOnly();
            webViewPlayer = new WebView(getApplicationContext());
            webViewPlayer.setNetworkAvailable(true);
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(webViewPlayer, true);
            }
            WebSettings settings = webViewPlayer.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setLoadsImagesAutomatically(true);
            settings.setLoadWithOverviewMode(false);
            settings.setUseWideViewPort(false);
            settings.setMediaPlaybackRequiresUserGesture(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
            settings.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

            webViewPlayer.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onReceivedTitle(WebView view, String title) {
                    if (requestId == playbackRequestId && title != null && !title.trim().isEmpty()) {
                        currentUrl = title.trim();
                    }
                }
            });

            webViewPlayer.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String pageUrl) {
                    if (requestId != playbackRequestId) return;
                    resetStreamWatchdogForWebPlayback(requestId, pageUrl);
                    startWebAutoplayNow(view, pageUrl, requestId);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    boolean isMainFrame = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                            || (request != null && request.isForMainFrame());
                    if (requestId == playbackRequestId && isMainFrame) {
                        logWarn("Web page main-frame error requestId=" + requestId + " page=" + currentUrl);
                        switchToAnotherStream("Web player page failed");
                    }
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return;
                    if (requestId == playbackRequestId) {
                        logWarn("Web page main-frame error requestId=" + requestId
                                + " code=" + errorCode + " description=" + description);
                        switchToAnotherStream("Web player page failed");
                    }
                }
            });

            webViewPlayer.onResume();
            webViewPlayer.resumeTimers();
            webViewPlayer.loadUrl(url, createPlayerHeaders());
        });
    }

    private void startDeferredWebAutoplay(int requestId) {
        if (requestId != playbackRequestId || webViewPlayer == null) return;
        String pageUrl = pendingWebPageUrl;
        if (pageUrl == null || pageUrl.trim().isEmpty()) {
            pageUrl = webViewPlayer.getUrl();
        }
        if (pageUrl == null || pageUrl.trim().isEmpty()) {
            pageUrl = currentUrl;
        }
        startWebAutoplayNow(webViewPlayer, pageUrl, requestId);
    }

    private void startWebAutoplayNow(WebView view, String pageUrl, int requestId) {
        pendingWebRequestId = -1;
        pendingWebPageUrl = null;
        injectAutoplayScript(view, pageUrl, false);
        logInfo("Web autoplay start requestId=" + requestId + " pageUrl=" + pageUrl);
        handler.postDelayed(() -> verifyWebPlaybackStarted(pageUrl, requestId, 0), 2_500L);
    }

    private void pauseAndMuteWebMedia(WebView view) {
        String script = "(function(){"
                + "try{var media=[].slice.call(document.querySelectorAll('audio,video'));"
                + "media.forEach(function(m){try{m.pause&&m.pause();m.muted=true;m.volume=0;}catch(e){}});}catch(e){}"
                + "})();";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(script, null);
        } else {
            view.loadUrl("javascript:" + script);
        }
    }

    private void injectAutoplayScript(WebView view, String targetUrl, boolean allowPagePlayer) {
        String stationCode = jsString(extractOnlineRadioBoxStationCode(targetUrl));
        String script = "(function(){"
                + "var targetVolume=" + NORMALIZED_STREAM_VOLUME + ";"
                + "var targetCode='" + stationCode + "';"
                + "var allowPagePlayer=" + allowPagePlayer + ";"
                + "function allMedia(){return [].slice.call(document.querySelectorAll('audio,video'));}"
                + "function stopOtherMedia(keep){allMedia().forEach(function(m){try{if(m!==keep){m.pause&&m.pause();m.muted=true;m.volume=0;}}catch(e){}});}"
                + "function attrs(e){var s='';try{s+=(e.id||'')+' '+(e.className||'')+' '+(e.href||'')+' '+(e.getAttribute('stream')||'')+' '+(e.getAttribute('data-stream')||'')+' '+(e.getAttribute('data-src')||'')+' '+(e.getAttribute('onclick')||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.title||'')+' '+((e.parentElement&&(e.parentElement.innerText||e.parentElement.getAttribute('href')||''))||'');}catch(e){}return s.toLowerCase();}"
                + "function findTarget(){var candidates=[].slice.call(document.querySelectorAll('[stream],[data-stream],[data-src],button,a,[role=button],.play,.play-button,.jp-play,.mejs-playpause-button'));"
                + "if(targetCode){for(var i=0;i<candidates.length;i++){if(attrs(candidates[i]).indexOf(targetCode)>=0)return candidates[i];}}"
                + "return document.getElementById('set_radio_button')||candidates.find(function(e){return attrs(e).indexOf('play')>=0||attrs(e).indexOf('listen')>=0||attrs(e).indexOf('start')>=0;})||document.querySelector('[stream],[data-stream],[data-src]');}"
                + "function streamOf(e){if(!e)return '';return e.getAttribute('stream')||e.getAttribute('data-stream')||e.getAttribute('data-src')||'';}"
                + "function ownedAudio(src){var a=document.getElementById('radioautoplay_audio');if(!a){a=document.createElement('audio');a.id='radioautoplay_audio';a.controls=true;a.preload='auto';a.style.position='fixed';a.style.left='0';a.style.bottom='0';a.style.width='1px';a.style.height='1px';document.body.appendChild(a);}if(src&&a.src!==src){a.src=src;}return a;}"
                + "function removeOwned(){var a=document.getElementById('radioautoplay_audio');if(a){try{a.pause();a.muted=true;a.volume=0;a.remove();}catch(e){}}}"
                + "function playOne(){var target=findTarget();var src=streamOf(target);stopOtherMedia(null);if(allowPagePlayer){removeOwned();if(target){try{target.click();}catch(e){}}setTimeout(function(){var media=allMedia().filter(function(m){try{return !m.paused&&!m.ended;}catch(e){return false;}});var keep=media[0]||null;stopOtherMedia(keep);if(keep){try{keep.muted=false;keep.volume=targetVolume;}catch(e){}}},1500);return;}if(target&&!src){try{target.click();}catch(e){}}"
                + "var owned=src?ownedAudio(src):document.getElementById('radioautoplay_audio');"
                + "if(owned){try{stopOtherMedia(owned);owned.muted=false;owned.autoplay=true;owned.volume=targetVolume;owned.play&&owned.play();return;}catch(e){}}"
                + "var media=allMedia().filter(function(m){try{return m.id!=='radioautoplay_audio'&&(m.src||m.currentSrc||m.querySelector('source'));}catch(e){return false;}});"
                + "var chosen=media[0];if(chosen){try{stopOtherMedia(chosen);chosen.muted=false;chosen.autoplay=true;chosen.controls=true;chosen.volume=targetVolume;chosen.play&&chosen.play();}catch(e){}}"
                + "}"
                + "playOne();"
                + "setTimeout(playOne,1000);"
                + "setTimeout(playOne,3000);"
                + "})();";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(script, null);
        } else {
            view.loadUrl("javascript:" + script);
        }
    }

    private void verifyWebPlaybackStarted(String pageUrl, int requestId, int attempt) {
        if (requestId != playbackRequestId || webViewPlayer == null) return;
        String probeScript =
                "(function(){"
                        + "try{"
                        + "var media=[].slice.call(document.querySelectorAll('audio,video'));"
                        + "var playing=media.some(function(m){return !m.paused&&!m.ended&&m.readyState>=2;});"
                        + "var title=(document.title||'').trim();"
                        + "return JSON.stringify({playing:playing,title:title,count:media.length});"
                        + "}catch(e){return JSON.stringify({playing:false,title:'',count:0});}"
                        + "})();";
        webViewPlayer.evaluateJavascript(probeScript, value -> {
            if (requestId != playbackRequestId || webViewPlayer == null) return;
            String result = value == null ? "" : value;
            boolean playing = result.contains("\"playing\":true");
            int mediaCount = extractWebMediaCount(result);
            logInfo("verifyWebPlaybackStarted requestId=" + requestId
                    + " attempt=" + attempt + " playing=" + playing + " mediaCount=" + mediaCount
                    + " payload=" + result);
            if (!playing && attempt < WEB_AUTOPLAY_PROBE_ATTEMPTS) {
                injectAutoplayScript(webViewPlayer, pageUrl, attempt >= 2);
                handler.postDelayed(() -> verifyWebPlaybackStarted(pageUrl, requestId, attempt + 1), 1_500L);
                return;
            }
            if (playing) {
                markWebPlaybackStarted(pageUrl, requestId, extractWebTitle(result));
            } else {
                logWarn("Web playback not confirmed yet; staying in starting state requestId="
                        + requestId + " pageUrl=" + pageUrl);
                broadcastState(false, null, "Starting stream");
                updateNotification("Starting stream", currentUrl);
                handler.postDelayed(() -> verifyWebPlaybackStarted(pageUrl, requestId, 0), 5_000L);
            }
        });
    }

    private void resetStreamWatchdogForWebPlayback(int requestId, String pageUrl) {
        cancelStreamWatchdog();
        streamStartTimeout = () -> {
            if (requestId == playbackRequestId && !isPlaying) {
                // Do not auto-skip web station on timeout; keep attempting autoplay in-place.
                logWarn("Web station start still pending after timeout; keeping current station. pageUrl=" + pageUrl);
                if (webViewPlayer != null) {
                    injectAutoplayScript(webViewPlayer, pageUrl, true);
                }
                handler.postDelayed(() -> resetStreamWatchdogForWebPlayback(requestId, pageUrl),
                        WEB_POST_LOAD_START_TIMEOUT_MS);
            }
        };
        handler.postDelayed(streamStartTimeout, WEB_POST_LOAD_START_TIMEOUT_MS);
    }

    private void markWebPlaybackStarted(String pageUrl, int requestId, String pageTitle) {
        if (requestId != playbackRequestId || webViewPlayer == null || isPlaying) return;
        cancelStreamWatchdog();
        stopWaitingLoop();
        requestAudioFocus();
        isPlaying = true;
        streamPrepared = true;
        webSilentChecks = 0;
        applyWebAudioNormalizer();
        urlManager.markStreamSuccess(activePlaybackUrl != null ? activePlaybackUrl : pageUrl);
        String station = getAnnouncementStationName(pageTitle, pageUrl);
        String displayUrl = activePlaybackUrl != null && !activePlaybackUrl.trim().isEmpty()
                ? activePlaybackUrl : pageUrl;
        currentUrl = station + "\n" + displayUrl;
        logInfo("Web playback confirmed requestId=" + requestId + " station=" + station
                + " displayUrl=" + displayUrl + " pageUrl=" + pageUrl);
        broadcastState(true, null, "Playing web player");
        updateNotification("Playing web player", station);
        startWebPlaybackMonitor(requestId);
    }

    private String extractWebTitle(String jsonValue) {
        if (jsonValue == null) return "";
        String raw = jsonValue.replace("\\\"", "\"");
        int idx = raw.indexOf("\"title\":\"");
        if (idx < 0) return "";
        int from = idx + 9;
        int to = raw.indexOf("\"", from);
        if (to <= from) return "";
        return raw.substring(from, to).trim();
    }

    private String extractOnlineRadioBoxStationCode(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !host.toLowerCase(Locale.US).contains("onlineradiobox.com")) {
                return "";
            }
            String code = uri.getQueryParameter("cs");
            return code != null ? code.trim().toLowerCase(Locale.US) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String jsString(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void startWebPlaybackMonitor(int requestId) {
        cancelWebPlaybackMonitor();
        webPlaybackMonitor = new Runnable() {
            @Override
            public void run() {
                if (requestId != playbackRequestId || webViewPlayer == null || !isPlaying) return;
                String script =
                        "(function(){"
                                + "try{"
                                + "var media=[].slice.call(document.querySelectorAll('audio,video'));"
                                + "var playing=media.some(function(m){return !m.paused&&!m.ended&&m.readyState>=2;});"
                                + "return JSON.stringify({playing:playing,count:media.length});"
                                + "}catch(e){return '0';}"
                                + "})();";
                webViewPlayer.evaluateJavascript(script, value -> {
                    if (requestId != playbackRequestId || webViewPlayer == null || !isPlaying) return;
                    String payload = value == null ? "" : value;
                    boolean playing = payload.contains("\"playing\":true");
                    int mediaCount = extractWebMediaCount(payload);
                    if (playing) {
                        webSilentChecks = 0;
                        webNoMediaChecks = 0;
                    } else {
                        injectAutoplayScript(webViewPlayer,
                                activePlaybackUrl != null ? activePlaybackUrl : currentUrl, true);
                        if (mediaCount > 0) {
                            webSilentChecks++;
                        } else {
                            webNoMediaChecks++;
                        }
                    }
                    handler.postDelayed(this, WEB_HEALTHCHECK_INTERVAL_MS);
                });
            }
        };
        handler.postDelayed(webPlaybackMonitor, WEB_HEALTHCHECK_INTERVAL_MS);
    }

    private void cancelWebPlaybackMonitor() {
        if (handler != null && webPlaybackMonitor != null) {
            handler.removeCallbacks(webPlaybackMonitor);
            webPlaybackMonitor = null;
        }
        webSilentChecks = 0;
        webNoMediaChecks = 0;
    }

    private int extractWebMediaCount(String jsonValue) {
        if (jsonValue == null) return 0;
        String raw = jsonValue.replace("\\\"", "\"");
        int idx = raw.indexOf("\"count\":");
        if (idx < 0) return 0;
        int from = idx + 8;
        int to = from;
        while (to < raw.length() && Character.isDigit(raw.charAt(to))) {
            to++;
        }
        if (to <= from) return 0;
        try {
            return Integer.parseInt(raw.substring(from, to));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void stopPlayback() {
        stopPlayback(true);
    }

    private void stopPlayback(boolean broadcastIdle) {
        isPlaying = false;
        introFinished = false;
        streamPrepared = false;
        webFallbackStarted = false;
        offlineMode = false;
        activePlaybackUrl = null;
        cancelWebPlaybackMonitor();
        cancelIntroStartDelay();
        cancelStreamWatchdog();
        releaseIntroPlayerOnly();
        stopWaitingLoop();
        stopOfflineFallback(false);
        releaseWebViewOnly();
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
        logWarn("switchToAnotherStream reason=" + reason + " currentUrl=" + currentUrl
                + " activePlaybackUrl=" + activePlaybackUrl + " failoverAttempts=" + failoverAttempts);
        urlManager.markStreamFailure(activePlaybackUrl != null ? activePlaybackUrl : currentUrl);
        releaseMediaPlayerOnly();
        releaseWebViewOnly();

        List<String> urls = urlManager.getAutoPlaybackUrls();
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
        activePlaybackUrl = nextUrl;
        introFinished = true;
        streamPrepared = false;
        webFallbackStarted = false;
        releaseIntroPlayerOnly();
        broadcastState(false, null, reason + ". Trying another stream");
        updateNotification("Trying backup stream", nextUrl);
        startWaitingLoop();
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
                Map<String, String> sourceHeaders = createPlayerHeaders();
                sourceHeaders.put("Referer", finalUrl);
                return new StreamSource(sourceUrl, finalUrl, sourceHeaders);
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

    private void startPreparedStream(int requestId) {
        if (requestId != playbackRequestId || mediaPlayer == null || isPlaying) return;
        stopWaitingLoop();
        releaseIntroPlayerOnly();
        requestAudioFocus();
        startPreparedStreamAfterAnnouncement(requestId);
    }

    private void startPreparedStreamAfterAnnouncement(int requestId) {
        if (requestId != playbackRequestId || mediaPlayer == null || isPlaying) return;
        try {
            mediaPlayer.start();
            isPlaying = true;
            logInfo("Direct playback started requestId=" + requestId + " url=" + currentUrl);
            urlManager.markStreamSuccess(activePlaybackUrl != null ? activePlaybackUrl : currentUrl);
            broadcastState(true, null);
            updateNotification("Playing", currentUrl);
            Log.d(TAG, "Playback started");
        } catch (Exception e) {
            logError("Prepared stream could not start requestId=" + requestId + " url=" + currentUrl, e);
            switchToAnotherStream("Unable to play stream");
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

        String onlineRadioBoxStream = extractOnlineRadioBoxStream(baseUrl, html);
        if (onlineRadioBoxStream != null) {
            return onlineRadioBoxStream;
        }

        Pattern mediaSourcePattern = Pattern.compile(
                "(?i)<(?:audio|video|source)[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]");
        Matcher matcher = mediaSourcePattern.matcher(html);
        if (matcher.find()) {
            return resolveHtmlUrl(baseUrl, matcher.group(1));
        }

        Pattern playerStreamAttributePattern = Pattern.compile(
                "(?i)\\b(?:data-stream|stream|data-src)\\s*=\\s*['\"]([^'\"]+)['\"]");
        matcher = playerStreamAttributePattern.matcher(html);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (looksLikePlayableStream(candidate)) {
                return resolveHtmlUrl(baseUrl, candidate);
            }
        }

        Pattern scriptUrlPattern = Pattern.compile(
                "(?i)['\"](https?://[^'\"]+(?:mp3|aac|m3u8|stream|icecast\\.audio|/proxy/)[^'\"]*)['\"]");
        matcher = scriptUrlPattern.matcher(html);
        if (matcher.find()) {
            return resolveHtmlUrl(baseUrl, matcher.group(1));
        }

        Pattern relativeUrlPattern = Pattern.compile(
                "(?i)['\"]([^'\"]*(?:mp3|aac|m3u8|stream|icecast\\.audio|/proxy/)[^'\"]*)['\"]");
        matcher = relativeUrlPattern.matcher(html);
        if (matcher.find()) {
            return resolveHtmlUrl(baseUrl, matcher.group(1));
        }

        return null;
    }

    private String extractOnlineRadioBoxStream(String baseUrl, String html) {
        String stationCode = extractOnlineRadioBoxStationCode(baseUrl);
        if (stationCode.isEmpty()) return null;

        String quotedCode = Pattern.quote(stationCode);
        Pattern radioIdThenStream = Pattern.compile(
                "(?is)<[^>]+radioId\\s*=\\s*['\"]" + quotedCode
                        + "['\"][^>]+stream\\s*=\\s*['\"]([^'\"]+)['\"]");
        Matcher matcher = radioIdThenStream.matcher(html);
        if (matcher.find()) {
            return resolveHtmlUrl(baseUrl, matcher.group(1));
        }

        Pattern streamThenRadioId = Pattern.compile(
                "(?is)<[^>]+stream\\s*=\\s*['\"]([^'\"]+)['\"][^>]+radioId\\s*=\\s*['\"]"
                        + quotedCode + "['\"]");
        matcher = streamThenRadioId.matcher(html);
        if (matcher.find()) {
            return resolveHtmlUrl(baseUrl, matcher.group(1));
        }

        return null;
    }

    private boolean looksLikePlayableStream(String value) {
        if (value == null) return false;
        String cleaned = value.replace("&amp;", "&").toLowerCase(Locale.US);
        return cleaned.startsWith("http://")
                || cleaned.startsWith("https://")
                || cleaned.startsWith("//")
                || cleaned.contains(".mp3")
                || cleaned.contains(".aac")
                || cleaned.contains(".m3u8")
                || cleaned.contains("/stream")
                || cleaned.contains("/livestream")
                || cleaned.contains("icecast")
                || cleaned.contains("streamtheworld")
                || cleaned.contains("/proxy/");
    }

    private String resolveHtmlUrl(String baseUrl, String value) {
        try {
            String cleaned = value.replace("&amp;", "&").trim();
            return new URL(new URL(baseUrl), cleaned).toString();
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve media source URL", e);
            return value;
        }
    }

    private boolean isLikelyDirectStreamSource(String originalUrl, StreamSource source) {
        if (source == null || source.playUrl == null || source.playUrl.trim().isEmpty()) return false;
        String playUrl = source.playUrl.trim().toLowerCase(Locale.US);
        String original = originalUrl == null ? "" : originalUrl.trim().toLowerCase(Locale.US);

        if (playUrl.equals(original)) return false;

        return playUrl.contains(".mp3")
                || playUrl.contains(".aac")
                || playUrl.contains(".m3u8")
                || playUrl.contains("/stream")
                || playUrl.contains("/icecast")
                || playUrl.contains("audio")
                || playUrl.contains("radio");
    }

    private void releaseMediaPlayerOnly() {
        if (mediaPlayer != null) {
            try {
                releaseLoudnessEnhancer();
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

    private void releaseWebViewOnly() {
        if (webViewPlayer != null) {
            WebView oldView = webViewPlayer;
            webViewPlayer = null;
            try {
                pauseAndMuteWebMedia(oldView);
                oldView.stopLoading();
                oldView.loadUrl("about:blank");
                oldView.onPause();
                oldView.pauseTimers();
                oldView.removeAllViews();
                oldView.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing web player", e);
            }
        }
        cancelWebPlaybackMonitor();
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
        logInfo("Voice alert: " + message);
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

        duckMusicVolume();

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
            textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        } else {
            java.util.HashMap<String, String> params = new java.util.HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            //noinspection deprecation
            textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, params);
        }
    }

    private void applyDirectAudioNormalizer(MediaPlayer player) {
        if (player == null) return;
        try {
            player.setVolume(NORMALIZED_STREAM_VOLUME, NORMALIZED_STREAM_VOLUME);
        } catch (Exception e) {
            Log.w(TAG, "Could not set normalized stream volume", e);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return;
        releaseLoudnessEnhancer();
        try {
            loudnessEnhancer = new LoudnessEnhancer(player.getAudioSessionId());
            loudnessEnhancer.setTargetGain(LOUDNESS_ENHANCER_GAIN_MB);
            loudnessEnhancer.setEnabled(true);
            logInfo("Direct stream normalizer enabled. gainMb=" + LOUDNESS_ENHANCER_GAIN_MB
                    + " volume=" + NORMALIZED_STREAM_VOLUME);
        } catch (Exception e) {
            loudnessEnhancer = null;
            Log.w(TAG, "Could not enable direct stream normalizer", e);
        }
    }

    private void releaseLoudnessEnhancer() {
        if (loudnessEnhancer == null) return;
        try {
            loudnessEnhancer.setEnabled(false);
            loudnessEnhancer.release();
        } catch (Exception e) {
            Log.w(TAG, "Could not release loudness enhancer", e);
        }
        loudnessEnhancer = null;
    }

    private void applyWebAudioNormalizer() {
        if (webViewPlayer == null) return;
        try {
            String script = "(function(){"
                    + "var targetVolume=" + NORMALIZED_STREAM_VOLUME + ";"
                    + "var media=[].slice.call(document.querySelectorAll('audio,video'));"
                    + "media.forEach(function(m){try{m.volume=targetVolume;m.muted=false;}catch(e){}});"
                    + "})();";
            webViewPlayer.evaluateJavascript(script, null);
        } catch (Exception e) {
            Log.w(TAG, "Could not apply web audio normalizer", e);
        }
    }

    private void startWaitingLoop() {
        // Waiting music disabled by user request.
    }

    private void stopWaitingLoop() {
        if (waitingPlayer != null) {
            try {
                if (waitingPlayer.isPlaying()) waitingPlayer.stop();
                waitingPlayer.reset();
                waitingPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing waiting player", e);
            }
            waitingPlayer = null;
        }
    }

    private void restoreMusicVolume() {
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.setVolume(NORMALIZED_STREAM_VOLUME, NORMALIZED_STREAM_VOLUME);
            } catch (Exception e) {
                Log.w(TAG, "Could not restore stream volume", e);
            }
        }
        if (webViewPlayer != null && isPlaying) {
            try {
                webViewPlayer.evaluateJavascript(
                        "(function(){var targetVolume=" + NORMALIZED_STREAM_VOLUME
                                + ";var m=[].slice.call(document.querySelectorAll('audio,video'));"
                                + "m.forEach(function(x){try{x.volume=targetVolume;x.muted=false;}catch(e){}});})();",
                        null);
            } catch (Exception e) {
                Log.w(TAG, "Could not restore web stream volume", e);
            }
        }
    }

    private void duckMusicVolume() {
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.setVolume(0.28f, 0.28f);
            } catch (Exception e) {
                Log.w(TAG, "Could not duck stream for announcement", e);
            }
        }
        if (webViewPlayer != null && isPlaying) {
            try {
                webViewPlayer.evaluateJavascript(
                        "(function(){var m=[].slice.call(document.querySelectorAll('audio,video'));m.forEach(function(x){x.volume=0.22;});})();",
                        null);
            } catch (Exception e) {
                Log.w(TAG, "Could not duck web stream for announcement", e);
            }
        }
    }

    private void startStatusAnnouncements() {
        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                stopForQuietHoursIfNeeded();
                announceTimeIfNeeded();
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
        if (!isPlaying && mediaPlayer == null && introPlayer == null
                && waitingPlayer == null && offlinePlayer == null) return;

        broadcastState(false, null, "Quiet hours started. Playback paused until 6:00 AM");
        updateNotification("Quiet hours", currentUrl);
        speakVoiceAlert("Quiet hours started. Playback is paused until 6 A M.", null);
        playbackRequestId++;
        stopPlayback(false);
        stopSelf();
    }

    private void announceTimeIfNeeded() {
        // Intentionally no-op now: frequent TTS announcements were disrupting long-running playback.
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
            logWarn("Network disconnected");
            if (!offlineMode) {
                startOfflineFallback("Network disconnected. Playing offline backup audio.");
            }
        } else if (!networkWasConnected && connected) {
            logInfo("Network reconnected");
            if (offlineMode) {
                stopOfflineFallback(true);
            }
        }
        networkWasConnected = connected;
    }

    private boolean isNetworkConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
        return info != null && info.isConnected();
    }

    private void startOfflineFallback(String reason) {
        playbackRequestId++;
        stopPlayback(false);
        offlineMode = true;
        stopWaitingLoop();
        currentUrl = "Offline backup audio";
        introFinished = true;
        streamPrepared = false;
        webFallbackStarted = false;
        isPlaying = false;
        startForeground(NOTIF_ID, buildNotification("Offline backup playing", currentUrl));
        broadcastState(true, null, reason);
        updateNotification("Offline backup playing", currentUrl);
        logWarn("startOfflineFallback reason=" + reason + " lastRequestedUrl=" + lastRequestedUrl);
        try {
            offlinePlayer = MediaPlayer.create(this, R.raw.kenny_g_songbird_gran_turismo_soundtrack);
            if (offlinePlayer == null) {
                logError("Offline fallback file could not be loaded", null);
                return;
            }
            setPlayerAudioMode(offlinePlayer);
            offlinePlayer.setLooping(true);
            offlinePlayer.setOnErrorListener((mp, what, extra) -> {
                logError("Offline fallback player error what=" + what + " extra=" + extra, null);
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
        if (resumeRadio && lastRequestedUrl != null && !lastRequestedUrl.trim().isEmpty()) {
            if (urlManager != null && !urlManager.isAppEnabled()) {
                logInfo("App disabled; not resuming radio after reconnect");
                stopSelf();
                return;
            }
            if (isQuietHoursNow()) {
                logInfo("Quiet hours active; not resuming radio after reconnect");
                stopSelf();
                return;
            }
            logInfo("Resuming radio after reconnect url=" + lastRequestedUrl);
            failoverAttempts = 0;
            playbackRequestId++;
            startPlaybackAfterIntro(lastRequestedUrl, playbackRequestId);
        }
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

    private String getAnnouncementStationName(String pageTitle, String fallbackUrl) {
        String cleaned = cleanStationName(pageTitle);
        if (!cleaned.isEmpty()) return cleaned;
        return cleanStationName(getStationName(fallbackUrl));
    }

    private String cleanStationName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("(?i)\\b(listen|live|online|radio|station|stream|fm|am)\\b", " ")
                .replace("|", " ")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) return "your radio station";
        return cleaned;
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
