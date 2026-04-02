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

public class AgentService extends Service {

    private static final String CHANNEL_ID       = "zeroaxis_agent";
    private static final int    NOTIF_ID         = 1001;
    private static final long   STATS_INTERVAL   = 15 * 60 * 1000L;
    private static final long   COMMAND_INTERVAL =  2 * 60 * 1000L;

    private OkHttpClient client  = new OkHttpClient();
    private Handler      handler = new Handler(Looper.getMainLooper());
    private String flaskUrl;
    private String serial;

    @Override
    public void onCreate() {
        super.onCreate();
        flaskUrl = loadConfig();
        serial   = getSharedPreferences("zeroaxis", MODE_PRIVATE)
                       .getString("serial", android.os.Build.SERIAL);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(this::reportStats).start();
        new Thread(this::pollCommands).start();
        handler.postDelayed(statsRunnable,   STATS_INTERVAL);
        handler.postDelayed(commandRunnable, COMMAND_INTERVAL);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(statsRunnable);
        handler.removeCallbacks(commandRunnable);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─── Runnables ───────────────────────────────────────────────────────────

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

    // ─── Stats reporting ─────────────────────────────────────────────────────

    private void reportStats() {
        try {
            JSONObject stats = new JSONObject();

            // Battery — isolated
            try {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                stats.put("battery_level",    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                stats.put("battery_charging", bm.isCharging());
            } catch (Exception e) {
                stats.put("battery_level", 0);
                stats.put("battery_charging", false);
            }

            // Storage — isolated
            try {
                StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
                stats.put("storage_free_bytes",  sf.getAvailableBytes());
                stats.put("storage_total_bytes", sf.getTotalBytes());
            } catch (Exception e) {
                stats.put("storage_free_bytes",  0);
                stats.put("storage_total_bytes", 0);
            }

            // WiFi — isolated, Android 10+ compatible
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
                    // IP via LinkProperties (API 23+)
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
            } catch (Exception e) {
                stats.put("wifi_ssid",  "");
                stats.put("ip_address", "");
            }

            // Location — isolated
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

            // Device info — isolated
            try {
                stats.put("os_version", android.os.Build.VERSION.RELEASE);
                stats.put("model",      android.os.Build.MODEL);
                stats.put("make",       android.os.Build.MANUFACTURER);
                stats.put("android_id", android.provider.Settings.Secure.getString(
                        getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID));
            } catch (Exception e) { /* non-fatal */ }

            // Usage stats — isolated
            int screenTime = 0;
            List<UsageStatsHelper.AppUsage> usage = null;
            try {
                usage = UsageStatsHelper.getTodayUsage(this);
                for (UsageStatsHelper.AppUsage a : usage) screenTime += a.foregroundMins;
            } catch (Exception e) {
                usage = null;
            }
            stats.put("screen_time_today_mins", screenTime);
            stats.put("status", "online");

            // POST stats
            post("/api/devices/" + serial + "/stats", stats);

            // POST app usage — isolated
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
                } catch (Exception e) { /* non-fatal */ }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─── Command polling ─────────────────────────────────────────────────────

    private void pollCommands() {
        try {
            Request req = new Request.Builder()
                    .url(flaskUrl + "/api/devices/" + serial + "/pending_commands")
                    .build();
            Response res = client.newCall(req).execute();
            if (!res.isSuccessful()) return;

            org.json.JSONArray cmds = new org.json.JSONArray(res.body().string());
            CommandExecutor executor = new CommandExecutor(this);

            for (int i = 0; i < cmds.length(); i++) {
                JSONObject cmd     = cmds.getJSONObject(i);
                int id             = cmd.getInt("id");
                String command     = cmd.getString("command");
                JSONObject payload = cmd.optJSONObject("payload");
                if (payload == null) payload = new JSONObject();

                String status = "done";
                try {
                    executor.execute(command, payload);
                } catch (Exception e) {
                    status = "failed";
                }

                try {
                    JSONObject ack = new JSONObject();
                    ack.put("command_id", id);
                    ack.put("status", status);
                    post("/api/devices/" + serial + "/command_ack", ack);
                } catch (Exception e) { /* non-fatal */ }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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
                .build();
    }
}
