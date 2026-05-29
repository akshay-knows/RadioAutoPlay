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
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Keeps charger plug/unplug detection alive while the app is backgrounded or the
 * phone is locked. Users only need to open the app once after install.
 */
public class ChargerMonitorService extends Service {

    private static final String TAG = "ChargerMonitorService";
    private static final String CHANNEL_ID = "charger_monitor_channel";
    private static final int NOTIF_ID = 2;

    public static final String ACTION_START = "com.radioautoplay.MONITOR_START";

    private BroadcastReceiver powerReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        registerPowerReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        unregisterPowerReceiver();
        super.onDestroy();
    }

    public static void start(Context context) {
        Intent service = new Intent(context, ChargerMonitorService.class);
        service.setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not start charger monitor", e);
        }
    }

    private void registerPowerReceiver() {
        if (powerReceiver != null) return;

        powerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                ChargerReceiver.handlePowerAction(context, intent.getAction());
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(powerReceiver, filter);
        }
    }

    private void unregisterPowerReceiver() {
        if (powerReceiver == null) return;
        try {
            unregisterReceiver(powerReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Could not unregister power receiver", e);
        }
        powerReceiver = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Charger Monitor",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Keeps bathroom charger autoplay ready");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Radio AutoPlay ready")
                .setContentText("Listening for charger connect and disconnect")
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
