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
import android.graphics.Rect;
import android.view.Display;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.URL;
import java.io.File;
import org.json.JSONArray;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;

public class CommandExecutor {
    private static final String CHANNEL_ID = "zeroaxis_cmd";
    private static int notifSeq = 2000;
    private final Context ctx;
    private static String DEBUG_LOG = null;

    private void log(String msg) {
        if (DEBUG_LOG == null) return;
        try {
            java.io.FileWriter fw = new java.io.FileWriter(DEBUG_LOG, true);
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " - " + msg + "\n");
            fw.close();
        } catch (Exception e) { }
    }

    public CommandExecutor(Context ctx) {
        this.ctx = ctx;
        // Set log file path
        File logDir = ctx.getExternalFilesDir(null);
        if (logDir != null) {
            DEBUG_LOG = new File(logDir, "zeroaxis_debug.log").getAbsolutePath();
        } else {
            DEBUG_LOG = ctx.getFilesDir() + "/zeroaxis_debug.log";
        }
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
                String url = payload.getString("url");
                String style = payload.optString("style", "fill");
                executeWallpaper(url, style);
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
            case "av_scan":
                String scanType = payload.optString("type", "quick");
                AVScanService.startScan(ctx,
                    scanType,
                    loadFlaskUrl(),
                    ctx.getSharedPreferences("zeroaxis", Context.MODE_PRIVATE)
                       .getString("serial", ""));
                break;
            case "av_update_signatures":
                new Thread(() -> {
                    AVEngine engine = new AVEngine(ctx);
                    engine.downloadSignatures(null);
                }).start();
                break;
            case "av_quarantine":
                String qPath = payload.optString("file_path", "");
                if (!qPath.isEmpty()) {
                    new AVEngine(ctx).quarantineFile(qPath);
                }
                break;
            case "av_delete":
                String dPath = payload.optString("file_path", "");
                if (!dPath.isEmpty()) {
                    new AVEngine(ctx).deleteFile(dPath);
                }
                break;
            case "av_ignore":
                // No action needed on device
                break;
            case "block_domains":
                JSONArray domainsArr = payload.optJSONArray("domains");
                if (domainsArr != null) {
                    List<String> domains = new ArrayList<>();
                    for (int i = 0; i < domainsArr.length(); i++) {
                        domains.add(domainsArr.getString(i));
                    }
                    executeBlockDomains(domains);
                }
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

    private void executeWallpaper(String url, String style) throws Exception {
        log("executeWallpaper: downloading from " + url + ", style=" + style);
        // Download bitmap
        InputStream in = new URL(url).openStream();
        Bitmap original = BitmapFactory.decodeStream(in);
        in.close();
        if (original == null) {
            log("Failed to decode bitmap from URL");
            throw new Exception("Invalid image");
        }

        // Get display size
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int screenW = size.x;
        int screenH = size.y;
        log("Screen size: " + screenW + "x" + screenH + ", original image: " + original.getWidth() + "x" + original.getHeight());

        Bitmap finalBitmap = null;
        WallpaperManager wmgr = WallpaperManager.getInstance(ctx);

        switch (style.toLowerCase()) {
            case "fill":
                // Scale to fill screen (crop if necessary)
                float scaleFill = Math.max((float) screenW / original.getWidth(), (float) screenH / original.getHeight());
                int fillW = Math.round(original.getWidth() * scaleFill);
                int fillH = Math.round(original.getHeight() * scaleFill);
                Bitmap scaledFill = Bitmap.createScaledBitmap(original, fillW, fillH, true);
                // Crop to screen size
                int startX = (fillW - screenW) / 2;
                int startY = (fillH - screenH) / 2;
                if (startX < 0) startX = 0;
                if (startY < 0) startY = 0;
                finalBitmap = Bitmap.createBitmap(scaledFill, startX, startY, screenW, screenH);
                scaledFill.recycle();
                log("Fill: scaled to " + fillW + "x" + fillH + ", cropped to " + screenW + "x" + screenH);
                break;
            case "fit":
                // Scale to fit inside screen (letterbox)
                float scaleFit = Math.min((float) screenW / original.getWidth(), (float) screenH / original.getHeight());
                int fitW = Math.round(original.getWidth() * scaleFit);
                int fitH = Math.round(original.getHeight() * scaleFit);
                finalBitmap = Bitmap.createScaledBitmap(original, fitW, fitH, true);
                log("Fit: scaled to " + fitW + "x" + fitH);
                break;
            case "stretch":
                // Stretch to exactly screen size
                finalBitmap = Bitmap.createScaledBitmap(original, screenW, screenH, true);
                log("Stretch: scaled to " + screenW + "x" + screenH);
                break;
            case "center":
                // Place original image centered (no scaling)
                finalBitmap = original;
                log("Center: no scaling");
                break;
            case "tile":
                // Tiling not supported directly; fallback to fill
                finalBitmap = Bitmap.createScaledBitmap(original, screenW, screenH, true);
                log("Tile not supported, fallback to stretch");
                break;
            case "span":
                // Span across multiple screens? Not applicable; fallback to fill
                finalBitmap = Bitmap.createScaledBitmap(original, screenW, screenH, true);
                log("Span not supported, fallback to stretch");
                break;
            default:
                finalBitmap = Bitmap.createScaledBitmap(original, screenW, screenH, true);
                log("Unknown style, fallback to stretch");
        }

        if (finalBitmap == null) finalBitmap = original;
        wmgr.setBitmap(finalBitmap);
        log("Wallpaper set successfully");

        if (finalBitmap != original) finalBitmap.recycle();
        original.recycle();
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

    private String loadFlaskUrl() {
        try {
            java.io.InputStream is = ctx.getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new org.json.JSONObject(new String(buf)).getString("flask_url");
        } catch (Exception e) {
            return "https://zeroaxis.live";
        }
    }

    private void executeBlockDomains(List<String> domains) {
        log("Blocking domains count: " + domains.size());
        // Write to a flat file instead of SharedPreferences —
        // SharedPreferences XML can't handle 60k entries reliably.
        try {
            java.io.File file = new java.io.File(ctx.getFilesDir(), "blocked_domains.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
            java.io.BufferedWriter writer = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(fos));
            for (String d : domains) {
                writer.write(d);
                writer.newLine();
            }
            writer.close();
            log("Wrote " + domains.size() + " domains to " + file.getAbsolutePath());
        } catch (Exception e) {
            log("Failed to write blocked_domains.txt: " + e.getMessage());
        }

        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(new android.content.Intent("com.zeroaxis.DOMAINS_UPDATED"));

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notif = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Domain Blocking Updated")
                .setContentText(domains.size() + " domain(s) blocked")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build();
        nm.notify(notifSeq++, notif);
    }
}