package com.zeroaxis.agent;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

public class LauncherActivity extends AppCompatActivity {

    private LinearLayout appGrid;
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
    private long loginTimeMillis;

    private void logToFile(String msg) {
        try {
            File logFile = new File(getExternalFilesDir(null), "launcher_crash.log");
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " - " + msg + "\n");
            fw.close();
        } catch (Exception e) { }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
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
            loginTimeMillis = System.currentTimeMillis();
            applyPolicies();

            btnLogout.setOnClickListener(v -> logout());

            policySyncRunnable = () -> {
                syncPolicies();
                handler.postDelayed(policySyncRunnable, 15 * 60 * 1000);
            };
            handler.post(policySyncRunnable);

            screenTimeUpdateRunnable = () -> {
                updateScreenTime();
                handler.postDelayed(screenTimeUpdateRunnable, 60 * 1000);
            };
            handler.post(screenTimeUpdateRunnable);
        } catch (Exception e) {
            logToFile("onCreate crash: " + e.toString());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logToFile(sw.toString());
            Toast.makeText(this, "App error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
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
            allowedApps.clear();
            logToFile("loadPolicies error: " + e.toString());
        }
    }

    private void applyPolicies() {
        try {
            if (isCurfewActive()) {
                showCurfewDialog();
                return;
            }
            int limit = (policies != null) ? policies.optInt("screen_time_limit_mins", 0) : 0;
            long sessionMinutes = (System.currentTimeMillis() - loginTimeMillis) / (60 * 1000);
            if (limit > 0 && sessionMinutes >= limit) {
                showScreenTimeExceededDialog();
                return;
            }
            buildAppGrid();
        } catch (Exception e) {
            logToFile("applyPolicies error: " + e.toString());
            e.printStackTrace();
            tvStatus.setText("Error: " + e.getMessage());
        }
    }

    private void buildAppGrid() {
        appGrid.removeAllViews();
        if (allowedApps.isEmpty()) {
            tvStatus.setText("No apps allowed. Contact administrator.");
            return;
        }
        tvStatus.setText("Welcome, " + currentUser);
        PackageManager pm = getPackageManager();
        
        LinearLayout currentRow = null;
        int colCount = 0;
        
        for (String pkg : allowedApps) {
            try {
                pm.getPackageInfo(pkg, 0);
                Button btn = new Button(this);
                btn.setText(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString());
                btn.setOnClickListener(v -> launchApp(pkg));
                
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                params.setMargins(8, 8, 8, 8);
                btn.setLayoutParams(params);
                
                if (currentRow == null || colCount >= 2) {
                    currentRow = new LinearLayout(this);
                    currentRow.setOrientation(LinearLayout.HORIZONTAL);
                    currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                    appGrid.addView(currentRow);
                    colCount = 0;
                }
                currentRow.addView(btn);
                colCount++;
            } catch (PackageManager.NameNotFoundException e) {
                // App not installed – skip
            }
        }
    }

    private void launchApp(String packageName) {
        try {
            if (isCurfewActive()) {
                showCurfewDialog();
                return;
            }
            int limit = (policies != null) ? policies.optInt("screen_time_limit_mins", 0) : 0;
            long sessionMinutes = (System.currentTimeMillis() - loginTimeMillis) / (60 * 1000);
            if (limit > 0 && sessionMinutes >= limit) {
                showScreenTimeExceededDialog();
                return;
            }
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "App not found", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error launching app: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            logToFile("launchApp error: " + e.toString());
        }
    }

    private boolean isCurfewActive() {
        if (policies == null) return false;
        String start = policies.optString("curfew_start", "");
        String end = policies.optString("curfew_end", "");
        if (start.isEmpty() || end.isEmpty()) return false;
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

    private void updateScreenTime() {
        try {
            int limit = (policies != null) ? policies.optInt("screen_time_limit_mins", 0) : 0;
            long sessionMinutes = (System.currentTimeMillis() - loginTimeMillis) / (60 * 1000);
            if (limit > 0) {
                long remaining = Math.max(0, limit - sessionMinutes);
                tvScreenTime.setText("Session time: " + sessionMinutes + " min / " + limit + " min (" + remaining + " left)");
            } else {
                tvScreenTime.setText("Session time: " + sessionMinutes + " min (no limit)");
            }
            if (limit > 0 && sessionMinutes >= limit) {
                showScreenTimeExceededDialog();
            }
        } catch (Exception e) {
            logToFile("updateScreenTime error: " + e.toString());
            tvScreenTime.setText("Error: " + e.getMessage());
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
                .setMessage("You have reached your screen time limit for this session.")
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> logout())
                .show();
    }

    private void syncPolicies() {
        Request request = new Request.Builder()
                .url(flaskUrl + "/api/device/policy/" + deviceSerial)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) { }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
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
        JSONObject payload = new JSONObject();
        try {
            payload.put("device_serial", deviceSerial);
        } catch (Exception e) {}
        RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(flaskUrl + "/api/enduser/logout")
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
        });
        getSharedPreferences("zeroaxis", MODE_PRIVATE).edit()
                .remove("logged_in_user")
                .remove("user_policies")
                .apply();
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