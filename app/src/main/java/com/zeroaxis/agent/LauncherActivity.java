package com.zeroaxis.agent;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LauncherActivity extends AppCompatActivity {

    private GridLayout appGrid;
    private TextView tvStatus, tvScreenTime;
    private Button btnLogout;
    private OkHttpClient client = new OkHttpClient();
    private String flaskUrl;
    private String deviceSerial;
    private String currentUser;
    private JSONObject policies;
    private List<String> allowedApps = new ArrayList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable policySyncRunnable;
    private Runnable screenTimeUpdateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        appGrid = findViewById(R.id.appGrid);
        tvStatus = findViewById(R.id.tvStatus);
        tvScreenTime = findViewById(R.id.tvScreenTime);
        btnLogout = findViewById(R.id.btnLogout);

        flaskUrl = loadFlaskUrl();
        deviceSerial = getSharedPreferences("zeroaxis", MODE_PRIVATE).getString("serial", null);
        currentUser = getSharedPreferences("zeroaxis", MODE_PRIVATE).getString("logged_in_user", null);
        if (currentUser == null) {
            logout();
            return;
        }

        loadPolicies();
        applyPolicies();

        btnLogout.setOnClickListener(v -> logout());

        // Periodic policy sync every 15 minutes
        policySyncRunnable = () -> {
            syncPolicies();
            handler.postDelayed(policySyncRunnable, 15 * 60 * 1000);
        };
        handler.post(policySyncRunnable);

        // Screen time tracking every minute
        screenTimeUpdateRunnable = () -> {
            updateScreenTime();
            handler.postDelayed(screenTimeUpdateRunnable, 60 * 1000);
        };
        handler.post(screenTimeUpdateRunnable);
    }

    private void loadPolicies() {
        SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
        String policiesStr = prefs.getString("user_policies", "{}");
        try {
            policies = new JSONObject(policiesStr);
            JSONArray allowed = policies.optJSONArray("allowed_apps");
            allowedApps.clear();
            if (allowed != null) {
                for (int i = 0; i < allowed.length(); i++) {
                    allowedApps.add(allowed.getString(i));
                }
            }
        } catch (Exception e) {
            policies = new JSONObject();
        }
    }

    private void applyPolicies() {
        // Check curfew
        if (isCurfewActive()) {
            showCurfewDialog();
            return;
        }
        // Check screen time limit
        int limit = policies.optInt("screen_time_limit_mins", 0);
        int todayUsage = getTodayScreenTime();
        if (limit > 0 && todayUsage >= limit) {
            showScreenTimeExceededDialog();
            return;
        }
        // Build app grid
        buildAppGrid();
    }

    private void buildAppGrid() {
        appGrid.removeAllViews();
        if (allowedApps.isEmpty()) {
            tvStatus.setText("No apps allowed. Contact administrator.");
            return;
        }
        tvStatus.setText("Welcome, " + currentUser);
        PackageManager pm = getPackageManager();
        for (String pkg : allowedApps) {
            try {
                // Check if app is installed
                pm.getPackageInfo(pkg, 0);
                Button btn = new Button(this);
                btn.setText(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString());
                btn.setOnClickListener(v -> launchApp(pkg));
                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width = 0;
                params.height = GridLayout.LayoutParams.WRAP_CONTENT;
                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                btn.setLayoutParams(params);
                appGrid.addView(btn);
            } catch (PackageManager.NameNotFoundException e) {
                // App not installed – skip
            }
        }
    }

    private void launchApp(String packageName) {
        // Check curfew and screen time again before launch
        if (isCurfewActive()) {
            showCurfewDialog();
            return;
        }
        int limit = policies.optInt("screen_time_limit_mins", 0);
        int todayUsage = getTodayScreenTime();
        if (limit > 0 && todayUsage >= limit) {
            showScreenTimeExceededDialog();
            return;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this, "App not found", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isCurfewActive() {
        String start = policies.optString("curfew_start", "");
        String end = policies.optString("curfew_end", "");
        if (start.isEmpty() || end.isEmpty()) return false;
        // Parse HH:MM
        try {
            String[] startParts = start.split(":");
            String[] endParts = end.split(":");
            int startMin = Integer.parseInt(startParts[0]) * 60 + Integer.parseInt(startParts[1]);
            int endMin = Integer.parseInt(endParts[0]) * 60 + Integer.parseInt(endParts[1]);
            int nowMin = (int) (System.currentTimeMillis() / 60000) % (24*60);
            if (startMin < endMin) {
                return nowMin < startMin || nowMin > endMin;
            } else {
                return nowMin >= startMin && nowMin <= endMin;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private int getTodayScreenTime() {
        // Use UsageStatsHelper to get total foreground minutes today
        List<UsageStatsHelper.AppUsage> usage = UsageStatsHelper.getTodayUsage(this);
        int total = 0;
        for (UsageStatsHelper.AppUsage u : usage) total += u.foregroundMins;
        return total;
    }

    private void updateScreenTime() {
        int limit = policies.optInt("screen_time_limit_mins", 0);
        int used = getTodayScreenTime();
        if (limit > 0) {
            int remaining = Math.max(0, limit - used);
            tvScreenTime.setText("Screen time today: " + used + " min / " + limit + " min (" + remaining + " left)");
        } else {
            tvScreenTime.setText("Screen time today: " + used + " min");
        }
        // If exceeded, lock
        if (limit > 0 && used >= limit) {
            showScreenTimeExceededDialog();
        }
    }

    private void showCurfewDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Device Locked")
                .setMessage("Device is locked due to curfew.")
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> logout())
                .show();
    }

    private void showScreenTimeExceededDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Time Limit Reached")
                .setMessage("You have reached your daily screen time limit. The device will lock.")
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> logout())
                .show();
    }

    private void syncPolicies() {
        Request request = new Request.Builder()
                .url(flaskUrl + "/api/device/policy/" + deviceSerial)
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) { }

            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        JSONObject pol = new JSONObject(response.body().string());
                        SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
                        prefs.edit().putString("user_policies", pol.toString()).apply();
                        runOnUiThread(() -> {
                            loadPolicies();
                            applyPolicies();
                        });
                    } catch (Exception e) { }
                }
            }
        });
    }

    private void logout() {
        // Call logout endpoint
        JSONObject payload = new JSONObject();
        try {
            payload.put("device_serial", deviceSerial);
        } catch (Exception e) {}
        RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(flaskUrl + "/api/enduser/logout")
                .post(body)
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
        });
        // Clear user data
        getSharedPreferences("zeroaxis", MODE_PRIVATE).edit()
                .remove("logged_in_user")
                .remove("user_policies")
                .apply();
        // Go to login screen
        Intent intent = new Intent(LauncherActivity.this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(policySyncRunnable);
        handler.removeCallbacks(screenTimeUpdateRunnable);
    }
}