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
                log("wifi_ssid=" + ssid + " ip=" + ip);
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