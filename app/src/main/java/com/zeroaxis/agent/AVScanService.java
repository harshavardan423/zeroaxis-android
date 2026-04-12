package com.zeroaxis.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AVScanService extends Worker {

    private static final String TAG        = "AVScanService";
    private static final int    NOTIF_ID   = 9900;
    private static final String CHANNEL_ID = "za_av_scan";

    public static final String EXTRA_SCAN_TYPE = "scan_type";
    public static final String EXTRA_FLASK_URL = "flask_url";
    public static final String EXTRA_SERIAL    = "serial";

    private static final long MAX_FILE_SIZE  = 50L * 1024 * 1024;
    private static final int  MAX_DEPTH_QUICK = 2;
    private static final int  MAX_DEPTH_FULL  = 5;
    private static final int  MAX_FILES       = 2000;

    private final OkHttpClient http = new OkHttpClient();

    // WorkManager requires this constructor signature exactly.
    public AVScanService(@NonNull Context context,
                         @NonNull WorkerParameters params) {
        super(context, params);
    }

    // ── Static entry point called by MainActivity and CommandExecutor ─────────

    public static void startScan(Context ctx, String scanType,
                                 String flaskUrl, String serial) {
        Data inputData = new Data.Builder()
                .putString(EXTRA_SCAN_TYPE, scanType)
                .putString(EXTRA_FLASK_URL, flaskUrl)
                .putString(EXTRA_SERIAL,    serial)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AVScanService.class)
                .setInputData(inputData)
                .build();

        WorkManager.getInstance(ctx).enqueue(request);
    }

    // ── WorkManager callback ──────────────────────────────────────────────────

    @NonNull
    @Override
    public Result doWork() {
        // setForegroundAsync() is the WorkManager equivalent of startForeground().
        // Must be called before doing any real work.
        try {
            setForegroundAsync(new ForegroundInfo(NOTIF_ID,
                    buildNotification("ZeroAxis AV scan starting…"))).get();
        } catch (Exception e) {
            Log.w(TAG, "setForegroundAsync failed: " + e.getMessage());
            // Non-fatal — continue anyway. On some devices/versions this
            // throws but the work still runs.
        }

        File log = crashLog();
        appendLog(log, "=== Scan started at " + new Date());

        try {
            runScan(log);
            return Result.success();
        } catch (Throwable t) {
            appendLog(log, "FATAL: " + t);
            for (StackTraceElement s : t.getStackTrace())
                appendLog(log, "  at " + s);
            Log.e(TAG, "Fatal error in scan", t);
            return Result.failure();
        }
    }

    // ── Main scan logic ───────────────────────────────────────────────────────

    private void runScan(File log) {
        // WorkManager passes inputs via Data, not Intent extras.
        String scanType = getInputData().getString(EXTRA_SCAN_TYPE);
        String flaskUrl = getInputData().getString(EXTRA_FLASK_URL);
        String serial   = getInputData().getString(EXTRA_SERIAL);
        if (scanType == null) scanType = "quick";
        if (flaskUrl == null || flaskUrl.isEmpty()) flaskUrl = loadFlaskUrl();

        appendLog(log, "scan_type=" + scanType + "  serial=" + serial);

        // Degrade full scan to quick if MANAGE_EXTERNAL_STORAGE not granted.
        if ("full".equals(scanType)
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            appendLog(log, "MANAGE_EXTERNAL_STORAGE not granted — downgrading to quick");
            scanType = "quick";
        }

        File scanRoot;
        int  maxDepth;
        if ("quick".equals(scanType)) {
            scanRoot = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            maxDepth = MAX_DEPTH_QUICK;
        } else {
            scanRoot = Environment.getExternalStorageDirectory();
            maxDepth = MAX_DEPTH_FULL;
        }

        if (scanRoot == null || !scanRoot.exists()) {
            appendLog(log, "Scan root unavailable: " + scanRoot);
            return;
        }
        appendLog(log, "Scan root: " + scanRoot.getPath() + "  max_depth=" + maxDepth);
        updateNotification("Scanning " + scanRoot.getName() + "…");

        AVEngine engine = new AVEngine(getApplicationContext());
        boolean sigsOk = engine.loadSignatures();
        appendLog(log, "loadSignatures() from disk: " + sigsOk);

        if (!sigsOk) {
            appendLog(log, "Signatures missing — downloading");
            updateNotification("Downloading signatures…");
            int n = engine.downloadSignatures(null);
            appendLog(log, "Downloaded " + n + " files");
        }

        JSONArray threats = new JSONArray();
        int scanned = bfsScan(scanRoot, engine, threats, maxDepth, log);

        appendLog(log, "Scan done — files=" + scanned + "  threats=" + threats.length());
        pushThreats(flaskUrl, serial, scanType, scanned, threats, log);

        getApplicationContext()
                .getSharedPreferences("zeroaxis", Context.MODE_PRIVATE)
                .edit()
                .putLong("av_last_scan",        System.currentTimeMillis())
                .putInt("av_threat_count",      threats.length())
                .putString("av_last_scan_type", scanType)
                .apply();
    }

    // ── Iterative BFS ─────────────────────────────────────────────────────────

    private int bfsScan(File root, AVEngine engine,
                        JSONArray threats, int maxDepth, File log) {
        int scanned = 0;
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{root, 0});

        while (!stack.isEmpty() && scanned < MAX_FILES) {
            Object[] entry = stack.pop();
            File dir   = (File)    entry[0];
            int  depth = (Integer) entry[1];

            File[] children;
            try {
                children = dir.listFiles();
            } catch (SecurityException e) {
                continue;
            }
            if (children == null) continue;

            for (File f : children) {
                if (scanned >= MAX_FILES) break;

                if (f.isDirectory()) {
                    if (depth < maxDepth) stack.push(new Object[]{f, depth + 1});
                } else {
                    long size = f.length();
                    if (size == 0 || size > MAX_FILE_SIZE) continue;
                    try {
                        boolean hit = engine.checkFile(f);
                        scanned++;
                        if (hit) {
                            appendLog(log, "THREAT: " + f.getAbsolutePath());
                            String[] hashes = engine.hashFile(f);
                            JSONObject t = new JSONObject();
                            t.put("file_path",   f.getAbsolutePath());
                            t.put("threat_name", "Malware (Signature)");
                            t.put("hash",        hashes[2] != null ? hashes[2] : "");
                            threats.put(t);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "checkFile: " + f.getName() + " " + e.getMessage());
                    }
                }
            }
        }
        return scanned;
    }

    // ── HTTP push ─────────────────────────────────────────────────────────────

    private void pushThreats(String flaskUrl, String serial,
                             String scanType, int scanned,
                             JSONArray threats, File log) {
        if (flaskUrl == null || serial == null || serial.isEmpty()) {
            appendLog(log, "No flaskUrl/serial — skipping push");
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("scan_type",     scanType);
            payload.put("files_scanned", scanned);
            payload.put("threats",       threats);

            RequestBody body = RequestBody.create(
                    payload.toString(),
                    MediaType.parse("application/json"));
            Request req = new Request.Builder()
                    .url(flaskUrl + "/api/devices/" + serial + "/av/threats")
                    .post(body)
                    .build();
            Response resp = http.newCall(req).execute();
            appendLog(log, "Push HTTP " + resp.code());
            resp.close();
        } catch (Exception e) {
            appendLog(log, "Push failed: " + e.getMessage());
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager)
                getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "AV Scan", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        ensureChannel();
        return new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setContentTitle("ZeroAxis AV Scan")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager)
                getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private File crashLog() {
        File dir = getApplicationContext().getExternalFilesDir(null);
        if (dir == null) dir = getApplicationContext().getFilesDir();
        return new File(dir, "av_crash.log");
    }

    private void appendLog(File log, String msg) {
        try {
            FileWriter fw = new FileWriter(log, true);
            fw.write(msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
        Log.d(TAG, msg);
    }

    private String loadFlaskUrl() {
        try {
            java.io.InputStream is =
                    getApplicationContext().getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new org.json.JSONObject(new String(buf)).getString("flask_url");
        } catch (Exception e) {
            return "https://zeroaxis.live";
        }
    }
}