package com.radioautoplay;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private StreamUrlManager urlManager;
    private IntroSoundManager introSoundManager;
    private UpdateManager updateManager;
    private DiagnosticsLogger diagnosticsLogger;
    private UrlAdapter        adapter;
    private List<String>      urlList;

    // UI
    private TextView   tvStatus;
    private TextView   tvCurrentUrl;
    private Button     btnPlayStop;
    private AudioVisualizerView audioVisualizer;
    private SwitchMaterial switchAppEnabled;
    private SwitchMaterial switchStartDelay;
    private SwitchMaterial switchVisualizer;
    private SwitchMaterial switchShuffle;
    private SwitchMaterial switchQuietHours;
    private EditText   etNewUrl;
    private RecyclerView rvUrls;

    private boolean serviceRunning = false;
    private final ActivityResultLauncher<String[]> csvPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importCsv(uri);
            });
    private final ActivityResultLauncher<String[]> introSoundPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) addIntroSound(uri);
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this,
                            "Playback works, but Android may hide the foreground notification.",
                            Toast.LENGTH_LONG).show();
                }
            });

    // ── BroadcastReceiver for service state ───────────────────────────────────

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            boolean playing = intent.getBooleanExtra(RadioService.EXTRA_PLAYING, false);
            String  url     = intent.getStringExtra(RadioService.EXTRA_URL_NOW);
            String  error   = intent.getStringExtra(RadioService.EXTRA_ERROR);
            String  status  = intent.getStringExtra(RadioService.EXTRA_STATUS);

            serviceRunning = playing || status != null;
            if (!urlManager.isAppEnabled() && !playing) {
                showAppDisabledState();
                return;
            }
            updatePlaybackUI(playing, url, error, status);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlManager = new StreamUrlManager(this);
        introSoundManager = new IntroSoundManager(this);
        updateManager = new UpdateManager(this);
        diagnosticsLogger = new DiagnosticsLogger(this);

        bindViews();
        setupRecyclerView();
        setupControls();
        refreshAppEnabledSwitch();
        refreshStartDelaySwitch();
        refreshVisualizerSwitch();
        refreshShuffleSwitch();
        refreshQuietHoursSwitch();
        requestNotificationPermissionIfNeeded();
        ChargerMonitorService.start(this);
        updateManager.register();
        updateManager.checkForUpdates(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register for service state broadcasts
        IntentFilter filter = new IntentFilter(RadioService.BROADCAST_STATE);
        ContextCompat.registerReceiver(this, serviceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        refreshAppEnabledSwitch();
        refreshStartDelaySwitch();
        refreshVisualizerSwitch();
        refreshList();
        startIfAlreadyCharging();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(serviceReceiver);
    }

    @Override
    protected void onDestroy() {
        updateManager.unregister();
        super.onDestroy();
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews() {
        tvStatus      = findViewById(R.id.tv_status);
        tvCurrentUrl  = findViewById(R.id.tv_current_url);
        btnPlayStop   = findViewById(R.id.btn_play_stop);
        audioVisualizer = findViewById(R.id.audio_visualizer);
        switchAppEnabled = findViewById(R.id.switch_app_enabled);
        switchStartDelay = findViewById(R.id.switch_start_delay);
        switchVisualizer = findViewById(R.id.switch_visualizer);
        switchShuffle = findViewById(R.id.switch_shuffle);
        switchQuietHours = findViewById(R.id.switch_quiet_hours);
        etNewUrl      = findViewById(R.id.et_new_url);
        rvUrls        = findViewById(R.id.rv_urls);
        TextView version = findViewById(R.id.tv_app_version);
        version.setText(BuildConfig.VERSION_NAME);
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        urlList = urlManager.getAllPlaybackUrls();
        adapter = new UrlAdapter(urlList, new UrlAdapter.OnItemActionListener() {
            @Override
            public void onDelete(int position) {
                confirmDelete(position);
            }
            @Override
            public void onPlay(int position) {
                playIndex(position);
            }
        });
        adapter.setActiveIndex(urlManager.getActiveIndex());
        rvUrls.setLayoutManager(new LinearLayoutManager(this));
        rvUrls.setAdapter(adapter);
    }

    private void refreshList() {
        urlList.clear();
        urlList.addAll(urlManager.getAllPlaybackUrls());
        adapter.setActiveIndex(urlManager.getActiveIndex());
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        findViewById(R.id.tv_empty).setVisibility(urlList.isEmpty() ? View.VISIBLE : View.GONE);
        rvUrls.setVisibility(urlList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private void setupControls() {

        // Add URL button
        findViewById(R.id.btn_add).setOnClickListener(v -> addUrl());
        findViewById(R.id.btn_import_csv).setOnClickListener(v -> openCsvPicker());
        findViewById(R.id.btn_add_intro).setOnClickListener(v -> openIntroSoundPicker());
        findViewById(R.id.btn_clear_intro).setOnClickListener(v -> clearIntroSounds());
        findViewById(R.id.btn_check_updates).setOnClickListener(v -> updateManager.checkForUpdates(true));
        findViewById(R.id.btn_show_log_path).setOnClickListener(v -> {
            String path = diagnosticsLogger.getLogPath();
            Toast.makeText(this, "Diagnostics log: " + path, Toast.LENGTH_LONG).show();
        });

        // Play / Stop button
        btnPlayStop.setOnClickListener(v -> {
            if (serviceRunning) {
                stopService();
            } else {
                if (!urlManager.isAppEnabled()) {
                    Toast.makeText(this, "Turn Radio AutoPlay ON first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (urlManager.isEmpty()) {
                    Toast.makeText(this, "Add at least one stream URL first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                playIndex(urlManager.getActiveIndex());
            }
        });

        switchAppEnabled.setOnCheckedChangeListener((btn, checked) -> {
            urlManager.setAppEnabled(checked);
            if (!checked) {
                stopService();
                showAppDisabledState();
            } else {
                ChargerMonitorService.start(this);
                updatePlaybackUI(false, null, null, null);
                startIfAlreadyCharging();
            }
            Toast.makeText(this,
                    checked ? "Radio AutoPlay ON" : "Radio AutoPlay OFF",
                    Toast.LENGTH_SHORT).show();
        });

        switchStartDelay.setOnCheckedChangeListener((btn, checked) -> {
            urlManager.setStartDelayEnabled(checked);
            Toast.makeText(this,
                    checked ? "40-second start delay ON" : "Start delay OFF",
                    Toast.LENGTH_SHORT).show();
        });

        switchVisualizer.setOnCheckedChangeListener((btn, checked) -> {
            urlManager.setVisualizerEnabled(checked);
            setVisualizerActive(checked && serviceRunning);
            Toast.makeText(this,
                    checked ? "Visualizer ON" : "Visualizer OFF",
                    Toast.LENGTH_SHORT).show();
        });

        // Shuffle switch
        switchShuffle.setOnCheckedChangeListener((btn, checked) -> {
            urlManager.setShuffleEnabled(checked);
            Toast.makeText(this,
                    checked ? "Shuffle ON – random stream on each plug-in"
                            : "Shuffle OFF – streams play in order",
                    Toast.LENGTH_SHORT).show();
        });

        switchQuietHours.setOnCheckedChangeListener((btn, checked) -> {
            urlManager.setQuietHoursEnabled(checked);
            if (checked && serviceRunning && isQuietHoursNow()) {
                stopService();
                Toast.makeText(this, "Quiet hours ON. Playback stopped until 6:00 AM.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this,
                    checked ? "Quiet hours ON – silent from 12:00 AM to 6:00 AM"
                            : "Quiet hours OFF – charger playback can start anytime",
                    Toast.LENGTH_SHORT).show();
        });

    }

    private void refreshAppEnabledSwitch() {
        switchAppEnabled.setChecked(urlManager.isAppEnabled());
        if (!urlManager.isAppEnabled()) {
            showAppDisabledState();
        }
    }

    private void showAppDisabledState() {
        tvStatus.setText("● Off");
        tvStatus.setTextColor(getResources().getColor(R.color.idle_grey));
        btnPlayStop.setText("▶  Play");
        setPlayStopButtonTint(R.color.idle_button_bg);
        setVisualizerActive(false);
        tvCurrentUrl.setText("Radio AutoPlay is turned off");
        tvCurrentUrl.setVisibility(View.VISIBLE);
    }

    private void refreshStartDelaySwitch() {
        switchStartDelay.setChecked(urlManager.isStartDelayEnabled());
    }

    private void refreshVisualizerSwitch() {
        boolean enabled = urlManager.isVisualizerEnabled();
        switchVisualizer.setChecked(enabled);
        audioVisualizer.setVisibility(enabled ? View.VISIBLE : View.GONE);
        setVisualizerActive(enabled && serviceRunning);
    }

    private void refreshShuffleSwitch() {
        switchShuffle.setChecked(urlManager.isShuffleEnabled());
    }

    private void refreshQuietHoursSwitch() {
        switchQuietHours.setChecked(urlManager.isQuietHoursEnabled());
    }

    private boolean isQuietHoursNow() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 0 && hour < 6;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void addUrl() {
        String url = etNewUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            etNewUrl.setError("Please enter a URL");
            return;
        }
        if (!StreamUrlManager.isPlayableStreamUrl(url)) {
            etNewUrl.setError("Enter a radio webpage or stream URL");
            return;
        }
        urlManager.addUrl(url);
        etNewUrl.setText("");
        etNewUrl.setError(null);
        refreshList();
        Toast.makeText(this, "Web station link added ✓", Toast.LENGTH_SHORT).show();
    }

    private void openCsvPicker() {
        csvPickerLauncher.launch(new String[] {
                "text/*",
                "text/csv",
                "application/csv",
                "application/vnd.ms-excel",
                "application/octet-stream"
        });
    }

    private void importCsv(Uri uri) {
        try {
            List<String> links = readStreamUrlsFromCsv(uri);
            int added = urlManager.addUrls(links);
            refreshList();

            if (added > 0) {
                Toast.makeText(this, "Imported " + added + " radio link(s).", Toast.LENGTH_LONG).show();
            } else if (links.isEmpty()) {
                Toast.makeText(this, "No radio webpage links found in that CSV.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "All links in that CSV were already saved.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not import CSV: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openIntroSoundPicker() {
        introSoundPickerLauncher.launch(new String[] {
                "audio/*",
                "application/octet-stream"
        });
    }

    private void addIntroSound(Uri uri) {
        try {
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            // Some providers do not offer persistable permissions; keep the URI if it still opens now.
        }

        boolean added = introSoundManager.addIntroUri(uri);
        Toast.makeText(this,
                added ? "Intro sound added. Saved intros: " + introSoundManager.getCount()
                        : "That intro sound is already saved.",
                Toast.LENGTH_LONG).show();
    }

    private void clearIntroSounds() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Clear custom intro sounds?")
                .setMessage("The bundled intro will still play when no custom intro is saved.")
                .setPositiveButton("Clear", (d, w) -> {
                    introSoundManager.clearIntroUris();
                    Toast.makeText(this, "Custom intro sounds cleared.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<String> readStreamUrlsFromCsv(Uri uri) throws Exception {
        List<String> links = new ArrayList<>();
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) {
            throw new IllegalStateException("Selected file could not be opened.");
        }
        try (InputStream inputStream = stream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String cell : parseCsvLine(line)) {
                    String value = cell.trim();
                    if (isValidStreamUrl(value) && !links.contains(value)) {
                        links.add(value);
                    }
                }
            }
        }
        return links;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private boolean isValidStreamUrl(String value) {
        return StreamUrlManager.isPlayableStreamUrl(value);
    }

    private void playIndex(int index) {
        if (!urlManager.isAppEnabled()) {
            Toast.makeText(this, "Radio AutoPlay is turned off.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> urls = urlManager.getAllPlaybackUrls();
        if (index < 0 || index >= urls.size()) return;
        urlManager.setActiveIndex(index);
        adapter.setActiveIndex(index);
        startStream(urls.get(index));
    }

    private void confirmDelete(int position) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove stream?")
                .setMessage(urlList.get(position))
                .setPositiveButton("Remove", (d, w) -> {
                    urlManager.removeUrl(position);
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Service control ───────────────────────────────────────────────────────

    private void startStream(String url) {
        Intent i = new Intent(this, RadioService.class);
        i.setAction(RadioService.ACTION_PLAY);
        i.putExtra(RadioService.EXTRA_URL, url);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        serviceRunning = true;
        updatePlaybackUI(false, url, null, "Starting");
    }

    private void stopService() {
        Intent i = new Intent(this, RadioService.class);
        i.setAction(RadioService.ACTION_STOP);
        startService(i);
        serviceRunning = false;
        updatePlaybackUI(false, null, null, null);
    }

    private void startIfAlreadyCharging() {
        if (serviceRunning || !urlManager.isAppEnabled() || urlManager.isEmpty()) return;
        if (ChargerReceiver.isPowerConnected(this)) {
            ChargerReceiver.handlePowerAction(this, Intent.ACTION_POWER_CONNECTED);
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updatePlaybackUI(boolean playing, String url, String error) {
        updatePlaybackUI(playing, url, error, null);
    }

    private void updatePlaybackUI(boolean playing, String url, String error, String status) {
        if (playing) {
            tvStatus.setText("● LIVE");
            tvStatus.setTextColor(getResources().getColor(R.color.playing_green));
            btnPlayStop.setText("■  Stop");
            setPlayStopButtonTint(R.color.stop_button_bg);
            setVisualizerActive(true);
            tvCurrentUrl.setText(url != null ? url : "");
            tvCurrentUrl.setVisibility(View.VISIBLE);
        } else if (status != null) {
            tvStatus.setText("● Starting");
            tvStatus.setTextColor(getResources().getColor(R.color.accent));
            btnPlayStop.setText("■  Stop");
            setPlayStopButtonTint(R.color.stop_button_bg);
            setVisualizerActive(true);
            tvCurrentUrl.setText(url != null && !url.isEmpty() ? status + "\n" + url : status);
            tvCurrentUrl.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setText(error != null ? "⚠ Error" : "● Idle");
            tvStatus.setTextColor(getResources().getColor(
                    error != null ? R.color.error_red : R.color.idle_grey));
            btnPlayStop.setText("▶  Play");
            setPlayStopButtonTint(R.color.play_button_bg);
            setVisualizerActive(false);
            tvCurrentUrl.setVisibility(error != null ? View.VISIBLE : View.GONE);
            if (error != null) tvCurrentUrl.setText("Error: " + error);
        }
        adapter.setActiveIndex(urlManager.getActiveIndex());
    }

    private void setVisualizerActive(boolean active) {
        boolean enabled = urlManager != null && urlManager.isVisualizerEnabled();
        audioVisualizer.setVisibility(enabled ? View.VISIBLE : View.GONE);
        audioVisualizer.setActive(enabled && active);
    }

    private void setPlayStopButtonTint(int colorRes) {
        ViewCompat.setBackgroundTintList(btnPlayStop,
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
    }
}
