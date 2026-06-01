package com.radioautoplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Stores optional user-picked intro sounds. The bundled intro remains the fallback.
 */
public class IntroSoundManager {

    private static final String PREF_NAME = "intro_sound_prefs";
    private static final String KEY_INTRO_URIS = "intro_uris";

    private final SharedPreferences prefs;
    private final Random random = new Random();

    public IntroSoundManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public List<String> getIntroUris() {
        Set<String> set = prefs.getStringSet(KEY_INTRO_URIS, new LinkedHashSet<>());
        return new ArrayList<>(set);
    }

    public boolean addIntroUri(Uri uri) {
        if (uri == null) return false;
        String value = uri.toString();
        List<String> current = getIntroUris();
        if (current.contains(value)) return false;
        current.add(value);
        save(current);
        return true;
    }

    public void clearIntroUris() {
        prefs.edit().remove(KEY_INTRO_URIS).apply();
    }

    public Uri getRandomIntroUri() {
        List<String> uris = getIntroUris();
        if (uris.isEmpty()) return null;
        return Uri.parse(uris.get(random.nextInt(uris.size())));
    }

    public int getCount() {
        return getIntroUris().size();
    }

    private void save(List<String> uris) {
        prefs.edit()
                .putStringSet(KEY_INTRO_URIS, new LinkedHashSet<>(uris))
                .apply();
    }
}
