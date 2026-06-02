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
 * Manages the list of radio stream URLs.
 * Stored in SharedPreferences – no hardcoded links.
 */
public class StreamUrlManager {

    private static final String PREF_NAME       = "radio_prefs";
    private static final String KEY_URLS         = "stream_urls";
    private static final String KEY_ACTIVE_IDX   = "active_index";
    private static final String KEY_APP_ENABLED  = "app_enabled";
    private static final String KEY_SHUFFLE      = "shuffle_mode";
    private static final String KEY_QUIET_HOURS  = "quiet_hours_enabled";
    private static final String KEY_START_DELAY  = "start_delay_enabled";
    private static final String KEY_VISUALIZER   = "visualizer_enabled";
    private static final String KEY_DEFAULTS_ADDED = "default_streams_added";
    private static final String KEY_DEFAULTS_VERSION = "default_streams_version";
    private static final String KEY_WEB_URLS = "web_stream_urls";
    private static final String KEY_WEB_STATIONS_DEFAULT = "web_stations_default";
    private static final int DEFAULT_STREAMS_VERSION = 8;

    private static final String[] DEFAULT_WEB_STREAM_URLS = {
            "https://onlineradiofm.in/stations/mirchi",
            "https://onlineradiofm.in/stations/vividh-bharati",
            "https://onlineradiofm.in/stations/fm-gold",
            "https://onlineradiofm.in/stations/fm-rainbow",
            "https://onlineradiofm.in/stations/bbc-hindi",
            "https://onlineradiofm.in/stations/air-bhopal",
            "https://onlineradiofm.in/stations/cmr-hindi-fm",
            "https://onlineradiofm.in/stations/hungama-90s-once-again",
            "https://onlineradiofm.in/stations/hungama-hot-now-bollywood",
            "https://onlineradiofm.in/stations/city-mohammed-rafi",
            "https://onlineradiofm.in/stations/city-kishore-kumar",
            "https://onlineradiofm.in/stations/bollywood-and-beyond",
            "https://onlineradiofm.in/stations/mirchi-new-jersey",
            "https://onlineradiofm.in/stations/mirchi-bay-area",
            "https://onlineradiofm.in/stations/hungama-punjabi-hits",
            "https://onlineradiofm.in/stations/hungama-mehfil",
            "https://onlineradiofm.in/stations/radio-hungama-hot-now-telugu",
            "https://onlineradiofm.in/stations/bbc-world-servie",
            "https://onlineradiofm.in/stations/bbc-asian-network",
            "https://onlineradiobox.com/us/wbbr/?cs=us.wbbr&played=1",
            "https://onlineradiobox.com/us/977todayshits/?cs=us.977todayshits&played=1",
            "https://onlineradiobox.com/us/977comedy/?cs=us.977comedy&played=1",
            "https://onlineradiobox.com/us/?cs=us.npr&played=1",
            "https://onlineradiobox.com/uk/capitalfmuk/?cs=uk.capitalfmuk&played=1",
            "https://onlineradiobox.com/uk/?cs=uk.lbc973fm&played=1",
            "https://onlineradiobox.com/uk/?cs=uk.smoothradio1022&played=1",
            "https://onlineradiobox.com/in/?cs=in.karanaujla&played=1&p=4&tzLoc=Asia%2FCalcutta",
            "https://onlineradiobox.com/in/?cs=in.air&played=1&p=7&tzLoc=Asia%2FCalcutta",
            "https://onlineradiobox.com/in/?cs=za.hindvaniradio&played=1&p=1&sf_langs=hi%2C&tzLoc=Asia%2FCalcutta",
            "https://onlineradiofm.in/stations/all-india-air-akashvani",
            "https://onlineradiobox.com/in/?cs=in.easy60s&played=1&p=4&tzLoc=Asia%2FCalcutta",
            "https://onlineradiobox.com/in/Karnataka-/?cs=in.easy10s&played=1",
            "https://onlineradiobox.com/genre/talk/?cs=ca.cbcrtoronto&played=1&p=1&tzLoc=Asia%2FCalcutta"


    };

    private static final String[] REMOVED_DEFAULT_WEB_STREAM_URLS = {
            "https://onlineradiobox.com/search?cs=uk.capitalfmuk&played=1&q=capital&radioid=1018&tzLoc=Asia%2FCalcutta",
            "https://onlineradiobox.com/in/?cs=in.ndtv&played=1",
            "https://onlineradiobox.com/in/genre/news/?cs=in.ndtvindia&played=1",
            "https://onlineradiobox.com/in/?cs=in.aajtak&played=1&p=3&tzLoc=Asia%2FCalcutta"
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
        syncDefaultStreamsIfNeeded();
    }

    // ── URL list ──────────────────────────────────────────────────────────────

    private List<String> getLegacyDirectUrls() {
        Set<String> set = prefs.getStringSet(KEY_URLS, new LinkedHashSet<>());
        return new ArrayList<>(set);
    }

    public List<String> getWebUrls() {
        Set<String> set = prefs.getStringSet(KEY_WEB_URLS, new LinkedHashSet<>());
        return new ArrayList<>(set);
    }

    public List<String> getAllPlaybackUrls() {
        return getWebUrls();
    }

    public void addUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();
        List<String> current = getWebUrls();
        if (!containsEquivalentUrl(current, url)) {
            current.add(url);
            saveWebList(current);
        }
    }

    public int addUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return 0;

        List<String> current = getWebUrls();
        int added = 0;
        for (String url : urls) {
            if (url == null) continue;
            url = url.trim();
            if (!url.isEmpty() && !containsEquivalentUrl(current, url)) {
                current.add(url);
                added++;
            }
        }

        if (added > 0) {
            saveWebList(current);
        }
        return added;
    }

    public void removeUrl(int index) {
        List<String> current = getWebUrls();
        if (index >= 0 && index < current.size()) {
            current.remove(index);
            saveWebList(current);
            // Adjust active index if needed
            int active = getActiveIndex();
            if (active >= current.size()) {
                setActiveIndex(Math.max(0, current.size() - 1));
            }
        }
    }

    public void updateUrl(int index, String newUrl) {
        if (newUrl == null || newUrl.trim().isEmpty()) return;
        List<String> current = getWebUrls();
        if (index >= 0 && index < current.size()) {
            current.set(index, newUrl.trim());
            saveWebList(current);
        }
    }

    private void saveWebList(List<String> list) {
        LinkedHashSet<String> set = new LinkedHashSet<>(dedupeUrls(list));
        prefs.edit().putStringSet(KEY_WEB_URLS, set).apply();
    }

    // ── Active URL ────────────────────────────────────────────────────────────

    /** Returns the URL that should be played next (shuffled or sequential). */
    public String getNextUrl() {
        List<String> urls = getAutoPlaybackUrls();
        if (urls.isEmpty()) return null;

        int idx;
        if (isShuffleEnabled() && urls.size() > 1) {
            // Pick a different index than the current one
            int current = getActiveIndex();
            do { idx = random.nextInt(urls.size()); } while (idx == current && urls.size() > 1);
        } else {
            idx = (getActiveIndex() + 1) % urls.size();
        }
        setActiveIndex(idx);
        return urls.get(idx);
    }

    /** Returns the currently active URL without advancing. */
    public String getCurrentUrl() {
        List<String> urls = getAutoPlaybackUrls();
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

    // ── Shuffle ───────────────────────────────────────────────────────────────

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

    public static String getRadioNameForUrl(String url) {
        String onlineRadioBoxName = getKnownOnlineRadioBoxName(url);
        if (!onlineRadioBoxName.isEmpty()) return onlineRadioBoxName;

        String onlineRadioBoxCode = getOnlineRadioBoxStationCode(url);
        if (!onlineRadioBoxCode.isEmpty()) {
            int dot = onlineRadioBoxCode.lastIndexOf('.');
            String stationCode = dot >= 0 && dot < onlineRadioBoxCode.length() - 1
                    ? onlineRadioBoxCode.substring(dot + 1) : onlineRadioBoxCode;
            return titleCase(stationCode.replace("-", " ").replace("_", " "));
        }

        String slugName = getSlugStationName(url);
        if (!slugName.isEmpty()) return slugName;

        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) return "Radio station";
            return titleCase(host.replace("www.", "").replace(".", " "));
        } catch (Exception ignored) {
            return "Radio station";
        }
    }

    public boolean isWebStationsDefault() {
        return prefs.getBoolean(KEY_WEB_STATIONS_DEFAULT, false);
    }

    public void setWebStationsDefault(boolean enabled) {
        prefs.edit().putBoolean(KEY_WEB_STATIONS_DEFAULT, enabled).apply();
    }

    public List<String> getAutoPlaybackUrls() {
        return getWebUrls();
    }

    private void syncDefaultStreamsIfNeeded() {
        int version = prefs.getInt(KEY_DEFAULTS_VERSION, 0);
        if (prefs.getBoolean(KEY_DEFAULTS_ADDED, false) && version >= DEFAULT_STREAMS_VERSION) return;

        List<String> current = getLegacyDirectUrls();
        boolean changed = !current.isEmpty();
        current.clear();

        List<String> webCurrent = getWebUrls();
        boolean webChanged = false;
        int originalWebSize = webCurrent.size();
        webCurrent = dedupeUrls(webCurrent);
        webChanged = webCurrent.size() != originalWebSize;
        for (String removedUrl : REMOVED_DEFAULT_WEB_STREAM_URLS) {
            if (removeExactUrl(webCurrent, removedUrl)) {
                webChanged = true;
            }
        }
        for (String url : DEFAULT_WEB_STREAM_URLS) {
            if (!containsEquivalentUrl(webCurrent, url)) {
                webCurrent.add(url);
                webChanged = true;
            }
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(KEY_DEFAULTS_ADDED, true)
                .putInt(KEY_DEFAULTS_VERSION, DEFAULT_STREAMS_VERSION);
        if (changed) {
            LinkedHashSet<String> set = new LinkedHashSet<>(current);
            editor.putStringSet(KEY_URLS, set);
        }
        if (webChanged) {
            LinkedHashSet<String> set = new LinkedHashSet<>(webCurrent);
            editor.putStringSet(KEY_WEB_URLS, set);
        }
        editor.apply();
    }

    private List<String> dedupeUrls(List<String> urls) {
        List<String> deduped = new ArrayList<>();
        if (urls == null || urls.isEmpty()) return deduped;

        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        for (String url : urls) {
            if (url == null) continue;
            String cleaned = url.trim();
            if (cleaned.isEmpty()) continue;
            String key = duplicateKey(cleaned);
            if (seenKeys.add(key)) {
                deduped.add(cleaned);
            }
        }
        return deduped;
    }

    private boolean containsEquivalentUrl(List<String> urls, String candidate) {
        if (candidate == null || urls == null) return false;
        String candidateKey = duplicateKey(candidate.trim());
        for (String url : urls) {
            if (url != null && duplicateKey(url.trim()).equals(candidateKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean removeExactUrl(List<String> urls, String candidate) {
        if (candidate == null || urls == null) return false;
        String candidateKey = normalizeExactUrl(candidate);
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (url != null && normalizeExactUrl(url).equals(candidateKey)) {
                urls.remove(i);
                return true;
            }
        }
        return false;
    }

    private String normalizeExactUrl(String url) {
        if (url == null) return "";
        String normalized = url.trim().toLowerCase();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String duplicateKey(String url) {
        if (url == null) return "";
        String cleaned = url.trim();
        if (cleaned.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(cleaned);
            String host = uri.getHost();
            if (host != null && host.toLowerCase().contains("onlineradiobox.com")) {
                String stationCode = uri.getQueryParameter("cs");
                if (stationCode != null && !stationCode.trim().isEmpty()) {
                    return "onlineradiobox:" + stationCode.trim().toLowerCase();
                }
            }
        } catch (Exception ignored) {
        }

        String normalized = cleaned.toLowerCase();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String getKnownOnlineRadioBoxName(String url) {
        String code = getOnlineRadioBoxStationCode(url);
        if (code.isEmpty()) return "";
        switch (code) {
            case "ca.cbcrtoronto":
                return "CBC Radio One";
            case "in.aajtak":
                return "Aaj Tak Radio";
            case "in.air":
                return "AIR Patiala 100.2 FM";
            case "in.easy60s":
                return "Easy 60s";
            case "in.karanaujla":
                return "Karan Aujla Radio";
            case "in.ndtv":
                return "NDTV 24x7 Radio";
            case "za.hindvaniradio":
                return "Hindvani Radio";
            case "in.ndtvindia":
                return "NDTV India";
            case "in.easy10s":
                return "Easy 10s";
            case "uk.capitalfmuk":
                return "Capital FM";
            case "uk.lbc973fm":
                return "LBC";
            case "uk.smoothradio1022":
                return "Smooth Radio";
            case "us.npr":
                return "NPR Radio";
            case "us.977comedy":
                return ".977 Comedy";
            case "us.977todayshits":
                return ".977 Today's Hits";
            case "us.wbbr":
                return "Bloomberg Radio";
            default:
                return "";
        }
    }

    private static String getOnlineRadioBoxStationCode(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !host.toLowerCase().contains("onlineradiobox.com")) {
                return "";
            }
            String code = uri.getQueryParameter("cs");
            return code != null ? code.trim().toLowerCase() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String getSlugStationName(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !host.toLowerCase().contains("onlineradiofm.in")) {
                return "";
            }
            List<String> segments = uri.getPathSegments();
            if (segments.isEmpty()) return "";
            String slug = segments.get(segments.size() - 1);
            if (slug == null || slug.trim().isEmpty()) return "";
            return titleCase(slug.replace("-", " "));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String titleCase(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String[] parts = value.trim().replaceAll("\\s+", " ").split(" ");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            if (part.length() <= 3 && part.equals(part.toLowerCase())) {
                result.append(part.toUpperCase());
            } else {
                result.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) result.append(part.substring(1));
            }
        }
        return result.toString();
    }
}
