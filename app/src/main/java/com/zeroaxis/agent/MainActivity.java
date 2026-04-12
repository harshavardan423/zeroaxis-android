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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ContextCompat.startForegroundService(this, new Intent(this, AgentService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
        String serial    = prefs.getString("serial", "Not enrolled");
        String flaskUrl  = loadFlaskUrl();
        long   lastScan  = prefs.getLong("av_last_scan", 0);
        int    threats   = prefs.getInt("av_threat_count", 0);
        String scanType  = prefs.getString("av_last_scan_type", "—");

        TextView tvSerial    = findViewById(R.id.tvSerial);
        TextView tvServer    = findViewById(R.id.tvServer);
        TextView tvStatus    = findViewById(R.id.tvStatus);
        TextView tvLastScan  = findViewById(R.id.tvLastScan);
        TextView tvThreats   = findViewById(R.id.tvThreats);
        Button   btnQuick    = findViewById(R.id.btnQuickScan);
        Button   btnFull     = findViewById(R.id.btnFullScan);
        Button   btnSigs     = findViewById(R.id.btnUpdateSigs);

        tvSerial.setText("Serial: " + serial);
        tvServer.setText("Server: " + flaskUrl);
        tvStatus.setText("Agent: Running ✓");

        if (lastScan == 0) {
            tvLastScan.setText("Last scan: Never");
        } else {
            String time = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US)
                    .format(new Date(lastScan));
            tvLastScan.setText("Last " + scanType + " scan: " + time);
        }

        tvThreats.setText(threats > 0
                ? "⚠ " + threats + " threat(s) detected"
                : "✓ No threats detected");
        tvThreats.setTextColor(getColor(threats > 0
                ? android.R.color.holo_red_dark
                : android.R.color.holo_green_dark));

        btnQuick.setOnClickListener(v -> startScan("quick", serial, flaskUrl));
        btnFull.setOnClickListener(v  -> startScan("full",  serial, flaskUrl));
        btnSigs.setOnClickListener(v  -> updateSignatures(serial, flaskUrl));
    }

    private void startScan(String type, String serial, String flaskUrl) {
        // On Android 11+, request MANAGE_EXTERNAL_STORAGE if not granted
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "Please grant All Files Access for full scanning", Toast.LENGTH_LONG).show();
                return;
            }
        }
        AVScanService.startScan(this, type, flaskUrl, serial);
        TextView tvLastScan = findViewById(R.id.tvLastScan);
        tvLastScan.setText("Scan started in background...");
    }

    private void updateSignatures(String serial, String flaskUrl) {
        TextView tvLastScan = findViewById(R.id.tvLastScan);
        tvLastScan.setText("Updating signatures...");
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