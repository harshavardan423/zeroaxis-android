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
import android.graphics.Point;
import android.view.Display;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.URL;
public class CommandExecutor {
    private static final String CHANNEL_ID = "zeroaxis_cmd";
    private static int notifSeq = 2000;
    private final Context ctx;
    private static final String DEBUG_LOG = "/sdcard/zeroaxis_debug.log";

    private void log(String msg) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(DEBUG_LOG, true);
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " - " + msg + "\n");
            fw.close();
        } catch (Exception e) { }
    }

    public CommandExecutor(Context ctx) {
        this.ctx = ctx;
        createChannel();
    }
    public void execute(String command, JSONObject payload) throws Exception {
        log("CommandExecutor.execute: " + command);
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
            log("Lock executed");
        } else {
            log("Device admin not active for lock");
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
        log("Message notification sent: " + text);
    }
    private void executeWallpaper(String url) throws Exception {
        log("executeWallpaper: downloading from " + url);
        // Download bitmap with scaling
        InputStream in = new URL(url).openStream();
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(in, null, opts);
        in.close();

        // Get display size
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int targetWidth = size.x;
        int targetHeight = size.y;
        log("Display size: " + targetWidth + "x" + targetHeight);

        opts.inSampleSize = Math.max(opts.outWidth / targetWidth, opts.outHeight / targetHeight);
        opts.inJustDecodeBounds = false;
        in = new URL(url).openStream();
        Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
        in.close();

        Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, true);
        WallpaperManager.getInstance(ctx).setBitmap(scaled);
        log("Wallpaper set, original size " + opts.outWidth + "x" + opts.outHeight +
                " scaled to " + targetWidth + "x" + targetHeight);
        bmp.recycle();
        scaled.recycle();
    }
    private void executeUninstallApp(String packageName) throws Exception {
        if (packageName.isEmpty()) throw new Exception("No package specified");
        log("Uninstalling package: " + packageName);
        Process p = Runtime.getRuntime().exec(new String[]{"pm", "uninstall", packageName});
        p.waitFor();
        log("Uninstall completed with exit code " + p.exitValue());
    }

    private void executeInstallApp(String packageUrl) throws Exception {
        if (packageUrl.isEmpty()) throw new Exception("No package URL specified");
        log("Installing from URL: " + packageUrl);
        java.io.File outFile = new java.io.File(ctx.getCacheDir(), "za_install.apk");
        java.net.URL url = new java.net.URL(packageUrl);
        java.io.InputStream in = url.openStream();
        java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        in.close(); fos.close();
        Process p = Runtime.getRuntime().exec(new String[]{"pm", "install", "-r", outFile.getAbsolutePath()});
        p.waitFor();
        log("Install completed with exit code " + p.exitValue());
    }
    private void executeWipe() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(ctx, ZeroAxisAdminReceiver.class);
        if (dpm.isAdminActive(admin)) {
            dpm.wipeData(0);
            log("Wipe initiated");
        } else {
            log("Device admin not active for wipe");
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