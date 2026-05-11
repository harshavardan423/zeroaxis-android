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
import android.content.ComponentName;
import android.content.IntentFilter;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;

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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Reload user from preferences (in case the activity was destroyed)
        SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
        String storedUser = prefs.getString("logged_in_user", null);
        if (storedUser == null) {
            // No active session – go to login
            Intent loginIntent = new Intent(this, LoginActivity.class);
            loginIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(loginIntent);
            finish();
            return;
        }
        if (currentUser == null) {
            currentUser = storedUser;
            loadPolicies();
            loadUsedToday();
            applyPolicies();
        }
        // Re-check screen time and curfew
        loadUsedToday();
        if (isCurfewActive()) {
            showCurfewDialog();
        } else if (dailyLimit > 0 && usedToday >= dailyLimit) {
            showScreenTimeExceededDialog();
        }
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
            SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
            currentUser = prefs.getString("logged_in_user", null);
            if (currentUser == null) {
                // No user – go to login and finish this activity
                Intent intent = new Intent(this, LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }

            loadPolicies();
            loadUsedToday();
            applyPolicies();

            btnLogout.setOnClickListener(v -> logout());

            Button btnSupport = findViewById(R.id.btnSupport);
            btnSupport.setOnClickListener(v -> {
                Intent intent = new Intent(LauncherActivity.this, SupportActivity.class);
                intent.putExtra("username", currentUser);
                intent.putExtra("device_serial", deviceSerial);
                intent.putExtra("flask_url", flaskUrl);
                startActivity(intent);
            });

            if (isOemMode()) applyOemLockdown();

            // My Files button — opens this user's dedicated folder
            Button btnFiles = new Button(this);
            btnFiles.setText("📁 My Files");
            btnFiles.setBackgroundColor(android.graphics.Color.parseColor("#5c636a"));
            btnFiles.setTextColor(android.graphics.Color.WHITE);
            btnFiles.setOnClickListener(v -> openUserFolder());
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 16, 0);
            btnFiles.setLayoutParams(lp);
            // Insert before logout button's parent row
            android.view.ViewGroup topBar = (android.view.ViewGroup)
                    btnLogout.getParent();
            topBar.addView(btnFiles, topBar.indexOfChild(btnLogout));
            btnRefresh.setOnClickListener(v -> {
                syncPolicies();
                Toast.makeText(this, "Syncing policies...", Toast.LENGTH_SHORT).show();
            });

            policySyncRunnable = () -> {
                syncPolicies();
                handler.postDelayed(policySyncRunnable, 15 * 60 * 1000);
            };
            handler.postDelayed(policySyncRunnable, 15 * 60 * 1000); // delay first sync, don't run immediately on create

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
        todayDate = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)+1, cal.get(Calendar.DAY_OF_MONTH));
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
            boolean kioskMode = policies.optBoolean("kiosk_mode", false);
            String kioskPackage = policies.optString("kiosk_package", "");
            if (kioskMode && !kioskPackage.isEmpty()) {
                enterKioskMode(kioskPackage);
            } else {
                exitKioskMode();
                buildAppGrid();
                setupSpecialButtons();
            }
        } catch (Exception e) {
            logToFile("applyPolicies error: " + e.toString());
            tvStatus.setText("Error: " + e.getMessage());
        }
    }

    private void enterKioskMode(String kioskPackage) {
        tvStatus.setText("Kiosk mode");
        rvApps.setVisibility(View.GONE);
        btnLogout.setVisibility(View.GONE);
        try { startLockTask(); } catch (Exception e) { logToFile("startLockTask failed: " + e.toString()); }
        Intent intent = getPackageManager().getLaunchIntentForPackage(kioskPackage);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            tvStatus.setText("Kiosk app not installed: " + kioskPackage);
        }
    }

    private void exitKioskMode() {
        try { stopLockTask(); } catch (Exception e) { logToFile("stopLockTask failed: " + e.toString()); }
        btnLogout.setVisibility(View.VISIBLE);
    }

    private boolean isOemMode() {
        try {
            java.io.InputStream is = getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new org.json.JSONObject(new String(buf)).optBoolean("oem", false);
        } catch (Exception e) {
            return false;
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
            String newDate = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)+1, cal.get(Calendar.DAY_OF_MONTH));
            if (!newDate.equals(todayDate)) {
                todayDate = newDate;
                usedToday = 0;
                saveUsedToday();
            }

            usedToday++;
            saveUsedToday();

            // Push per-user screen time to server every 5 minutes (every 5 ticks)
            if (usedToday % 5 == 0) {
                pushUserScreenTime();
            }

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
                            loadUsedToday();
                            applyPolicies();
                            updateScreenTime();
                        });
                    } catch (Exception e) { }
                }
            }
        });
    }

    private void logout() {
        logToFile("LOGOUT CALLED FROM: " + android.util.Log.getStackTraceString(new Exception()));
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
        UsageStatsHelper.clearSessionBaseline(this);
        getSharedPreferences("zeroaxis", MODE_PRIVATE).edit()
                .remove("logged_in_user")
                .remove("user_policies")
                .apply();        Intent intent = new Intent(LauncherActivity.this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void openUserFolder() {
        if (currentUser == null) return;
        Intent intent = new Intent(this, FileManagerActivity.class);
        intent.putExtra("username", currentUser);
        startActivity(intent);
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

    private void pushUserScreenTime() {
        if (deviceSerial == null || currentUser == null) return;
        final int minutesCopy = usedToday;
        final String dateCopy = todayDate;
        new Thread(() -> {
            try {
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("username", currentUser);
                payload.put("date", dateCopy);
                payload.put("screen_time_mins", minutesCopy);
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        payload.toString(),
                        okhttp3.MediaType.parse("application/json"));
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(flaskUrl + "/api/enduser/screen_time/" + deviceSerial)
                        .post(body)
                        .build();
                client.newCall(req).execute().close();
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(policySyncRunnable);
        handler.removeCallbacks(screenTimeUpdateRunnable);
    }

    private void applyOemLockdown() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    getSystemService(android.content.Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, ZeroAxisAdminReceiver.class);
            if (!dpm.isDeviceOwnerApp(getPackageName())) return;

            // Re-apply persistent home app preference
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, homeFilter,
                    new ComponentName(this, LauncherActivity.class));

            // Status bar
            boolean hideStatusBar = false;
            try {
                java.io.InputStream is = getAssets().open("config.json");
                byte[] buf = new byte[is.available()];
                is.read(buf);
                is.close();
                hideStatusBar = new org.json.JSONObject(new String(buf))
                        .optBoolean("hide_status_bar", false);
            } catch (Exception ignored) {}
            dpm.setStatusBarDisabled(admin, hideStatusBar);

        } catch (Exception e) {
            logToFile("applyOemLockdown failed: " + e.toString());
        }
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