package com.radioautoplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Manages direct audio stream URLs and playback settings.
 */
public class StreamUrlManager {

    private static final String PREF_NAME       = "radio_prefs";
    private static final String KEY_URLS         = "stream_urls";
    private static final String KEY_WEB_URLS     = "web_stream_urls";
    private static final String KEY_ACTIVE_IDX   = "active_index";
    private static final String KEY_APP_ENABLED  = "app_enabled";
    private static final String KEY_SHUFFLE      = "shuffle_mode";
    private static final String KEY_QUIET_HOURS  = "quiet_hours_enabled";
    private static final String KEY_START_DELAY  = "start_delay_enabled";
    private static final String KEY_VISUALIZER   = "visualizer_enabled";
    private static final String KEY_DEFAULTS_ADDED = "default_streams_added";
    private static final String KEY_DEFAULTS_VERSION = "default_streams_version";
    private static final int DEFAULT_STREAMS_VERSION = 9;

    private static final String[] DEFAULT_STREAM_URLS = {
            "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
            "https://eu8.fastcast4u.com/proxy/clyedupq?mp=%2F1?aw_0_req_lsid=2c0fae177108c9a42a7cf24878625444",
            "https://stream.zeno.fm/dbstwo3dvhhtv",
            "https://stream.zeno.fm/6quh1pfnt1duv",
            "https://s8.voscast.com:7021/stream",
            "https://drive.uber.radio/uber-app/bollywooddance/icecast.audio",
            "https://srv01.onlineradio.voaplus.com/kissfm",
            "https://server.mixify.in:8010/radio.mp3",
            "https://live.cmr24.net/CMR/Desi_Music-MQ/icecast.audio",
            "https://ice42.securenetsystems.net/KQBK?playSessionID=893715E2-D578-F731-09E75DFFEE53C84F",
            "https://stream.zeno.fm/a2gyqzwpwfeuv",
            "https://www.streamcontrol.net:8444/s/12010/",
            "https://uksoutha.streaming.broadcast.radio/awazfm",
            "https://cp11.serverse.com/proxy/foxfm/stream",
            "https://media-ssl.musicradio.com/HeartLondon",
            "https://media-ssl.musicradio.com/Capital",
            "https://virgin.live.stream.broadcasting.news/stream",
            "https://ice8.securenetsystems.net/EASY96",
            "https://npr-ice.streamguys1.com/live.mp3",
            "https://apnews.cdnstream1.com/apnews",
            "https://tunein.cdnstream1.com/3519_96.mp3"
    };

    private final SharedPreferences prefs;
    private final Random random = new Random();

    public StreamUrlManager(Context context) {
        Context appContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Context deviceContext = appContext.createDeviceProtectedStorageContext();
            deviceContext.moveSharedPreferencesFrom(appContext, PREF_NAME);
            prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } else {
            prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
        syncDirectStreamsIfNeeded();
    }

    public List<String> getUrls() {
        Set<String> set = prefs.getStringSet(KEY_URLS, new LinkedHashSet<>());
        return new ArrayList<>(set);
    }

    public List<String> getAllPlaybackUrls() {
        return getUrls();
    }

    public void addUrl(String url) {
        if (!isPlayableStreamUrl(url)) return;
        List<String> current = getUrls();
        if (!containsEquivalentUrl(current, url)) {
            current.add(url.trim());
            saveList(current);
        }
    }

    public int addUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return 0;

        List<String> current = getUrls();
        int added = 0;
        for (String url : urls) {
            if (isPlayableStreamUrl(url) && !containsEquivalentUrl(current, url)) {
                current.add(url.trim());
                added++;
            }
        }

        if (added > 0) saveList(current);
        return added;
    }

    public void removeUrl(int index) {
        List<String> current = getUrls();
        if (index >= 0 && index < current.size()) {
            current.remove(index);
            saveList(current);
            int active = getActiveIndex();
            if (active >= current.size()) {
                setActiveIndex(Math.max(0, current.size() - 1));
            }
        }
    }

    public void updateUrl(int index, String newUrl) {
        if (!isPlayableStreamUrl(newUrl)) return;
        List<String> current = getUrls();
        if (index >= 0 && index < current.size()) {
            current.set(index, newUrl.trim());
            saveList(current);
        }
    }

    private void saveList(List<String> list) {
        LinkedHashSet<String> set = new LinkedHashSet<>(dedupeUrls(list));
        prefs.edit().putStringSet(KEY_URLS, set).apply();
    }

    public String getNextUrl() {
        List<String> urls = getAllPlaybackUrls();
        if (urls.isEmpty()) return null;

        int idx;
        if (isShuffleEnabled() && urls.size() > 1) {
            int current = getActiveIndex();
            do { idx = random.nextInt(urls.size()); } while (idx == current);
        } else {
            idx = (getActiveIndex() + 1) % urls.size();
        }
        setActiveIndex(idx);
        return urls.get(idx);
    }

    public String getCurrentUrl() {
        List<String> urls = getAllPlaybackUrls();
        if (urls.isEmpty()) return null;
        int idx = getActiveIndex();
        if (idx >= urls.size()) idx = 0;
        return urls.get(idx);
    }

    public int getActiveIndex() {
        return prefs.getInt(KEY_ACTIVE_IDX, 0);
    }

    public void setActiveIndex(int index) {
        prefs.edit().putInt(KEY_ACTIVE_IDX, index).apply();
    }

    public boolean isShuffleEnabled() {
        return prefs.getBoolean(KEY_SHUFFLE, true);
    }

    public void setShuffleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHUFFLE, enabled).apply();
    }

    public boolean isAppEnabled() {
        return prefs.getBoolean(KEY_APP_ENABLED, true);
    }

    public void setAppEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_APP_ENABLED, enabled).apply();
    }

    public boolean isQuietHoursEnabled() {
        return prefs.getBoolean(KEY_QUIET_HOURS, true);
    }

    public void setQuietHoursEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_QUIET_HOURS, enabled).apply();
    }

    public boolean isStartDelayEnabled() {
        return prefs.getBoolean(KEY_START_DELAY, true);
    }

    public void setStartDelayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_START_DELAY, enabled).apply();
    }

    public boolean isVisualizerEnabled() {
        return prefs.getBoolean(KEY_VISUALIZER, true);
    }

    public void setVisualizerEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VISUALIZER, enabled).apply();
    }

    public boolean isEmpty() {
        return getAllPlaybackUrls().isEmpty();
    }

    public static boolean isPlayableStreamUrl(String value) {
        if (value == null) return false;
        value = value.trim().toLowerCase();
        return (value.startsWith("http://") || value.startsWith("https://"))
                && !value.startsWith("blob:")
                && !value.endsWith(".html")
                && !value.endsWith(".htm")
                && !value.contains("/watch?")
                && !value.contains("youtube.com")
                && !value.contains("youtu.be")
                && !value.contains("onlineradiobox.com")
                && !value.contains("onlineradiofm.in/stations");
    }

    public static String getRadioNameForUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) return "Radio station";
            return titleCase(host.replace("www.", "").replace(".", " "));
        } catch (Exception ignored) {
            return "Radio station";
        }
    }

    private void syncDirectStreamsIfNeeded() {
        int version = prefs.getInt(KEY_DEFAULTS_VERSION, 0);
        if (prefs.getBoolean(KEY_DEFAULTS_ADDED, false) && version >= DEFAULT_STREAMS_VERSION) return;

        List<String> current = getUrls();
        current.addAll(readLegacyWebUrls());
        current = dedupeUrls(current);
        List<String> playable = new ArrayList<>();
        for (String url : current) {
            if (isPlayableStreamUrl(url)) {
                playable.add(url);
            }
        }
        current = playable;

        for (String url : DEFAULT_STREAM_URLS) {
            if (isPlayableStreamUrl(url) && !containsEquivalentUrl(current, url)) {
                current.add(url);
            }
        }

        LinkedHashSet<String> directSet = new LinkedHashSet<>(current);
        prefs.edit()
                .putStringSet(KEY_URLS, directSet)
                .remove(KEY_WEB_URLS)
                .putBoolean(KEY_DEFAULTS_ADDED, true)
                .putInt(KEY_DEFAULTS_VERSION, DEFAULT_STREAMS_VERSION)
                .apply();
    }

    private List<String> readLegacyWebUrls() {
        Set<String> set = prefs.getStringSet(KEY_WEB_URLS, new LinkedHashSet<>());
        return new ArrayList<>(set);
    }

    private List<String> dedupeUrls(List<String> urls) {
        List<String> deduped = new ArrayList<>();
        if (urls == null || urls.isEmpty()) return deduped;

        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        for (String url : urls) {
            if (url == null) continue;
            String cleaned = url.trim();
            if (cleaned.isEmpty()) continue;
            String key = normalizeUrl(cleaned);
            if (seenKeys.add(key)) {
                deduped.add(cleaned);
            }
        }
        return deduped;
    }

    private boolean containsEquivalentUrl(List<String> urls, String candidate) {
        if (candidate == null || urls == null) return false;
        String candidateKey = normalizeUrl(candidate);
        for (String url : urls) {
            if (url != null && normalizeUrl(url).equals(candidateKey)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeUrl(String url) {
        if (url == null) return "";
        String normalized = url.trim().toLowerCase();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String titleCase(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String[] parts = value.trim().replaceAll("\\s+", " ").split(" ");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) result.append(part.substring(1));
        }
        return result.toString();
    }
}
