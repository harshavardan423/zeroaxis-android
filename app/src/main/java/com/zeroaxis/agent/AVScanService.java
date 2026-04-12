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
        if (intent == null) return;

        String scanType = intent.getStringExtra(EXTRA_SCAN_TYPE);
        String flaskUrl = intent.getStringExtra(EXTRA_FLASK_URL);
        String serial   = intent.getStringExtra(EXTRA_SERIAL);
        if (scanType == null) scanType = "quick";

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

        File[] roots = AVEngine.getScanRoots();
        File[] scanRoots;
        if ("quick".equals(scanType)) {
            scanRoots = new File[]{
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
            };
        } else {
            scanRoots = roots;
        }

        JSONArray threats = new JSONArray();
        int scanned = 0;

        for (File root : scanRoots) {
            if (root == null || !root.exists()) continue;
            scanned += scanDir(root, engine, threats);
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
    }

    private int scanDir(File dir, AVEngine engine, JSONArray threats) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += scanDir(f, engine, threats);
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