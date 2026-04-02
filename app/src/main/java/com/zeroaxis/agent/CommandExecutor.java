package com.zeroaxis.agent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.URL;
public class CommandExecutor {
    private static final String CHANNEL_ID = "zeroaxis_cmd";
    private static int notifSeq = 2000;
    private final Context ctx;
    public CommandExecutor(Context ctx) {
        this.ctx = ctx;
        createChannel();
    }
    public void execute(String command, JSONObject payload) throws Exception {
        switch (command) {
            case "lock":
                executeLock();
                break;
            case "message":
                executeMessage(payload.optString("text", "Message from administrator"));
                break;
            case "wallpaper":
                executeWallpaper(payload.getString("url"));
                break;
            case "wipe":
                executeWipe();
                break;
            case "uninstall":
                executeUninstallViaHeadwind(payload.optString("package", ""));
                break;
            case "install":
                executeInstallViaHeadwind(payload.optString("package", ""));
                break;
            default:
                throw new Exception("Unknown command: " + command);
        }
    }
    private void executeLock() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(ctx, ZeroAxisAdminReceiver.class);
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow();
        } else {
            throw new RuntimeException("Device admin not active");
        }
    }
    private void executeMessage(String text) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notif = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Message from Administrator")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();
        nm.notify(notifSeq++, notif);
    }
    private void executeWallpaper(String url) throws Exception {
        InputStream in = new URL(url).openStream();
        Bitmap bmp = BitmapFactory.decodeStream(in);
        in.close();
        WallpaperManager.getInstance(ctx).setBitmap(bmp);
    }
    private String getFlaskUrl() {
        try {
            java.io.InputStream is = ctx.getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf); is.close();
            return new org.json.JSONObject(new String(buf)).getString("flask_url");
        } catch (Exception e) {
            return "https://zeroaxis.live";
        }
    }

    private String getSerial() {
        String serial = ctx.getSharedPreferences("zeroaxis", android.content.Context.MODE_PRIVATE)
                .getString("serial", "");
        if (serial.isEmpty()) serial = android.os.Build.SERIAL;
        return serial;
    }

    private void executeInstallViaHeadwind(String packageUrl) throws Exception {
        if (packageUrl.isEmpty()) throw new Exception("No package URL specified");
        String serial = getSerial();
        String flaskUrl = getFlaskUrl();
        org.json.JSONObject payload = new org.json.JSONObject();
        payload.put("package_url", packageUrl);
        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                payload.toString(), okhttp3.MediaType.parse("application/json"));
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(flaskUrl + "/api/devices/" + serial + "/headwind/install")
                .post(body).build();
        new okhttp3.OkHttpClient().newCall(req).execute();
    }

    private void executeUninstallViaHeadwind(String packageName) throws Exception {
        if (packageName.isEmpty()) throw new Exception("No package specified");
        String serial = getSerial();
        String flaskUrl = getFlaskUrl();
        org.json.JSONObject payload = new org.json.JSONObject();
        payload.put("package", packageName);
        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                payload.toString(), okhttp3.MediaType.parse("application/json"));
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(flaskUrl + "/api/devices/" + serial + "/headwind/uninstall")
                .post(body).build();
        new okhttp3.OkHttpClient().newCall(req).execute();
    }
    private void executeWipe() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(ctx, ZeroAxisAdminReceiver.class);
        if (dpm.isAdminActive(admin)) {
            dpm.wipeData(0);
        } else {
            throw new RuntimeException("Device admin not active for wipe");
        }
    }
    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "ZeroAxis Commands",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(ch);
    }
}
