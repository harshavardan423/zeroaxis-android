package com.zeroaxis.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.Debug;
import android.app.ActivityManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AgentService extends Service {

    private static final String CHANNEL_ID             = "zeroaxis_agent";
    private static final int    NOTIF_ID               = 1001;
    private static final long   STATS_INTERVAL         = 30_000L;
    private static final long   COMMAND_INTERVAL       = 10_000L;
    private static final long   INSTALLED_APPS_INTERVAL = 24 * 60 * 60 * 1000L;

    private static String DEBUG_LOG = null;

    private OkHttpClient client  = new OkHttpClient();
    private Handler      handler = new Handler(Looper.getMainLooper());
    private String flaskUrl;
    private String serial;
    private android.os.PowerManager.WakeLock wakeLock;

    // FIX 1: Guard flag — onStartCommand must only wire up runnables once.
    // Without this, every call to startForegroundService() re-enters
    // onStartCommand and adds duplicate runnables + spawns extra threads.
    // Instance flag, not static — each new process instance must wire up
    // its own runnables. The static flag caused the service to skip
    // wiring after Samsung killed and restarted the process.
    private boolean started = false;

    private void log(String msg) {
        if (DEBUG_LOG == null) return;
        try {
            java.io.FileWriter fw = new java.io.FileWriter(DEBUG_LOG, true);
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date()) + " - " + msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        File logDir = getExternalFilesDir(null);
        DEBUG_LOG = logDir != null
                ? new File(logDir, "zeroaxis_debug.log").getAbsolutePath()
                : getFilesDir() + "/zeroaxis_debug.log";

        flaskUrl = loadConfig();
        serial   = getSharedPreferences("zeroaxis", MODE_PRIVATE)
                       .getString("serial", android.os.Build.SERIAL);
        log("AgentService onCreate serial=" + serial);

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());

        // FIX 5: Only acquire the wakelock once, here in onCreate.
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (wakeLock == null || !wakeLock.isHeld()) {
            wakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "ZeroAxis::AgentWakeLock");
            wakeLock.acquire();
            log("WakeLock acquired");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // FIX 1: Only set up threads and runnables on the very first call.
        // Subsequent startForegroundService() calls from MainActivity/BootReceiver
        // just keep the service alive — they must not spawn duplicate workers.
        if (!started) {
            started = true;
            log("AgentService first start — wiring up workers");
            new Thread(this::reportStats).start();
            new Thread(this::pollCommands).start();
            handler.postDelayed(statsRunnable,   STATS_INTERVAL);
            handler.postDelayed(commandRunnable, COMMAND_INTERVAL);
        } else {
            log("AgentService onStartCommand called again — ignoring (already running)");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("onDestroy");
        started = false;
        handler.removeCallbacks(statsRunnable);
        handler.removeCallbacks(commandRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            log("WakeLock released");
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private final Runnable statsRunnable = new Runnable() {
        @Override public void run() {
            new Thread(AgentService.this::reportStats).start();
            handler.postDelayed(this, STATS_INTERVAL);
        }
    };

    private final Runnable commandRunnable = new Runnable() {
        @Override public void run() {
            new Thread(AgentService.this::pollCommands).start();
            handler.postDelayed(this, COMMAND_INTERVAL);
        }
    };

    private void reportStats() {
        try {
            log("reportStats started");
            JSONObject stats = new JSONObject();

            try {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                stats.put("battery_level",    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                stats.put("battery_charging", bm.isCharging());
                log("battery_level=" + stats.getInt("battery_level"));
            } catch (Exception e) {
                log("Battery error: " + e.getMessage());
                stats.put("battery_level", 0);
                stats.put("battery_charging", false);
            }

            try {
                StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
                stats.put("storage_free_bytes",  sf.getAvailableBytes());
                stats.put("storage_total_bytes", sf.getTotalBytes());
            } catch (Exception e) {
                stats.put("storage_free_bytes",  0);
                stats.put("storage_total_bytes", 0);
            }

            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network activeNet = cm.getActiveNetwork();
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                String ssid = "";
                String ip   = "";
                if (caps != null && caps.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                            getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                    ssid = wi.getSSID().replace("\"", "");
                    android.net.LinkProperties lp = cm.getLinkProperties(activeNet);
                    if (lp != null) {
                        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                            java.net.InetAddress addr = la.getAddress();
                            if (addr instanceof java.net.Inet4Address) {
                                ip = addr.getHostAddress();
                                break;
                            }
                        }
                    }
                }
                stats.put("wifi_ssid",  ssid);
                stats.put("ip_address", ip);
                // --- New fields for lifecycle ---
                String wifiSec = getWifiSecurity();
                int wifiSig = getWifiSignalStrength();
                stats.put("wifi_security", wifiSec);
                stats.put("wifi_signal_strength", wifiSig);
                int cpuPct = getCpuUsagePercent();
                int ramPct = getRamUsagePercent();
                stats.put("cpu_usage_pct", cpuPct);
                stats.put("ram_usage_pct", ramPct);
                log("wifi_ssid=" + ssid + " ip=" + ip + " sec=" + wifiSec + " cpu=" + cpuPct + " ram=" + ramPct);
            } catch (Exception e) {
                stats.put("wifi_ssid",  "");
                stats.put("ip_address", "");
            }

            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                Location loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                stats.put("lat", loc != null ? loc.getLatitude()  : 0);
                stats.put("lng", loc != null ? loc.getLongitude() : 0);
            } catch (Exception e) {
                stats.put("lat", 0);
                stats.put("lng", 0);
            }

            try {
                stats.put("os_version", android.os.Build.VERSION.RELEASE);
                stats.put("model",      android.os.Build.MODEL);
                stats.put("make",       android.os.Build.MANUFACTURER);
                stats.put("android_id", android.provider.Settings.Secure.getString(
                        getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID));
            } catch (Exception e) {
                log("Device info error: " + e.getMessage());
            }

            int screenTime = 0;
            List<UsageStatsHelper.AppUsage> usage = null;
            try {
                usage = UsageStatsHelper.getTodayUsage(this);
                for (UsageStatsHelper.AppUsage a : usage) screenTime += a.foregroundMins;
                log("screenTime=" + screenTime + " apps=" + usage.size());
            } catch (Exception e) {
                log("Usage stats error: " + e.getMessage());
            }
            stats.put("screen_time_today_mins", screenTime);
            stats.put("status", "online");

            post("/api/devices/" + serial + "/stats", stats);
            log("Stats POST sent");

            if (usage != null && !usage.isEmpty()) {
                try {
                    JSONArray apps = new JSONArray();
                    for (UsageStatsHelper.AppUsage a : usage) {
                        JSONObject obj = new JSONObject();
                        obj.put("package_name",    a.packageName);
                        obj.put("app_name",        a.appName);
                        obj.put("foreground_mins", a.foregroundMins);
                        apps.put(obj);
                    }
                    JSONObject usagePayload = new JSONObject();
                    usagePayload.put("date", new SimpleDateFormat(
                            "yyyy-MM-dd", Locale.US).format(new Date()));
                    usagePayload.put("apps", apps);
                    post("/api/devices/" + serial + "/app_usage", usagePayload);
                    log("App usage POST sent, apps=" + apps.length());
                } catch (Exception e) {
                    log("App usage POST error: " + e.getMessage());
                }
            }

            try {
                long nowMs    = System.currentTimeMillis();
                long lastSent = getSharedPreferences("zeroaxis", MODE_PRIVATE)
                        .getLong("last_installed_sent", 0);
                if (nowMs - lastSent > INSTALLED_APPS_INTERVAL) {
                    List<UsageStatsHelper.AppInfo> installed =
                            UsageStatsHelper.getInstalledApps(this);
                    if (!installed.isEmpty()) {
                        JSONArray arr = new JSONArray();
                        for (UsageStatsHelper.AppInfo app : installed) {
                            JSONObject obj = new JSONObject();
                            obj.put("package", app.packageName);
                            obj.put("name",    app.appName);
                            obj.put("version", app.version);
                            arr.put(obj);
                        }
                        JSONObject payload = new JSONObject();
                        payload.put("apps", arr);
                        post("/api/devices/" + serial + "/installed_apps", payload);
                        getSharedPreferences("zeroaxis", MODE_PRIVATE)
                                .edit().putLong("last_installed_sent", nowMs).apply();
                        log("Installed apps sent, count=" + installed.size());
                    }
                }
            } catch (Exception e) {
                log("Installed apps error: " + e.getMessage());
            }

        } catch (Exception e) {
            log("reportStats exception: " + e.getMessage());
        }
    }

    // ----- Helper methods for WiFi security, CPU, RAM -----
    private String getWifiSecurity() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return "unknown";
            // Android does not expose security type directly; we need to check capabilities
            android.net.wifi.WifiManager wifiMan = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wifiMan.getConnectionInfo();
            if (info == null) return "unknown";
            // Get the current network's capabilities via WifiManager
            List<android.net.wifi.ScanResult> results = wifiMan.getScanResults();
            for (android.net.wifi.ScanResult result : results) {
                if (result.SSID != null && result.SSID.equals(info.getSSID().replace("\"", ""))) {
                    String cap = result.capabilities;
                    if (cap.contains("WPA3")) return "wpa3";
                    if (cap.contains("WPA2")) return "wpa2";
                    if (cap.contains("WPA")) return "wpa";
                    if (cap.contains("WEP")) return "wep";
                    if (cap.contains("ESS") && !cap.contains("WPA") && !cap.contains("WEP")) return "open";
                    return "unknown";
                }
            }
            // Fallback: if we can't find scan result, assume WPA2 (most common)
            return "wpa2";
        } catch (Exception e) {
            log("WiFi security error: " + e.getMessage());
            return "unknown";
        }
    }

    private int getWifiSignalStrength() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return -1;
            int rssi = wi.getRssi();
            // Convert to percentage (0-100) if desired, or return raw dBm
            return rssi;  // raw dBm (e.g., -65)
        } catch (Exception e) {
            return -1;
        }
    }

    private int getCpuUsagePercent() {
        // First try the accurate delta method (200ms sample)
        try {
            long[] first = readStats();
            Thread.sleep(200);
            long[] second = readStats();

            long totalDelta = second[0] - first[0];
            long activeDelta = second[1] - first[1];
            if (totalDelta > 0) {
                int percent = (int) ((activeDelta * 100.0) / totalDelta);
                // If any CPU activity was measured, ensure at least 1%
                if (activeDelta > 0 && percent == 0) percent = 1;
                return Math.min(100, Math.max(0, percent));
            }
        } catch (Exception e) {
            log("CPU delta error: " + e.getMessage());
        }

        // Fallback: instantaneous usage (floating point to avoid truncation)
        try {
            long[] stats = readStats();
            long total = stats[0];
            long active = stats[1];
            if (total > 0) {
                int percent = (int) ((active * 100.0) / total);
                if (active > 0 && percent == 0) percent = 1;
                return Math.min(100, Math.max(0, percent));
            }
        } catch (Exception e) {
            log("CPU fallback error: " + e.getMessage());
        }

        // If everything fails, return 0
        return 0;
    }

    private long[] readStats() throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(
            new java.io.InputStreamReader(new java.io.FileInputStream("/proc/stat")));
        String line = r.readLine();
        r.close();
        if (line == null || !line.startsWith("cpu")) {
            throw new Exception("Invalid /proc/stat line");
        }
        String[] parts = line.trim().split("\\s+");
        // parts[0] is "cpu". Not all Android kernels have all fields; default missing to 0.
        long user = 0, nice = 0, system = 0, idle = 0, iowait = 0, irq = 0, softirq = 0;
        if (parts.length > 1) user = Long.parseLong(parts[1]);
        if (parts.length > 2) nice = Long.parseLong(parts[2]);
        if (parts.length > 3) system = Long.parseLong(parts[3]);
        if (parts.length > 4) idle = Long.parseLong(parts[4]);
        if (parts.length > 5) iowait = Long.parseLong(parts[5]);
        if (parts.length > 6) irq = Long.parseLong(parts[6]);
        if (parts.length > 7) softirq = Long.parseLong(parts[7]);
        long total = user + nice + system + idle + iowait + irq + softirq;
        // Idle time includes both idle and iowait (waiting for I/O)
        long active = total - (idle + iowait);
        return new long[]{total, active};
    }

    private int getRamUsagePercent() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long total = mi.totalMem;
            long free = mi.availMem;
            long used = total - free;
            return (int) (used * 100 / total);
        } catch (Exception e) {
            log("RAM error: " + e.getMessage());
            return -1;
        }
    }
    // ----- End helpers -----

    private void pollCommands() {
        try {
            log("pollCommands started");
            Request req = new Request.Builder()
                    .url(flaskUrl + "/api/devices/" + serial + "/pending_commands")
                    .build();
            Response res = client.newCall(req).execute();
            if (!res.isSuccessful()) {
                log("pollCommands HTTP " + res.code());
                res.close();
                return;
            }

            JSONArray cmds = new JSONArray(res.body().string());
            res.close();
            log("Received " + cmds.length() + " commands");
            CommandExecutor executor = new CommandExecutor(this);

            for (int i = 0; i < cmds.length(); i++) {
                JSONObject cmd     = cmds.getJSONObject(i);
                int        id      = cmd.getInt("id");
                String     command = cmd.getString("command");
                JSONObject payload = cmd.optJSONObject("payload");
                if (payload == null) payload = new JSONObject();
                log("Executing command " + command + " id=" + id);

                String status = "done";
                try {
                    executor.execute(command, payload);
                    log("Command " + command + " ok");
                } catch (Exception e) {
                    log("Command " + command + " failed: " + e.getMessage());
                    status = "failed";
                }

                try {
                    JSONObject ack = new JSONObject();
                    ack.put("command_id", id);
                    ack.put("status", status);
                    post("/api/devices/" + serial + "/command_ack", ack);
                    log("Ack sent id=" + id + " status=" + status);
                } catch (Exception e) {
                    log("Ack failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log("pollCommands exception: " + e.getMessage());
        }
    }

    private void post(String path, JSONObject body) throws Exception {
        RequestBody rb = RequestBody.create(
                body.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
                .url(flaskUrl + path).post(rb).build();
        client.newCall(req).execute().close();
    }

    private String loadConfig() {
        try {
            InputStream is = getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new JSONObject(new String(buf)).getString("flask_url");
        } catch (Exception e) {
            return "https://zeroaxis.live";
        }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "ZeroAxis Agent",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Device management agent");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ZeroAxis Agent")
                .setContentText("Device management active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setForegroundServiceBehavior(
                        NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}