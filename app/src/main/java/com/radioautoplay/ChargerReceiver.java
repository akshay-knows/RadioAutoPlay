package com.radioautoplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Listens for power-connected / power-disconnected broadcasts.
 * When charger is plugged in  → picks the next URL (random or sequential)
 *                               and starts RadioService.
 * When charger is unplugged  → stops RadioService.
 */
public class ChargerReceiver extends BroadcastReceiver {

    private static final String TAG = "ChargerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "Received: " + action);

        StreamUrlManager mgr = new StreamUrlManager(context);

        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            if (mgr.isEmpty()) {
                Log.w(TAG, "No stream URLs saved – skipping autoplay.");
                return;
            }
            // Advance to next URL (shuffle or sequential based on user setting)
            String url = mgr.getNextUrl();
            Log.d(TAG, "Charger connected – playing: " + url);
            startService(context, url);

        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            Log.d(TAG, "Charger disconnected – stopping playback.");
            stopService(context);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void startService(Context context, String url) {
        Intent service = new Intent(context, RadioService.class);
        service.setAction(RadioService.ACTION_PLAY);
        service.putExtra(RadioService.EXTRA_URL, url);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }

    private void stopService(Context context) {
        Intent service = new Intent(context, RadioService.class);
        service.setAction(RadioService.ACTION_STOP);
        context.startService(service);
    }
}
