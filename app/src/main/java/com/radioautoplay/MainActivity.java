package com.radioautoplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private StreamUrlManager urlManager;
    private UrlAdapter        adapter;
    private List<String>      urlList;

    // UI
    private TextView   tvStatus;
    private TextView   tvCurrentUrl;
    private Button     btnPlayStop;
    private Switch     switchShuffle;
    private EditText   etNewUrl;
    private RecyclerView rvUrls;

    private boolean serviceRunning = false;

    // ── BroadcastReceiver for service state ───────────────────────────────────

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            boolean playing = intent.getBooleanExtra(RadioService.EXTRA_PLAYING, false);
            String  url     = intent.getStringExtra(RadioService.EXTRA_URL_NOW);
            String  error   = intent.getStringExtra(RadioService.EXTRA_ERROR);

            serviceRunning = playing;
            updatePlaybackUI(playing, url, error);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlManager = new StreamUrlManager(this);

        bindViews();
        setupRecyclerView();
        setupControls();
        refreshShuffleSwitch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register for service state broadcasts
        IntentFilter filter = new IntentFilter(RadioService.BROADCAST_STATE);
        registerReceiver(serviceReceiver, filter);
        refreshList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(serviceReceiver);
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews() {
        tvStatus      = findViewById(R.id.tv_status);
        tvCurrentUrl  = findViewById(R.id.tv_current_url);
        btnPlayStop   = findViewById(R.id.btn_play_stop);
        switchShuffle = findViewById(R.id.switch_shuffle);
        etNewUrl      = findViewById(R.id.et_new_url);
        rvUrls        = findViewById(R.id.rv_urls);
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        urlList = urlManager.getUrls();
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
        urlList.addAll(urlManager.getUrls());
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

        // Play / Stop button
        btnPlayStop.setOnClickListener(v -> {
            if (serviceRunning) {
                stopService();
            } else {
                if (urlManager.isEmpty()) {
                    Toast.makeText(this, "Add at least one stream URL first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                playIndex(urlManager.getActiveIndex());
            }
        });

        // Shuffle switch
        switchShuffle.setOnCheckedChangeListener((btn, checked) -> {
            urlManager.setShuffleEnabled(checked);
            Toast.makeText(this,
                    checked ? "Shuffle ON – random stream on each plug-in"
                            : "Shuffle OFF – streams play in order",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshShuffleSwitch() {
        switchShuffle.setChecked(urlManager.isShuffleEnabled());
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void addUrl() {
        String url = etNewUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            etNewUrl.setError("Please enter a URL");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            etNewUrl.setError("URL must start with http:// or https://");
            return;
        }
        urlManager.addUrl(url);
        etNewUrl.setText("");
        etNewUrl.setError(null);
        refreshList();
        Toast.makeText(this, "Stream added ✓", Toast.LENGTH_SHORT).show();
    }

    private void playIndex(int index) {
        List<String> urls = urlManager.getUrls();
        if (index < 0 || index >= urls.size()) return;
        urlManager.setActiveIndex(index);
        adapter.setActiveIndex(index);
        startStream(urls.get(index));
    }

    private void confirmDelete(int position) {
        new AlertDialog.Builder(this)
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
        updatePlaybackUI(true, url, null);
    }

    private void stopService() {
        Intent i = new Intent(this, RadioService.class);
        i.setAction(RadioService.ACTION_STOP);
        startService(i);
        serviceRunning = false;
        updatePlaybackUI(false, null, null);
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updatePlaybackUI(boolean playing, String url, String error) {
        if (playing) {
            tvStatus.setText("● LIVE");
            tvStatus.setTextColor(getResources().getColor(R.color.playing_green));
            btnPlayStop.setText("■  Stop");
            tvCurrentUrl.setText(url != null ? url : "");
            tvCurrentUrl.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setText(error != null ? "⚠ Error" : "● Idle");
            tvStatus.setTextColor(getResources().getColor(
                    error != null ? R.color.error_red : R.color.idle_grey));
            btnPlayStop.setText("▶  Play");
            tvCurrentUrl.setVisibility(error != null ? View.VISIBLE : View.GONE);
            if (error != null) tvCurrentUrl.setText("Error: " + error);
        }
        adapter.setActiveIndex(urlManager.getActiveIndex());
    }
}
