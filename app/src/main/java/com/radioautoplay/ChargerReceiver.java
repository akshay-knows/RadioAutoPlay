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
    private static final long DUPLICATE_EVENT_WINDOW_MS = 2_000L;
    private static String lastAction;
    private static long lastActionTime;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "Received: " + action);
        handlePowerAction(context, action);
    }

    public static void handlePowerAction(Context context, String action) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ChargerMonitorService.start(context);
            return;
        }

        if (isDuplicate(action)) {
            Log.d(TAG, "Skipping duplicate power event: " + action);
            return;
        }

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

    private static synchronized boolean isDuplicate(String action) {
        long now = System.currentTimeMillis();
        if (action.equals(lastAction) && now - lastActionTime < DUPLICATE_EVENT_WINDOW_MS) {
            return true;
        }
        lastAction = action;
        lastActionTime = now;
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void startService(Context context, String url) {
        Intent service = new Intent(context, RadioService.class);
        service.setAction(RadioService.ACTION_PLAY);
        service.putExtra(RadioService.EXTRA_URL, url);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not start radio service", e);
        }
    }

    private static void stopService(Context context) {
        Intent service = new Intent(context, RadioService.class);
        service.setAction(RadioService.ACTION_STOP);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not send stop command to radio service", e);
            context.stopService(service);
        }
    }
}
