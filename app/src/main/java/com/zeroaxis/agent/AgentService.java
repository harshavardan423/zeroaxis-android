package com.zeroaxis.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AgentService extends Service {

    private static final String CHANNEL_ID   = "zeroaxis_agent";
    private static final int    NOTIF_ID     = 1001;
    private static final long   STATS_INTERVAL   = 15 * 60 * 1000L;  // 15 mins
    private static final long   COMMAND_INTERVAL =  2 * 60 * 1000L;  //  2 mins

    private OkHttpClient client = new OkHttpClient();
    private Handler handler     = new Handler(Looper.getMainLooper());
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
        // Run immediately, then schedule repeating
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
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean charging = bm.isCharging();

            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            long free  = sf.getAvailableBytes();
            long total = sf.getTotalBytes();

            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi   = wm.getConnectionInfo();
            String ssid   = wi.getSSID().replace("\"", "");
            int ipInt     = wi.getIpAddress();
            String ip     = String.format("%d.%d.%d.%d",
                    ipInt & 0xff, (ipInt >> 8) & 0xff,
                    (ipInt >> 16) & 0xff, (ipInt >> 24) & 0xff);

            double lat = 0, lng = 0;
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                Location loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc != null) { lat = loc.getLatitude(); lng = loc.getLongitude(); }
            } catch (SecurityException ignored) {}

            List<UsageStatsHelper.AppUsage> usage = UsageStatsHelper.getTodayUsage(this);
            int screenTime = 0;
            for (UsageStatsHelper.AppUsage a : usage) screenTime += a.foregroundMins;

            JSONObject stats = new JSONObject();
            stats.put("battery_level",       battery);
            stats.put("battery_charging",    charging);
            stats.put("storage_free_bytes",  free);
            stats.put("storage_total_bytes", total);
            stats.put("wifi_ssid",           ssid);
            stats.put("ip_address",          ip);
            stats.put("lat",                 lat);
            stats.put("lng",                 lng);
            stats.put("os_version",          android.os.Build.VERSION.RELEASE);
            stats.put("model",               android.os.Build.MODEL);
            stats.put("make",                android.os.Build.MANUFACTURER);
            stats.put("android_id",          android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID));
            stats.put("screen_time_today_mins", screenTime);
            stats.put("status", "online");

            post("/api/devices/" + serial + "/stats", stats);

            // App usage report
            if (!usage.isEmpty()) {
                JSONArray apps = new JSONArray();
                for (UsageStatsHelper.AppUsage a : usage) {
                    JSONObject obj = new JSONObject();
                    obj.put("package_name",   a.packageName);
                    obj.put("app_name",       a.appName);
                    obj.put("foreground_mins", a.foregroundMins);
                    apps.put(obj);
                }
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                JSONObject usagePayload = new JSONObject();
                usagePayload.put("date", sdf.format(new java.util.Date()));
                usagePayload.put("apps", apps);
                post("/api/devices/" + serial + "/app_usage", usagePayload);
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

            JSONArray cmds = new JSONArray(res.body().string());
            CommandExecutor executor = new CommandExecutor(this);

            for (int i = 0; i < cmds.length(); i++) {
                JSONObject cmd = cmds.getJSONObject(i);
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

                // Acknowledge
                JSONObject ack = new JSONObject();
                ack.put("command_id", id);
                ack.put("status", status);
                post("/api/devices/" + serial + "/command_ack", ack);
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
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);
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
