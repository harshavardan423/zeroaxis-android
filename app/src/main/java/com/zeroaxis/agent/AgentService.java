package com.zeroaxis.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.location.Location;
import android.location.LocationManager;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
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
import java.io.File;

public class AgentService extends Service {

    private static final String CHANNEL_ID       = "zeroaxis_agent";
    private static final int    NOTIF_ID         = 1001;
    private static final long   STATS_INTERVAL   = 30 * 1000L;
    private static final long   COMMAND_INTERVAL =  10 * 1000L;
    private static final long   INSTALLED_APPS_INTERVAL = 24 * 60 * 60 * 1000L;
    private static String DEBUG_LOG = null;

    private OkHttpClient client  = new OkHttpClient();
    private Handler      handler = new Handler(Looper.getMainLooper());
    private String flaskUrl;
    private String serial;
    private android.os.PowerManager.WakeLock wakeLock;

    private void log(String msg) {
        if (DEBUG_LOG == null) return;
        try {
            java.io.FileWriter fw = new java.io.FileWriter(DEBUG_LOG, true);
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " - " + msg + "\n");
            fw.close();
        } catch (Exception e) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Set log file in app's external files directory (no permission needed)
        File logDir = getExternalFilesDir(null);
        if (logDir != null) {
            DEBUG_LOG = new File(logDir, "zeroaxis_debug.log").getAbsolutePath();
        } else {
            DEBUG_LOG = getFilesDir() + "/zeroaxis_debug.log";
        }
        flaskUrl = loadConfig();
        serial   = getSharedPreferences("zeroaxis", MODE_PRIVATE)
                       .getString("serial", android.os.Build.SERIAL);
        log("AgentService onCreate, serial=" + serial + ", flaskUrl=" + flaskUrl);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());

        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "ZeroAxis::AgentWakeLock");
        wakeLock.acquire();
        log("WakeLock acquired");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("onStartCommand");
        new Thread(this::reportStats).start();
        new Thread(this::pollCommands).start();
        handler.postDelayed(statsRunnable,   STATS_INTERVAL);
        handler.postDelayed(commandRunnable, COMMAND_INTERVAL);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("onDestroy");
        handler.removeCallbacks(statsRunnable);
        handler.removeCallbacks(commandRunnable);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
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

            // Battery
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

            // Storage
            try {
                StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
                stats.put("storage_free_bytes",  sf.getAvailableBytes());
                stats.put("storage_total_bytes", sf.getTotalBytes());
                log("storage free=" + sf.getAvailableBytes() + " total=" + sf.getTotalBytes());
            } catch (Exception e) {
                log("Storage error: " + e.getMessage());
                stats.put("storage_free_bytes",  0);
                stats.put("storage_total_bytes", 0);
            }

            // WiFi & IP
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network activeNet = cm.getActiveNetwork();
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                String ssid = "";
                String ip = "";
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
                log("wifi_ssid=" + ssid + " ip=" + ip);
            } catch (Exception e) {
                log("WiFi error: " + e.getMessage());
                stats.put("wifi_ssid",  "");
                stats.put("ip_address", "");
            }

            // Location
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                Location loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                stats.put("lat", loc != null ? loc.getLatitude()  : 0);
                stats.put("lng", loc != null ? loc.getLongitude() : 0);
                log("location lat=" + stats.getDouble("lat") + " lng=" + stats.getDouble("lng"));
            } catch (Exception e) {
                log("Location error: " + e.getMessage());
                stats.put("lat", 0);
                stats.put("lng", 0);
            }

            // Device info
            try {
                stats.put("os_version", android.os.Build.VERSION.RELEASE);
                stats.put("model",      android.os.Build.MODEL);
                stats.put("make",       android.os.Build.MANUFACTURER);
                stats.put("android_id", android.provider.Settings.Secure.getString(
                        getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID));
                log("os=" + android.os.Build.VERSION.RELEASE + " model=" + android.os.Build.MODEL);
            } catch (Exception e) { log("Device info error: " + e.getMessage()); }

            // Usage stats
            int screenTime = 0;
            List<UsageStatsHelper.AppUsage> usage = null;
            try {
                usage = UsageStatsHelper.getTodayUsage(this);
                for (UsageStatsHelper.AppUsage a : usage) screenTime += a.foregroundMins;
                log("screenTime=" + screenTime + " apps=" + (usage != null ? usage.size() : 0));
            } catch (Exception e) {
                log("Usage stats error: " + e.getMessage());
                usage = null;
            }
            stats.put("screen_time_today_mins", screenTime);
            stats.put("status", "online");

            // POST stats
            post("/api/devices/" + serial + "/stats", stats);
            log("Stats POST sent");

            // POST app usage
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

            // Send installed apps once per day
            try {
                long nowMs = System.currentTimeMillis();
                long lastSent = getSharedPreferences("zeroaxis", MODE_PRIVATE)
                        .getLong("last_installed_sent", 0);
                if (nowMs - lastSent > INSTALLED_APPS_INTERVAL) {
                    List<UsageStatsHelper.AppInfo> installed = UsageStatsHelper.getInstalledApps(this);
                    if (!installed.isEmpty()) {
                        JSONArray appsArray = new JSONArray();
                        for (UsageStatsHelper.AppInfo app : installed) {
                            JSONObject obj = new JSONObject();
                            obj.put("package", app.packageName);
                            obj.put("name", app.appName);
                            obj.put("version", app.version);
                            appsArray.put(obj);
                        }
                        JSONObject payload = new JSONObject();
                        payload.put("apps", appsArray);
                        post("/api/devices/" + serial + "/installed_apps", payload);
                        getSharedPreferences("zeroaxis", MODE_PRIVATE)
                                .edit().putLong("last_installed_sent", nowMs).apply();
                        log("Installed apps sent, count=" + installed.size());
                    } else {
                        log("No installed apps found");
                    }
                }
            } catch (Exception e) {
                log("Installed apps error: " + e.getMessage());
            }
        } catch (Exception e) {
            log("reportStats exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void pollCommands() {
        try {
            log("pollCommands started");
            Request req = new Request.Builder()
                    .url(flaskUrl + "/api/devices/" + serial + "/pending_commands")
                    .build();
            Response res = client.newCall(req).execute();
            if (!res.isSuccessful()) {
                log("pollCommands response not successful: " + res.code());
                return;
            }

            org.json.JSONArray cmds = new org.json.JSONArray(res.body().string());
            log("Received " + cmds.length() + " commands");
            CommandExecutor executor = new CommandExecutor(this);

            for (int i = 0; i < cmds.length(); i++) {
                JSONObject cmd     = cmds.getJSONObject(i);
                int id             = cmd.getInt("id");
                String command     = cmd.getString("command");
                JSONObject payload = cmd.optJSONObject("payload");
                if (payload == null) payload = new JSONObject();
                log("Executing command " + command + " id=" + id);

                String status = "done";
                try {
                    executor.execute(command, payload);
                    log("Command " + command + " executed successfully");
                } catch (Exception e) {
                    log("Command " + command + " failed: " + e.getMessage());
                    status = "failed";
                }

                try {
                    JSONObject ack = new JSONObject();
                    ack.put("command_id", id);
                    ack.put("status", status);
                    post("/api/devices/" + serial + "/command_ack", ack);
                    log("Ack sent for command id=" + id + " status=" + status);
                } catch (Exception e) {
                    log("Ack failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log("pollCommands exception: " + e.getMessage());
            e.printStackTrace();
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
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}