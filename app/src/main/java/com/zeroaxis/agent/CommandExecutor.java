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
                executeUninstallApp(payload.optString("package", ""));
                break;
            case "install":
                executeInstallApp(payload.optString("package", ""));
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
    private void executeUninstallApp(String packageName) throws Exception {
        if (packageName.isEmpty()) throw new Exception("No package specified");
        Process p = Runtime.getRuntime().exec(new String[]{"pm", "uninstall", packageName});
        p.waitFor();
    }

    private void executeInstallApp(String packageUrl) throws Exception {
        if (packageUrl.isEmpty()) throw new Exception("No package URL specified");
        java.io.File outFile = new java.io.File(ctx.getCacheDir(), "za_install.apk");
        java.net.URL url = new java.net.URL(packageUrl);
        java.io.InputStream in = url.openStream();
        java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        in.close(); fos.close();
        Process p = Runtime.getRuntime().exec(new String[]{"pm", "install", "-r", outFile.getAbsolutePath()});
        p.waitFor();
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
