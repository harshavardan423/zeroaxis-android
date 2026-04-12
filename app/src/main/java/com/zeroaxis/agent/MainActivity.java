package com.zeroaxis.agent;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_MEDIA_PERMISSIONS = 2001;

    private String pendingScanType     = null;
    private String pendingScanSerial   = null;
    private String pendingScanFlaskUrl = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ContextCompat.startForegroundService(
                this, new Intent(this, AgentService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();

        // If returning from permission dialog with a pending scan, fire it now.
        if (pendingScanType != null) {
            String type     = pendingScanType;
            String serial   = pendingScanSerial;
            String flaskUrl = pendingScanFlaskUrl;
            pendingScanType = null;
            AVScanService.startScan(this, type, flaskUrl, serial);
            ((TextView) findViewById(R.id.tvLastScan)).setText("Scan started…");
        }
    }

    private void refresh() {
        SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
        String serial   = prefs.getString("serial", "Not enrolled");
        String flaskUrl = loadFlaskUrl();
        long   lastScan = prefs.getLong("av_last_scan", 0);
        int    threats  = prefs.getInt("av_threat_count", 0);
        String scanType = prefs.getString("av_last_scan_type", "—");

        TextView tvSerial   = findViewById(R.id.tvSerial);
        TextView tvServer   = findViewById(R.id.tvServer);
        TextView tvStatus   = findViewById(R.id.tvStatus);
        TextView tvLastScan = findViewById(R.id.tvLastScan);
        TextView tvThreats  = findViewById(R.id.tvThreats);
        Button   btnQuick   = findViewById(R.id.btnQuickScan);
        Button   btnFull    = findViewById(R.id.btnFullScan);
        Button   btnSigs    = findViewById(R.id.btnUpdateSigs);

        tvSerial.setText("Serial: " + serial);
        tvServer.setText("Server: " + flaskUrl);
        tvStatus.setText("Agent: Running ✓");

        tvLastScan.setText(lastScan == 0 ? "Last scan: Never"
                : "Last " + scanType + " scan: " + new SimpleDateFormat(
                        "dd MMM yyyy HH:mm", Locale.US).format(new Date(lastScan)));

        tvThreats.setText(threats > 0
                ? "⚠ " + threats + " threat(s) detected"
                : "✓ No threats detected");
        tvThreats.setTextColor(getColor(threats > 0
                ? android.R.color.holo_red_dark
                : android.R.color.holo_green_dark));

        btnQuick.setOnClickListener(v -> startScan("quick", serial, flaskUrl));
        btnFull.setOnClickListener(v  -> startScan("full",  serial, flaskUrl));
        btnSigs.setOnClickListener(v  -> updateSignatures());
    }

    private void startScan(String type, String serial, String flaskUrl) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            boolean hasImages = checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean hasVideo  = checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean hasAudio  = checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;

            if (!hasImages || !hasVideo || !hasAudio) {
                pendingScanType     = type;
                pendingScanSerial   = serial;
                pendingScanFlaskUrl = flaskUrl;
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_AUDIO
                }, REQ_MEDIA_PERMISSIONS);
                return;
            }
        }
        // Permissions satisfied — start scan directly.
        // Full scan degrades to quick inside AVScanService if needed.
        // We never launch any external picker activity — doing so creates
        // stale tasks in the back stack that cause the restart loop.
        pendingScanType = null;
        AVScanService.startScan(this, type, flaskUrl, serial);
        ((TextView) findViewById(R.id.tvLastScan)).setText("Scan started…");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MEDIA_PERMISSIONS && pendingScanType != null) {
            String type     = pendingScanType;
            String serial   = pendingScanSerial;
            String flaskUrl = pendingScanFlaskUrl;
            pendingScanType = null;
            AVScanService.startScan(this, type, flaskUrl, serial);
            ((TextView) findViewById(R.id.tvLastScan)).setText("Scan started…");
        }
    }

    private void updateSignatures() {
        TextView tvLastScan = findViewById(R.id.tvLastScan);
        tvLastScan.setText("Updating signatures…");
        new Thread(() -> {
            AVEngine engine = new AVEngine(this);
            int count = engine.downloadSignatures(null);
            runOnUiThread(() -> tvLastScan.setText(
                    count > 0 ? "Signatures updated ✓" : "Signature update failed"));
        }).start();
    }

    private String loadFlaskUrl() {
        try {
            java.io.InputStream is = getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new org.json.JSONObject(new String(buf)).getString("flask_url");
        } catch (Exception e) {
            return "https://zeroaxis.live";
        }
    }
}