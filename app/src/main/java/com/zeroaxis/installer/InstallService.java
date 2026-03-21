package com.zeroaxis.installer;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class InstallService extends Service {

    private static final String TAG = "ZeroAxisInstaller";
    private OkHttpClient client = new OkHttpClient();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String flaskUrl = intent.getStringExtra("flask_url");
        String districtId = intent.getStringExtra("district_id");
        String blockId = intent.getStringExtra("block_id");
        String schoolId = intent.getStringExtra("school_id");
        boolean headless = intent.getBooleanExtra("headless", false);

        new Thread(() -> {
            try {
                // Step 1 — Get device serial
                String serial = android.os.Build.SERIAL;
                if (serial == null || serial.equals("unknown")) {
                    serial = android.provider.Settings.Secure.getString(
                            getContentResolver(),
                            android.provider.Settings.Secure.ANDROID_ID);
                }

                // Step 2 — Check if already enrolled
                Request checkRequest = new Request.Builder()
                        .url(flaskUrl + "/api/devices/check/" + serial)
                        .build();
                Response checkResponse = client.newCall(checkRequest).execute();

                if (checkResponse.code() == 200) {
                    Log.i(TAG, "Device already enrolled, installing agents silently");
                    installAgents();
                    stopSelf();
                    return;
                }

                // Step 3 — Register device
                JSONObject payload = new JSONObject();
                payload.put("identifier", serial);
                payload.put("platform", "android");
                payload.put("name", android.os.Build.MODEL);

                if (districtId != null) payload.put("district_id", districtId);
                if (blockId != null) payload.put("block_id", blockId);
                if (schoolId != null) payload.put("school_id", schoolId);

                RequestBody body = RequestBody.create(
                        payload.toString(),
                        MediaType.parse("application/json"));

                Request registerRequest = new Request.Builder()
                        .url(flaskUrl + "/api/devices/register")
                        .post(body)
                        .build();

                Response registerResponse = client.newCall(registerRequest).execute();

                if (registerResponse.isSuccessful()) {
                    Log.i(TAG, "Device registered successfully");
                    installAgents();
                } else {
                    Log.e(TAG, "Registration failed: " + registerResponse.code());
                }

            } catch (Exception e) {
                Log.e(TAG, "Install error: " + e.getMessage());
            }

            stopSelf();
        }).start();

        return START_NOT_STICKY;
    }

    private void installAgents() {
        installApkFromAssets("headwind-agent.apk", "com.hmdm.launcher");
        installApkFromAssets("rustdesk-agent.apk", "com.carriez.flutter_hbb");
    }

    private void installApkFromAssets(String assetName, String packageName) {
        try {
            // Copy APK from assets to internal storage
            InputStream in = getAssets().open(assetName);
            File outFile = new File(getFilesDir(), assetName);
            OutputStream out = new FileOutputStream(outFile);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.close();

            // Try root install first
            boolean rootSuccess = installWithRoot(outFile.getAbsolutePath());

            if (!rootSuccess) {
                // Fallback — prompt user
                Log.i(TAG, "Root install failed, falling back to prompt for " + packageName);
                installWithPrompt(outFile);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to install " + assetName + ": " + e.getMessage());
        }
    }

    private boolean installWithRoot(String apkPath) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            OutputStream os = process.getOutputStream();
            os.write(("pm install -r " + apkPath + "\n").getBytes());
            os.write("exit\n".getBytes());
            os.flush();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "Root install error: " + e.getMessage());
            return false;
        }
    }

    private void installWithPrompt(File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(this,
                    "com.zeroaxis.installer.fileprovider", apkFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Prompt install error: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}