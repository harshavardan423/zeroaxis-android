package com.zeroaxis.agent;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class AVScanService extends IntentService {

    private static final String TAG = "AVScanService";
    public  static final String EXTRA_SCAN_TYPE   = "scan_type";
    public  static final String EXTRA_FLASK_URL   = "flask_url";
    public  static final String EXTRA_SERIAL      = "serial";

    private static final long MAX_FILE_SIZE = 500L * 1024 * 1024;

    private OkHttpClient client = new OkHttpClient();

    public AVScanService() {
        super("AVScanService");
    }

    public static void startScan(Context ctx, String scanType, String flaskUrl, String serial) {
        Intent intent = new Intent(ctx, AVScanService.class);
        intent.putExtra(EXTRA_SCAN_TYPE, scanType);
        intent.putExtra(EXTRA_FLASK_URL, flaskUrl);
        intent.putExtra(EXTRA_SERIAL, serial);
        ctx.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // Write crash log to file for debugging
        try {
            java.io.File crashLog = new java.io.File(getExternalFilesDir(null), "av_crash.log");
            java.io.FileWriter fw = new java.io.FileWriter(crashLog, true);
            fw.write("=== Scan started at " + new java.util.Date() + "\n");
            fw.close();
        } catch (Exception e) {}
        
        try {
            if (intent == null) return;

            String scanType = intent.getStringExtra(EXTRA_SCAN_TYPE);
            String flaskUrl = intent.getStringExtra(EXTRA_FLASK_URL);
            String serial   = intent.getStringExtra(EXTRA_SERIAL);
            if (scanType == null) scanType = "quick";

            // Check permission
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    Log.e(TAG, "MANAGE_EXTERNAL_STORAGE not granted");
                    return;
                }
            }

            Log.i(TAG, "Starting " + scanType + " scan");

            AVEngine engine = new AVEngine(this);

            if (!engine.signaturesReady()) {
                if (engine.signaturesNeedUpdate() || !engine.loadSignatures()) {
                    Log.i(TAG, "Downloading signatures before scan");
                    engine.downloadSignatures(null);
                } else {
                    engine.loadSignatures();
                }
            }

            if (!engine.signaturesReady()) {
                Log.e(TAG, "Signatures not available, aborting scan");
                return;
            }

            // Use full external storage for Option A
            File scanRoot = android.os.Environment.getExternalStorageDirectory();
            if (scanRoot == null || !scanRoot.exists()) {
                Log.e(TAG, "External storage not available");
                return;
            }

            JSONArray threats = new JSONArray();
            int scanned = 0;

            try {
                scanned = scanDir(scanRoot, engine, threats);
            } catch (Exception e) {
                Log.e(TAG, "ScanDir failed: " + e.getMessage(), e);
                // Write to file
                java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(getExternalFilesDir(null), "av_crash.log"), true);
                fw.write("Exception: " + e.toString() + "\n");
                for (StackTraceElement ste : e.getStackTrace()) {
                    fw.write("  " + ste.toString() + "\n");
                }
                fw.close();
            }

            Log.i(TAG, "Scan complete: scanned=" + scanned + " threats=" + threats.length());

            try {
                JSONObject payload = new JSONObject();
                payload.put("scan_type",     scanType);
                payload.put("files_scanned", scanned);
                payload.put("threats",       threats);

                RequestBody body = RequestBody.create(
                        payload.toString(), MediaType.parse("application/json"));
                Request req = new Request.Builder()
                        .url(flaskUrl + "/api/devices/" + serial + "/av/threats")
                        .post(body)
                        .build();
                client.newCall(req).execute().close();
                Log.i(TAG, "Pushed " + threats.length() + " threats to ZeroAxis");
            } catch (Exception e) {
                Log.e(TAG, "Push failed: " + e.getMessage());
            }

            getSharedPreferences("zeroaxis", MODE_PRIVATE).edit()
                    .putLong("av_last_scan", System.currentTimeMillis())
                    .putInt("av_threat_count", threats.length())
                    .putString("av_last_scan_type", scanType)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Fatal error in scan service: " + e.getMessage(), e);
            // Write to file
            try {
                java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(getExternalFilesDir(null), "av_crash.log"), true);
                fw.write("FATAL: " + e.toString() + "\n");
                for (StackTraceElement ste : e.getStackTrace()) {
                    fw.write("  " + ste.toString() + "\n");
                }
                fw.close();
            } catch (Exception ex) {}
        }
    }

    private int scanDir(File dir, AVEngine engine, JSONArray threats) {
        int count = 0;
        File[] files;
        try {
            files = dir.listFiles();
            if (files == null) {
                Log.w(TAG, "listFiles returned null for " + dir.getPath());
                return 0;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot list directory " + dir.getPath() + ": " + e.getMessage());
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error listing " + dir.getPath() + ": " + e.getMessage());
            return 0;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                try {
                    count += scanDir(f, engine, threats);
                } catch (Exception e) {
                    Log.e(TAG, "Error recursing into " + f.getPath() + ": " + e.getMessage());
                }
            } else {
                long size = f.length();
                if (size == 0 || size > MAX_FILE_SIZE) continue;
                try {
                    boolean detected = engine.checkFile(f);
                    count++;
                    if (detected) {
                        Log.w(TAG, "THREAT: " + f.getAbsolutePath());
                        String[] hashes = engine.hashFile(f);
                        JSONObject t = new JSONObject();
                        t.put("file_path",   f.getAbsolutePath());
                        t.put("threat_name", "Malware (Signature)");
                        t.put("hash",        hashes[2] != null ? hashes[2] : "");
                        threats.put(t);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Scan error on " + f.getName() + ": " + e.getMessage());
                }
            }
        }
        return count;
    }
}