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
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Foreground service that manages MediaPlayer for radio streaming.
 * Keeps a CPU wake-lock so playback isn't killed by Doze.
 */
public class RadioService extends Service {

    private static final String TAG          = "RadioService";
    private static final String CHANNEL_ID   = "radio_channel";
    private static final int    NOTIF_ID     = 1;
    private static final long   PLAYBACK_START_DELAY_MS = 40_000L;
    private static final long   WEB_START_RETRY_MS = 2_000L;
    private static final long   WEB_SLOW_RETRY_MS = 8_000L;
    private static final int    WEB_AUTOPLAY_PROBE_ATTEMPTS = 12;
    private static final long   STATUS_CHECK_INTERVAL_MS = 60_000L;
    private static final int    LOW_BATTERY_PERCENT = 20;
    private static final int    QUIET_HOURS_START_HOUR = 0;
    private static final int    QUIET_HOURS_END_HOUR = 6;
    private static final float  NORMALIZED_STREAM_VOLUME = 0.82f;
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

    private MediaPlayer introPlayer;
    private MediaPlayer offlinePlayer;
    private WebView webViewPlayer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock  wifiLock;
    private StreamUrlManager urlManager;
    private IntroSoundManager introSoundManager;
    private DiagnosticsLogger diagnosticsLogger;
    private Handler handler;
    private AudioManager audioManager;
    private String currentUrl;
    private String activePlaybackUrl;
    private Runnable introStartDelay;
    private Runnable statusCheckRunnable;
    private boolean isPlaying = false;
    private boolean introFinished = false;
    private boolean webFallbackStarted = false;
    private boolean offlineMode = false;
    private boolean networkWasConnected = true;
    private boolean lowBatteryAnnounced = false;
    private int playbackRequestId = 0;
    private String lastRequestedUrl;
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
                    || webViewPlayer != null
                    || introPlayer != null
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
        long startDelayMs = urlManager != null && urlManager.isStartDelayEnabled()
                ? PLAYBACK_START_DELAY_MS : 0L;
        String delayStatus = startDelayMs > 0
                ? "Starting in " + (startDelayMs / 1000L) + " seconds"
                : "Starting now";
        startForeground(NOTIF_ID, buildNotification(delayStatus, url));
        broadcastState(false, null, delayStatus);
        logInfo("Delaying playback start by " + startDelayMs + "ms requestId="
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
        if (startDelayMs > 0) {
            handler.postDelayed(introStartDelay, startDelayMs);
        } else {
            handler.post(introStartDelay);
        }
    }

    private void startPlaybackAfterIntro(String url, int requestId) {
        stopPlayback(false);
        offlineMode = false;
        currentUrl = url;
        activePlaybackUrl = url;
        logInfo("startPlaybackAfterIntro requestId=" + requestId + " url=" + url);
        introFinished = false;
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
        startPlayback(url, requestId);
    }

    private void startPlayback(String url, int requestId) {
        releaseWebViewOnly();
        webFallbackStarted = false;
        if (requestId != playbackRequestId) return;

        currentUrl = url;
        activePlaybackUrl = url;
        logInfo("startPlayback requestId=" + requestId + " url=" + url);

        startWebPageFallback(url, requestId);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startWebPageFallback(String url, int requestId) {
        if (requestId != playbackRequestId) return;
        if (webFallbackStarted) {
            logWarn("Web player already started. requestId=" + requestId);
            return;
        }

        webFallbackStarted = true;
        requestAudioFocus();
        broadcastState(false, null, "Opening web player");
        updateNotification("Opening web player", url);
        logInfo("startWebPageFallback requestId=" + requestId + " url=" + url);

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
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
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

                @Override
                public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                    if (consoleMessage != null) {
                        logInfo("Web console: " + consoleMessage.message());
                    }
                    return true;
                }
            });

            webViewPlayer.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String pageUrl) {
                    if (requestId != playbackRequestId) return;
                    startWebAutoplayNow(view, pageUrl, requestId);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    boolean isMainFrame = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                            || (request != null && request.isForMainFrame());
                    if (requestId == playbackRequestId && isMainFrame) {
                        logWarn("Web page main-frame error requestId=" + requestId + " page=" + currentUrl);
                        handleWebPageFailed(requestId, "Web player page failed");
                    }
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return;
                    if (requestId == playbackRequestId) {
                        logWarn("Web page main-frame error requestId=" + requestId
                                + " code=" + errorCode + " description=" + description);
                        handleWebPageFailed(requestId, "Web player page failed");
                    }
                }
            });

            webViewPlayer.onResume();
            webViewPlayer.resumeTimers();
            webViewPlayer.loadUrl(url, createPlayerHeaders());
        });
    }

    private void startWebAutoplayNow(WebView view, String pageUrl, int requestId) {
        if (requestId != playbackRequestId || view == null || isPlaying) return;
        requestAudioFocus();
        view.setNetworkAvailable(true);
        view.onResume();
        view.resumeTimers();
        view.getSettings().setMediaPlaybackRequiresUserGesture(false);
        injectAutoplayScript(view, pageUrl);
        logInfo("Web autoplay start requestId=" + requestId + " pageUrl=" + pageUrl);
        handler.postDelayed(() -> verifyWebPlaybackStarted(pageUrl, requestId, 0), WEB_START_RETRY_MS);
    }

    private void stopWebMedia(WebView view) {
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

    private void injectAutoplayScript(WebView view, String targetUrl) {
        String stationCode = jsString(extractOnlineRadioBoxStationCode(targetUrl));
        String script = "(function(){"
                + "var targetVolume=" + NORMALIZED_STREAM_VOLUME + ";"
                + "var targetCode='" + stationCode + "';"
                + "function log(m){try{console.log('RadioAutoPlay '+m);}catch(e){}}"
                + "function q(s){return [].slice.call(document.querySelectorAll(s));}"
                + "function attr(e,n){try{return e.getAttribute(n)||'';}catch(x){return '';}}"
                + "function cls(e){try{return typeof e.className==='string'?e.className:((e.className&&e.className.baseVal)||'');}catch(x){return '';}}"
                + "function abs(src){src=(src||'').replace(/&amp;/g,'&').trim();if(!src)return '';if(src.indexOf('//')===0)return location.protocol+src;try{return new URL(src,location.href).href;}catch(e){return src;}}"
                + "function allMedia(){return q('audio,video');}"
                + "function stopOthers(keep){allMedia().forEach(function(m){try{if(m!==keep){m.pause();m.muted=true;m.volume=0;}}catch(e){}});}"
                + "function alreadyPlaying(){return allMedia().some(function(m){try{return !m.paused&&!m.ended&&(m.readyState>0||m.currentTime>0);}catch(e){return false;}});}"
                + "function text(e){try{return ((e.id||'')+' '+cls(e)+' '+(e.href||'')+' '+attr(e,'stream')+' '+attr(e,'data-stream')+' '+attr(e,'data-src')+' '+attr(e,'radioId')+' '+attr(e,'radioid')+' '+attr(e,'radioName')+' '+attr(e,'radioname')+' '+attr(e,'aria-label')+' '+attr(e,'title')+' '+attr(e,'onclick')+' '+((e.parentElement&&(e.parentElement.innerText||cls(e.parentElement)))||'')).toLowerCase();}catch(x){return '';}}"
                + "function streamOf(e){if(!e)return '';var src=attr(e,'stream')||attr(e,'data-stream')||attr(e,'data-src')||attr(e,'src');if(!src&&e.currentSrc)src=e.currentSrc;if(!src){var s=e.querySelector&&e.querySelector('source[src]');if(s)src=attr(s,'src');}return abs(src);}"
                + "function bad(s){return /close|next|prev|previous|share|facebook|twitter|googleplay|playmarket|appstore|download/.test(s);}"
                + "function score(e){var s=text(e);if(bad(s))return -9999;var n=0;if(targetCode&&s.indexOf(targetCode)>=0)n+=1000;if(streamOf(e))n+=600;if(s.indexOf('station_play')>=0)n+=300;if(s.indexOf('b_top_play')>=0)n+=250;if(s.indexOf('button-play')>=0||s.indexOf('b-play')>=0)n+=220;if(s.indexOf('play')>=0)n+=120;if(s.indexOf('listen')>=0||s.indexOf('start')>=0)n+=80;return n;}"
                + "function findTarget(){var items=q('audio,video,source,[stream],[data-stream],[data-src],#b_top_play,#play,.button-play,svg.button-play,.b-play,.station_play,button,a,[role=button],[id*=play],[class*=play]');var best=null,bestScore=-1;items.forEach(function(e){var s=score(e);if(s>bestScore){best=e;bestScore=s;}});return best||document.getElementById('set_radio_button')||document.getElementById('play')||q('audio,video')[0]||null;}"
                + "function ownedAudio(src){var a=document.getElementById('radioautoplay_audio');if(!a){a=document.createElement('audio');a.id='radioautoplay_audio';a.controls=true;a.autoplay=true;a.preload='auto';a.style.position='fixed';a.style.left='0';a.style.bottom='0';a.style.width='1px';a.style.height='1px';document.body.appendChild(a);}if(src&&a.src!==src)a.src=src;return a;}"
                + "function playMedia(m){if(!m)return false;try{stopOthers(m);m.muted=false;m.autoplay=true;m.controls=true;m.volume=targetVolume;var p=m.play&&m.play();if(p&&p.then)p.then(function(){window.radioAutoPlayStarted=true;log('media playing');}).catch(function(e){log('play rejected '+e);});return true;}catch(e){log('playMedia error '+e);return false;}}"
                + "function clickTarget(e){if(!e)return;try{e.click();}catch(x){}try{e.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(x){}var p=e.closest&&e.closest('button,a,[role=button],.station_play,.b-play,.button-play');if(p&&p!==e){try{p.click();}catch(x){}}}"
                + "function playExisting(){var media=allMedia().filter(function(m){try{return m.id!=='radioautoplay_audio'&&(m.src||m.currentSrc||m.querySelector('source[src]'));}catch(e){return false;}});return playMedia(media[0]);}"
                + "function start(){if(window.radioAutoPlayStarted||alreadyPlaying()){window.radioAutoPlayStarted=true;return;}var target=findTarget();var src=streamOf(target);clickTarget(target);setTimeout(playExisting,350);setTimeout(function(){if(!alreadyPlaying()&&src){log('using stream '+src);playMedia(ownedAudio(src));}},1400);}"
                + "start();"
                + "setTimeout(start,1500);"
                + "setTimeout(start,3500);"
                + "}"
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
                        + "var playing=!!window.radioAutoPlayStarted||media.some(function(m){try{return !m.paused&&!m.ended&&(m.readyState>0||m.currentTime>0);}catch(e){return false;}});"
                        + "var title=(document.title||'').trim();"
                        + "var states=media.map(function(m){try{return {id:m.id||'',tag:m.tagName||'',paused:m.paused,ended:m.ended,readyState:m.readyState,currentTime:m.currentTime,src:m.currentSrc||m.src||''};}catch(e){return {};}});"
                        + "return JSON.stringify({playing:playing,title:title,count:media.length,states:states});"
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
                injectAutoplayScript(webViewPlayer, pageUrl);
                handler.postDelayed(() -> verifyWebPlaybackStarted(pageUrl, requestId, attempt + 1),
                        WEB_START_RETRY_MS);
                return;
            }
            if (playing) {
                markWebPlaybackStarted(pageUrl, requestId, extractWebTitle(result));
            } else {
                logWarn("Web playback not confirmed yet; staying in starting state requestId="
                        + requestId + " pageUrl=" + pageUrl);
                broadcastState(false, null, "Still starting web player");
                updateNotification("Still starting", currentUrl);
                handler.postDelayed(() -> verifyWebPlaybackStarted(pageUrl, requestId, 0),
                        WEB_SLOW_RETRY_MS);
            }
        });
    }

    private void markWebPlaybackStarted(String pageUrl, int requestId, String pageTitle) {
        if (requestId != playbackRequestId || webViewPlayer == null || isPlaying) return;
        requestAudioFocus();
        isPlaying = true;
        applyWebAudioNormalizer();
        String station = getAnnouncementStationName(pageTitle, pageUrl);
        String displayUrl = activePlaybackUrl != null && !activePlaybackUrl.trim().isEmpty()
                ? activePlaybackUrl : pageUrl;
        currentUrl = station + "\n" + displayUrl;
        logInfo("Web playback confirmed requestId=" + requestId + " station=" + station
                + " displayUrl=" + displayUrl + " pageUrl=" + pageUrl);
        broadcastState(true, null, "Playing web player");
        updateNotification("Playing web player", station);
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
        webFallbackStarted = false;
        offlineMode = false;
        activePlaybackUrl = null;
        cancelIntroStartDelay();
        releaseIntroPlayerOnly();
        stopOfflineFallback(false);
        releaseWebViewOnly();
        if (broadcastIdle) {
            broadcastState(false, null);
        }
        abandonAudioFocus();
    }

    private void handleWebPageFailed(int requestId, String reason) {
        if (requestId != playbackRequestId) return;
        broadcastState(false, reason, "Web player page failed");
        updateNotification("Web page failed", currentUrl);
    }

    private Map<String, String> createPlayerHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Upgrade-Insecure-Requests", "1");
        return headers;
    }

    private void releaseWebViewOnly() {
        if (webViewPlayer != null) {
            WebView oldView = webViewPlayer;
            webViewPlayer = null;
            try {
                stopWebMedia(oldView);
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
        if (!isPlaying && introPlayer == null && offlinePlayer == null) return;

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
        currentUrl = "Offline backup audio";
        introFinished = true;
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
        String knownName = StreamUrlManager.getRadioNameForUrl(url);
        if (!knownName.isEmpty() && !knownName.equals("Radio station")) {
            return knownName;
        }
        if (url == null || url.isEmpty()) return "your radio station";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "your radio station";
            return StreamUrlManager.getRadioNameForUrl(url);
        } catch (Exception e) {
            return "your radio station";
        }
    }

    private String getAnnouncementStationName(String pageTitle, String fallbackUrl) {
        String knownName = StreamUrlManager.getRadioNameForUrl(fallbackUrl);
        if (!knownName.isEmpty() && !knownName.equals("Radio station")) {
            return knownName;
        }
        String cleaned = cleanStationName(pageTitle);
        if (!cleaned.isEmpty()) return cleaned;
        return cleanStationName(getStationName(fallbackUrl));
    }

    private String cleanStationName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("(?i)\\bonline\\s+radio\\s+box\\b", " ")
                .replaceAll("(?i)\\bradio\\s+box\\b", " ")
                .replaceAll("(?i)\\b(listen|live|online|station|stream)\\b", " ")
                .replace("|", " ")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = cleaned.replaceAll("(?i)^to\\s+", "").trim();
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

}
