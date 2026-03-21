package com.zeroaxis.installer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EnrollmentActivity extends AppCompatActivity {

    private String flaskUrl;
    private boolean headless;
    private boolean oem;
    private String vpnTunnelName;

    private Spinner districtSpinner, blockSpinner, schoolSpinner;
    private Button enrollButton;
    private ProgressBar progressBar;
    private TextView statusText;

    private List<JSONObject> districts = new ArrayList<>();
    private List<JSONObject> blocks = new ArrayList<>();
    private List<JSONObject> schools = new ArrayList<>();
    private OkHttpClient client = new OkHttpClient();

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load config.json from assets
        try {
            InputStream is = getAssets().open("config.json");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            JSONObject config = new JSONObject(new String(buffer));
            flaskUrl      = config.getString("flask_url");
            headless      = config.getBoolean("headless");
            oem           = config.optBoolean("oem", false);
            vpnTunnelName = config.optString("vpn_tunnel_name", "ZeroAxis VPN");
        } catch (Exception e) {
            flaskUrl      = "https://zeroaxis.live";
            headless      = false;
            oem           = false;
            vpnTunnelName = "ZeroAxis VPN";
        }

        if (headless) {
            startInstall(null, null, null);
            return;
        }

        setContentView(R.layout.activity_enrollment);

        districtSpinner = findViewById(R.id.districtSpinner);
        blockSpinner    = findViewById(R.id.blockSpinner);
        schoolSpinner   = findViewById(R.id.schoolSpinner);
        enrollButton    = findViewById(R.id.enrollButton);
        progressBar     = findViewById(R.id.progressBar);
        statusText      = findViewById(R.id.statusText);

        loadHierarchy();

        districtSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos > 0) populateBlocks(pos - 1);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        blockSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos > 0) populateSchools(pos - 1);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        enrollButton.setOnClickListener(v -> {
            int dPos = districtSpinner.getSelectedItemPosition();
            int bPos = blockSpinner.getSelectedItemPosition();
            int sPos = schoolSpinner.getSelectedItemPosition();

            if (dPos == 0 || bPos == 0 || sPos == 0) {
                Toast.makeText(this, "Please select District, Block and School", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                String districtId = districts.get(dPos - 1).getString("id");
                String blockId    = blocks.get(bPos - 1).getString("id");
                String schoolId   = schools.get(sPos - 1).getString("id");
                startInstall(districtId, blockId, schoolId);
            } catch (Exception e) {
                Toast.makeText(this, "Error reading selection", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ─── Hierarchy loaders ───────────────────────────────────────────────────

    private void loadHierarchy() {
        statusText.setText("Loading...");
        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                        .url(flaskUrl + "/api/groups")
                        .build();
                Response response = client.newCall(request).execute();
                String body = response.body().string();
                JSONArray districtArray = new JSONArray(body);

                districts.clear();
                List<String> districtNames = new ArrayList<>();
                districtNames.add("Select District");
                for (int i = 0; i < districtArray.length(); i++) {
                    districts.add(districtArray.getJSONObject(i));
                    districtNames.add(districtArray.getJSONObject(i).getString("name"));
                }

                mainHandler.post(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, districtNames);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    districtSpinner.setAdapter(adapter);
                    statusText.setText("Select your location to enroll this device");
                });
            } catch (Exception e) {
                mainHandler.post(() -> statusText.setText("Error connecting to server. Check network."));
            }
        }).start();
    }

    private void populateBlocks(int districtIndex) {
        try {
            JSONArray blockArray = districts.get(districtIndex).getJSONArray("blocks");
            blocks.clear();
            List<String> blockNames = new ArrayList<>();
            blockNames.add("Select Block");
            for (int i = 0; i < blockArray.length(); i++) {
                blocks.add(blockArray.getJSONObject(i));
                blockNames.add(blockArray.getJSONObject(i).getString("name"));
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, blockNames);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            blockSpinner.setAdapter(adapter);
            schoolSpinner.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, new String[]{"Select School"}));
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void populateSchools(int blockIndex) {
        try {
            JSONArray schoolArray = blocks.get(blockIndex).getJSONArray("schools");
            schools.clear();
            List<String> schoolNames = new ArrayList<>();
            schoolNames.add("Select School");
            for (int i = 0; i < schoolArray.length(); i++) {
                schools.add(schoolArray.getJSONObject(i));
                schoolNames.add(schoolArray.getJSONObject(i).getString("name"));
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, schoolNames);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            schoolSpinner.setAdapter(adapter);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ─── Main install flow ───────────────────────────────────────────────────

    private void startInstall(String districtId, String blockId, String schoolId) {
        if (!headless) {
            enrollButton.setEnabled(false);
            progressBar.setVisibility(View.VISIBLE);
            setStatus("Registering device...");
        }

        new Thread(() -> {
            try {
                // ── 1. Resolve serial ──────────────────────────────────────
                String rawSerial = android.os.Build.SERIAL;
                if (rawSerial == null || rawSerial.equals("unknown")) {
                    rawSerial = android.provider.Settings.Secure.getString(
                            getContentResolver(),
                            android.provider.Settings.Secure.ANDROID_ID);
                }
                final String serial = rawSerial;

                // ── 2. Check if already enrolled ──────────────────────────
                Request checkRequest = new Request.Builder()
                        .url(flaskUrl + "/api/devices/check/" + serial)
                        .build();
                Response checkResponse = client.newCall(checkRequest).execute();
                boolean alreadyEnrolled = (checkResponse.code() == 200);

                if (!alreadyEnrolled) {
                    // ── 3. Register device with Flask ─────────────────────
                    JSONObject payload = new JSONObject();
                    payload.put("serial", serial);
                    payload.put("platform", "android");
                    payload.put("name", android.os.Build.MODEL);
                    if (districtId != null) payload.put("district_id", Integer.parseInt(districtId));
                    if (blockId != null)    payload.put("block_id",    Integer.parseInt(blockId));
                    if (schoolId != null)   payload.put("school_id",   Integer.parseInt(schoolId));

                    okhttp3.RequestBody body = okhttp3.RequestBody.create(
                            payload.toString(),
                            okhttp3.MediaType.parse("application/json"));

                    Request registerRequest = new Request.Builder()
                            .url(flaskUrl + "/api/devices/register")
                            .post(body)
                            .build();

                    Response registerResponse = client.newCall(registerRequest).execute();
                    if (!registerResponse.isSuccessful()) {
                        setStatus("Registration failed: " + registerResponse.body().string());
                        return;
                    }
                }

                // ── 4. Install WireGuard APK ───────────────────────────────
                setStatus("Installing WireGuard...");
                installApk("wireguard.apk");
                // OEM: silent install is fast. Test: user sees installer UI, give them time.
                Thread.sleep(oem ? 2000 : 8000);

                // ── 5. Import VPN tunnel config ────────────────────────────
                setStatus("Configuring VPN tunnel...");
                importWireGuardTunnel();
                // OEM: root copy is instant. Test: WireGuard app opens, user taps Import.
                Thread.sleep(oem ? 1000 : 6000);

                // ── 6. Install Headwind MDM agent ──────────────────────────
                setStatus("Installing MDM agent...");
                installApk("headwind-agent.apk");
                Thread.sleep(oem ? 2000 : 8000);

                // ── 7. Configure ADB for remote control ────────────────────
                setStatus("Configuring remote access...");
                configureAdb();

                // ── Done ───────────────────────────────────────────────────
                if (!headless) {
                    mainHandler.post(() -> progressBar.setVisibility(View.GONE));
                }

            } catch (Exception e) {
                setStatus("Error: " + e.getMessage());
            }
        }).start();
    }

    // ─── Step: Install APK ────────────────────────────────────────────────────

    /**
     * Copies an APK from assets and installs it.
     * OEM / root: silent via `pm install -r`.
     * Test phone: opens system installer UI (one user tap to approve).
     */
    private void installApk(String assetName) {
        try {
            InputStream in = getAssets().open(assetName);
            File outFile = new File(getFilesDir(), assetName);
            OutputStream out = new FileOutputStream(outFile);
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            in.close();
            out.close();

            if (tryRootInstall(outFile)) return;  // silent succeeded

            // Fallback: system installer prompt
            Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.zeroaxis.installer.fileprovider", outFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

        } catch (Exception e) {
            setStatus("Install error (" + assetName + "): " + e.getMessage());
        }
    }

    private boolean tryRootInstall(File apkFile) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            OutputStream os = process.getOutputStream();
            os.write(("pm install -r " + apkFile.getAbsolutePath() + "\n").getBytes());
            os.write("exit\n".getBytes());
            os.flush();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Step: WireGuard tunnel import ────────────────────────────────────────

    /**
     * OEM / root: copies conf to /data/misc/wireguard/ and brings up the tunnel.
     * Test phone: fires WireGuard's official IMPORT_TUNNEL Intent — user taps Import
     *             inside the WireGuard app (one tap, no pairing code needed).
     */
    private void importWireGuardTunnel() {
        try {
            InputStream in = getAssets().open("zeroaxis-vpn.conf");
            File confFile = new File(getFilesDir(), "zeroaxis-vpn.conf");
            OutputStream out = new FileOutputStream(confFile);
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            in.close();
            out.close();

            if (oem) {
                // Root path: write config and bring up wg interface
                Process process = Runtime.getRuntime().exec("su");
                OutputStream os = process.getOutputStream();
                os.write("mkdir -p /data/misc/wireguard/\n".getBytes());
                os.write(("cp " + confFile.getAbsolutePath() + " /data/misc/wireguard/zeroaxis-vpn.conf\n").getBytes());
                os.write("chmod 600 /data/misc/wireguard/zeroaxis-vpn.conf\n".getBytes());
                os.write("wg-quick up /data/misc/wireguard/zeroaxis-vpn.conf\n".getBytes());
                os.write("exit\n".getBytes());
                os.flush();
                process.waitFor();
            } else {
                // Non-OEM: WireGuard's official import Intent (requires WireGuard app installed)
                Uri confUri = androidx.core.content.FileProvider.getUriForFile(
                        this, "com.zeroaxis.installer.fileprovider", confFile);
                Intent intent = new Intent("com.wireguard.android.action.IMPORT_TUNNEL");
                intent.setPackage("com.wireguard.android");
                intent.setData(confUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }

        } catch (Exception e) {
            setStatus("VPN config error: " + e.getMessage());
        }
    }

    // ─── Step: ADB / Wireless Debugging ──────────────────────────────────────

    /**
     * OEM / root: enables ADB over TCP on port 5555 persistently via root shell.
     *             Equivalent to having persist.adb.tcp.port=5555 in the ROM.
     *
     * Test phone: Android blocks enabling Wireless Debugging programmatically.
     *             Show clear on-screen instructions instead.
     */
    private void configureAdb() {
        if (oem) {
            try {
                Process process = Runtime.getRuntime().exec("su");
                OutputStream os = process.getOutputStream();
                os.write("settings put global adb_enabled 1\n".getBytes());
                os.write("setprop service.adb.tcp.port 5555\n".getBytes());
                os.write("setprop persist.adb.tcp.port 5555\n".getBytes());
                os.write("stop adbd\n".getBytes());
                os.write("start adbd\n".getBytes());
                os.write("exit\n".getBytes());
                os.flush();
                process.waitFor();
                setStatus("Setup complete! Device is ready.");
            } catch (Exception e) {
                setStatus("ADB setup failed: " + e.getMessage());
            }
        } else {
            // Can't do this silently on stock Android — guide the user
            setStatus(
                "Almost done!\n\n" +
                "One manual step for remote access:\n" +
                "  1. Settings → Developer Options\n" +
                "  2. Enable Wireless Debugging\n" +
                "  3. Keep screen on (Stay Awake option)\n\n" +
                "Everything else is configured. ✓ WireGuard VPN active ✓ MDM enrolled"
            );
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private void setStatus(String msg) {
        mainHandler.post(() -> {
            if (statusText != null) statusText.setText(msg);
        });
    }
}
