package com.radioautoplay;

import android.content.Context;
import android.content.SharedPreferences;
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
    private static final String KEY_SHUFFLE      = "shuffle_mode";
    private static final String KEY_DEFAULTS_ADDED = "default_streams_added";

    private static final String[] DEFAULT_STREAM_URLS = {
            "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
            "https://eu8.fastcast4u.com/proxy/clyedupq?mp=%2F1?aw_0_req_lsid=2c0fae177108c9a42a7cf24878625444",
            "https://stream.zeno.fm/dbstwo3dvhhtv",
            "https://stream.zeno.fm/6quh1pfnt1duv",
            "https://onlineradiofm.in/f7457fda-0a31-474f-b31a-3ba845be729b",
            "https://s8.voscast.com:7021/stream",
            "https://drive.uber.radio/uber-app/bollywooddance/icecast.audio",
            "https://srv01.onlineradio.voaplus.com/kissfm",
            "https://server.mixify.in:8010/radio.mp3",
            "https://live.cmr24.net/CMR/Desi_Music-MQ/icecast.audio",
            "https://ice42.securenetsystems.net/KQBK?playSessionID=893715E2-D578-F731-09E75DFFEE53C84F",
            "https://stream.zeno.fm/a2gyqzwpwfeuv",
            "http://stream.zenolive.com/rqqps6cbe3quv.html",
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
        addDefaultStreamsIfNeeded();
    }

    // ── URL list ──────────────────────────────────────────────────────────────

    public List<String> getUrls() {
        Set<String> set = prefs.getStringSet(KEY_URLS, new LinkedHashSet<>());
        // LinkedHashSet preserves insertion order
        return new ArrayList<>(set);
    }

    public void addUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();
        List<String> current = getUrls();
        if (!current.contains(url)) {
            current.add(url);
            saveList(current);
        }
    }

    public int addUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return 0;

        List<String> current = getUrls();
        int added = 0;
        for (String url : urls) {
            if (url == null) continue;
            url = url.trim();
            if (!url.isEmpty() && !current.contains(url)) {
                current.add(url);
                added++;
            }
        }

        if (added > 0) {
            saveList(current);
        }
        return added;
    }

    public void removeUrl(int index) {
        List<String> current = getUrls();
        if (index >= 0 && index < current.size()) {
            current.remove(index);
            saveList(current);
            // Adjust active index if needed
            int active = getActiveIndex();
            if (active >= current.size()) {
                setActiveIndex(Math.max(0, current.size() - 1));
            }
        }
    }

    public void updateUrl(int index, String newUrl) {
        if (newUrl == null || newUrl.trim().isEmpty()) return;
        List<String> current = getUrls();
        if (index >= 0 && index < current.size()) {
            current.set(index, newUrl.trim());
            saveList(current);
        }
    }

    private void saveList(List<String> list) {
        // Preserve order via LinkedHashSet
        LinkedHashSet<String> set = new LinkedHashSet<>(list);
        prefs.edit().putStringSet(KEY_URLS, set).apply();
    }

    // ── Active URL ────────────────────────────────────────────────────────────

    /** Returns the URL that should be played next (shuffled or sequential). */
    public String getNextUrl() {
        List<String> urls = getUrls();
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
        List<String> urls = getUrls();
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

    public boolean isEmpty() {
        return getUrls().isEmpty();
    }

    private void addDefaultStreamsIfNeeded() {
        if (prefs.getBoolean(KEY_DEFAULTS_ADDED, false)) return;

        List<String> current = getUrls();
        boolean changed = false;
        for (String url : DEFAULT_STREAM_URLS) {
            if (!current.contains(url)) {
                current.add(url);
                changed = true;
            }
        }

        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_DEFAULTS_ADDED, true);
        if (changed) {
            LinkedHashSet<String> set = new LinkedHashSet<>(current);
            editor.putStringSet(KEY_URLS, set);
        }
        editor.apply();
    }
}
