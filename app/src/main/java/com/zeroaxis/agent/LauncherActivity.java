package com.zeroaxis.agent;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
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

    private RecyclerView rvApps;
    private TextView tvStatus, tvScreenTime;
    private ProgressBar progressScreenTime;
    private Button btnLogout, btnRefresh;
    private OkHttpClient client = new OkHttpClient();
    private String flaskUrl;
    private String deviceSerial;
    private String currentUser;
    private JSONObject policies;
    private List<AppItem> allowedApps = new ArrayList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable policySyncRunnable;
    private Runnable screenTimeUpdateRunnable;
    private int usedToday = 0;
    private int dailyLimit = 0;
    private String todayDate;
    private AppAdapter adapter;

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

            rvApps = findViewById(R.id.rvApps);
            tvStatus = findViewById(R.id.tvStatus);
            tvScreenTime = findViewById(R.id.tvScreenTime);
            progressScreenTime = findViewById(R.id.progressScreenTime);
            btnLogout = findViewById(R.id.btnLogout);
            Button btnBrowser = findViewById(R.id.btnBrowser);
            Button btnDocuments = findViewById(R.id.btnDocuments);
            btnRefresh = findViewById(R.id.btnRefresh);

            flaskUrl = loadFlaskUrl();
            deviceSerial = getSharedPreferences("zeroaxis", MODE_PRIVATE).getString("serial", null);
            currentUser = getSharedPreferences("zeroaxis", MODE_PRIVATE).getString("logged_in_user", null);
            if (currentUser == null) {
                logout();
                return;
            }

            loadPolicies();
            loadUsedToday();
            applyPolicies();

            btnLogout.setOnClickListener(v -> logout());
            btnRefresh.setOnClickListener(v -> {
                syncPolicies();
                Toast.makeText(this, "Syncing policies...", Toast.LENGTH_SHORT).show();
            });

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
                PackageManager pm = getPackageManager();
                for (int i = 0; i < allowed.length(); i++) {
                    String pkg = allowed.getString(i);
                    try {
                        String appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                        Drawable icon = pm.getApplicationIcon(pkg);
                        allowedApps.add(new AppItem(pkg, appName, icon));
                    } catch (PackageManager.NameNotFoundException e) {
                        // App not installed – skip
                    }
                }
            }
            dailyLimit = policies.optInt("screen_time_limit_mins", 0);
        } catch (Exception e) {
            policies = new JSONObject();
            allowedApps.clear();
            dailyLimit = 0;
            logToFile("loadPolicies error: " + e.toString());
        }
    }

    private void loadUsedToday() {
        Calendar cal = Calendar.getInstance();
        todayDate = cal.get(Calendar.YEAR) + "-" + (cal.get(Calendar.MONTH)+1) + "-" + cal.get(Calendar.DAY_OF_MONTH);
        SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
        usedToday = prefs.getInt("screen_time_used_" + currentUser + "_" + todayDate, 0);
    }

    private void saveUsedToday() {
        SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
        prefs.edit().putInt("screen_time_used_" + currentUser + "_" + todayDate, usedToday).apply();
    }

    private void applyPolicies() {
        try {
            if (isCurfewActive()) {
                showCurfewDialog();
                return;
            }
            if (dailyLimit > 0 && usedToday >= dailyLimit) {
                showScreenTimeExceededDialog();
                return;
            }
            buildAppGrid();
            setupSpecialButtons();
        } catch (Exception e) {
            logToFile("applyPolicies error: " + e.toString());
            tvStatus.setText("Error: " + e.getMessage());
        }
    }

    private void buildAppGrid() {
        if (allowedApps.isEmpty()) {
            tvStatus.setText("No apps allowed. Contact administrator.");
            rvApps.setVisibility(View.GONE);
            return;
        }
        tvStatus.setText("Welcome, " + currentUser);
        rvApps.setVisibility(View.VISIBLE);
        int columnCount = getResources().getInteger(R.integer.grid_columns);
        GridLayoutManager layoutManager = new GridLayoutManager(this, columnCount);
        rvApps.setLayoutManager(layoutManager);
        adapter = new AppAdapter(allowedApps);
        rvApps.setAdapter(adapter);
    }

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {
        private List<AppItem> apps;

        AppAdapter(List<AppItem> apps) { this.apps = apps; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem app = apps.get(position);
            holder.tvAppName.setText(app.appName);
            holder.ivAppIcon.setImageDrawable(app.icon);
            holder.itemView.setOnClickListener(v -> launchApp(app.packageName));
        }

        @Override
        public int getItemCount() { return apps.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivAppIcon;
            TextView tvAppName;
            ViewHolder(View itemView) {
                super(itemView);
                ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
            }
        }
    }

    private static class AppItem {
        String packageName;
        String appName;
        Drawable icon;
        AppItem(String pkg, String name, Drawable icon) {
            this.packageName = pkg;
            this.appName = name;
            this.icon = icon;
        }
    }

    private void launchApp(String packageName) {
        try {
            if (isCurfewActive()) {
                showCurfewDialog();
                return;
            }
            if (dailyLimit > 0 && usedToday >= dailyLimit) {
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
            Calendar cal = Calendar.getInstance();
            String newDate = cal.get(Calendar.YEAR) + "-" + (cal.get(Calendar.MONTH)+1) + "-" + cal.get(Calendar.DAY_OF_MONTH);
            if (!newDate.equals(todayDate)) {
                todayDate = newDate;
                usedToday = 0;
                saveUsedToday();
            }

            usedToday++;
            saveUsedToday();

            int remaining = Math.max(0, dailyLimit - usedToday);
            if (dailyLimit > 0) {
                tvScreenTime.setText(usedToday + " min / " + dailyLimit + " min (" + remaining + " left)");
                int percent = (int) ((usedToday * 100.0) / dailyLimit);
                progressScreenTime.setProgress(Math.min(percent, 100));
                progressScreenTime.setVisibility(View.VISIBLE);
            } else {
                tvScreenTime.setText(usedToday + " min (unlimited)");
                progressScreenTime.setVisibility(View.GONE);
            }

            if (dailyLimit > 0 && usedToday >= dailyLimit) {
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
                .setMessage("You have reached your daily screen time limit. The device will lock.")
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
                            if (dailyLimit > 0 && usedToday >= dailyLimit) {
                                showScreenTimeExceededDialog();
                            } else {
                                buildAppGrid();
                                setupSpecialButtons();
                                updateScreenTime();
                            }
                        });
                    } catch (Exception e) { }
                }
            }
        });
    }

    private void logout() {
        saveUsedToday();

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

    private void setupSpecialButtons() {
        Button btnBrowser = findViewById(R.id.btnBrowser);
        Button btnDocuments = findViewById(R.id.btnDocuments);
        String browserPkg = policies.optString("allowed_browser", "");
        String docViewerPkg = policies.optString("allowed_document_viewer", "");

        if (browserPkg.isEmpty()) {
            btnBrowser.setVisibility(View.GONE);
        } else {
            btnBrowser.setVisibility(View.VISIBLE);
            btnBrowser.setOnClickListener(v -> launchApp(browserPkg));
        }

        if (docViewerPkg.isEmpty()) {
            btnDocuments.setVisibility(View.GONE);
        } else {
            btnDocuments.setVisibility(View.VISIBLE);
            btnDocuments.setOnClickListener(v -> launchApp(docViewerPkg));
        }
    }
}