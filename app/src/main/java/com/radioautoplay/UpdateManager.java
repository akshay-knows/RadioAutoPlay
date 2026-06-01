package com.radioautoplay;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class UpdateManager {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/akshay-knows/RadioAutoPlay/releases/latest";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String UPDATE_CHANNEL_ID = "app_update_channel";
    private static final int UPDATE_NOTIFICATION_ID = 1042;

    private final Activity activity;
    private long activeDownloadId = -1L;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()) == false) {
                return;
            }
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id == activeDownloadId) {
                installDownloadedApk(id);
            }
        }
    };

    public UpdateManager(Activity activity) {
        this.activity = activity;
        ensureUpdateChannel();
    }

    public void register() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(downloadReceiver, filter);
        }
    }

    public void unregister() {
        try {
            activity.unregisterReceiver(downloadReceiver);
        } catch (Exception ignored) {
        }
    }

    public void checkForUpdates(boolean manual) {
        new Thread(() -> {
            try {
                UpdateInfo update = fetchLatestUpdate();
                activity.runOnUiThread(() -> handleUpdateResult(update, manual));
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    if (manual) {
                        Toast.makeText(activity, "Could not check for updates: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "UpdateChecker").start();
    }

    private void handleUpdateResult(UpdateInfo update, boolean manual) {
        if (update == null || update.apkUrl == null || update.apkUrl.isEmpty()
                || compareVersions(update.versionName, BuildConfig.VERSION_NAME) <= 0) {
            if (manual) {
                Toast.makeText(activity, "You already have the latest version.",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        showUpdateNotification(update);

        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("Radio AutoPlay " + update.versionName
                        + " is available.\n\nCurrent version: " + BuildConfig.VERSION_NAME
                        + "\n\nDownload and install it now?")
                .setPositiveButton("Update", (dialog, which) -> startUpdate(update))
                .setNegativeButton("Later", null)
                .show();
    }

    private void ensureUpdateChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Alerts when a newer Radio AutoPlay version is available");
        nm.createNotificationChannel(channel);
    }

    private void showUpdateNotification(UpdateInfo update) {
        NotificationManager nm =
                (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent openApp = new Intent(activity, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(activity, 42, openApp, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update available: v" + update.versionName)
                .setContentText("Tap to open Radio AutoPlay and install the update.")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("A newer version (v" + update.versionName
                                + ") is available. Open the app and tap Update to install."))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi);

        nm.notify(UPDATE_NOTIFICATION_ID, builder.build());
    }

    private UpdateInfo fetchLatestUpdate() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(12_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "RadioAutoPlay/" + BuildConfig.VERSION_NAME);

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("GitHub returned " + code);
        }

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        } finally {
            connection.disconnect();
        }

        JSONObject json = new JSONObject(body.toString());
        String tag = json.optString("tag_name", "");
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        JSONArray assets = json.optJSONArray("assets");
        if (assets == null) return null;

        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "").toLowerCase(Locale.US);
            if (name.endsWith(".apk")) {
                return new UpdateInfo(version, asset.optString("browser_download_url", ""));
            }
        }
        return null;
    }

    private void startUpdate(UpdateInfo update) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow app updates")
                    .setMessage("Android needs permission to install APK updates from Radio AutoPlay.")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        DownloadManager downloadManager =
                (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            Toast.makeText(activity, "Download manager is not available.", Toast.LENGTH_LONG).show();
            return;
        }

        String fileName = "RadioAutoPlay-" + update.versionName + ".apk";
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle("Radio AutoPlay " + update.versionName)
                .setDescription("Downloading app update")
                .setMimeType(APK_MIME_TYPE)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);

        activeDownloadId = downloadManager.enqueue(request);
        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_SHORT).show();
    }

    private void installDownloadedApk(long downloadId) {
        DownloadManager downloadManager =
                (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) return;

        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (statusColumn >= 0
                    && cursor.getInt(statusColumn) != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(activity, "Update download failed.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        Uri apkUri = downloadManager.getUriForDownloadedFile(downloadId);
        if (apkUri == null) {
            Toast.makeText(activity, "Downloaded APK was not found.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (install.resolveActivity(activity.getPackageManager()) == null) {
            Toast.makeText(activity, "No APK installer was found on this device.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        activity.startActivity(install);
    }

    private int compareVersions(String left, String right) {
        String[] a = left == null ? new String[0] : left.split("\\.");
        String[] b = right == null ? new String[0] : right.split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int ai = i < a.length ? parseVersionPart(a[i]) : 0;
            int bi = i < b.length ? parseVersionPart(b[i]) : 0;
            if (ai != bi) return ai - bi;
        }
        return 0;
    }

    private int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static class UpdateInfo {
        final String versionName;
        final String apkUrl;

        UpdateInfo(String versionName, String apkUrl) {
            this.versionName = versionName;
            this.apkUrl = apkUrl;
        }
    }
}
