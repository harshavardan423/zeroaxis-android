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
    private List<JSONObject> blocks    = new ArrayList<>();
    private List<JSONObject> schools   = new ArrayList<>();
    private OkHttpClient client = new OkHttpClient();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // Tracks which install step we're on (0 = not started)
    private int currentStep = 0;
    // Steps: 1=WireGuard APK, 2=VPN import, 3=Headwind APK, 4=ADB instructions
    private static final int STEP_WIREGUARD_APK = 1;
    private static final int STEP_VPN_IMPORT    = 2;
    private static final int STEP_HEADWIND_APK  = 3;
    private static final int STEP_ADB           = 4;
    private static final int STEP_DONE          = 5;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        // Enroll button — first tap registers device and starts step flow
        enrollButton.setOnClickListener(v -> {
            if (currentStep == 0) {
                // First tap — validate and register
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
                    registerAndBegin(districtId, blockId, schoolId);
                } catch (Exception e) {
                    Toast.makeText(this, "Error reading selection", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Subsequent taps — advance to next step
                advanceStep();
            }
        });
    }

    // ─── Hierarchy loaders ───────────────────────────────────────────────────

    private void loadHierarchy() {
        statusText.setText("Loading...");
        new Thread(() -> {
            try {
                Request request = new Request.Builder().url(flaskUrl + "/api/groups").build();
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

    // ─── Registration ─────────────────────────────────────────────────────────

    private void registerAndBegin(String districtId, String blockId, String schoolId) {
        enrollButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        setStatus("Registering device...");

        // Hide spinners during install flow
        districtSpinner.setVisibility(View.GONE);
        blockSpinner.setVisibility(View.GONE);
        schoolSpinner.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                String rawSerial = android.os.Build.SERIAL;
                if (rawSerial == null || rawSerial.equals("unknown")) {
                    rawSerial = android.provider.Settings.Secure.getString(
                            getContentResolver(),
                            android.provider.Settings.Secure.ANDROID_ID);
                }
                final String serial = rawSerial;

                // Check if already enrolled
                Request checkRequest = new Request.Builder()
                        .url(flaskUrl + "/api/devices/check/" + serial).build();
                Response checkResponse = client.newCall(checkRequest).execute();
                boolean alreadyEnrolled = (checkResponse.code() == 200);

                if (!alreadyEnrolled) {
                    JSONObject payload = new JSONObject();
                    payload.put("serial", serial);
                    payload.put("platform", "android");
                    payload.put("name", android.os.Build.MODEL);
                    if (districtId != null) payload.put("district_id", Integer.parseInt(districtId));
                    if (blockId != null)    payload.put("block_id",    Integer.parseInt(blockId));
                    if (schoolId != null)   payload.put("school_id",   Integer.parseInt(schoolId));

                    okhttp3.RequestBody body = okhttp3.RequestBody.create(
                            payload.toString(), okhttp3.MediaType.parse("application/json"));

                    Request registerRequest = new Request.Builder()
                            .url(flaskUrl + "/api/devices/register").post(body).build();
                    Response registerResponse = client.newCall(registerRequest).execute();

                    if (!registerResponse.isSuccessful()) {
                        setStatus("Registration failed: " + registerResponse.body().string());
                        mainHandler.post(() -> {
                            enrollButton.setEnabled(true);
                            progressBar.setVisibility(View.GONE);
                        });
                        return;
                    }
                }

                // Registration done — start step flow on main thread
                mainHandler.post(() -> {
                    currentStep = STEP_WIREGUARD_APK;
                    progressBar.setVisibility(View.GONE);
                    runStep(currentStep);
                });

            } catch (Exception e) {
                setStatus("Error: " + e.getMessage());
                mainHandler.post(() -> {
                    enrollButton.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    // ─── Step runner ──────────────────────────────────────────────────────────

    /**
     * Called on the main thread. Executes the current step action, then
     * updates the button to say "Next →" so the user can confirm and proceed.
     */
    private void runStep(int step) {
        switch (step) {

            case STEP_WIREGUARD_APK:
                setStatus("Step 1 of 4 — Installing WireGuard\n\nApprove the install prompt, then tap Next.");
                enrollButton.setText("Next →");
                enrollButton.setEnabled(true);
                new Thread(() -> installApk("wireguard.apk")).start();
                break;

            case STEP_VPN_IMPORT:
                setStatus("Step 2 of 4 — Importing VPN config\n\nWireGuard will open. Tap the checkmark/import button inside it, then come back and tap Next.");
                enrollButton.setText("Next →");
                enrollButton.setEnabled(true);
                new Thread(() -> importWireGuardTunnel()).start();
                break;

            case STEP_HEADWIND_APK:
                setStatus("Step 3 of 4 — Installing MDM Agent\n\nApprove the install prompt, then tap Next.");
                enrollButton.setText("Next →");
                enrollButton.setEnabled(true);
                new Thread(() -> installApk("headwind-agent.apk")).start();
                break;

            case STEP_ADB:
                if (oem) {
                    // OEM: do it silently, no user action needed
                    setStatus("Step 4 of 4 — Enabling remote access...");
                    enrollButton.setEnabled(false);
                    new Thread(() -> {
                        configureAdbRoot();
                        mainHandler.post(() -> {
                            currentStep = STEP_DONE;
                            runStep(STEP_DONE);
                        });
                    }).start();
                } else {
                    // Test phone: show instructions, let user read and tap Next
                    setStatus("Step 4 of 4 — Enable Wireless Debugging\n\n" +
                            "1. Open Settings → Developer Options\n" +
                            "2. Enable Wireless Debugging\n" +
                            "3. Enable Stay Awake\n\n" +
                            "Then tap Done below.");
                    enrollButton.setText("Done ✓");
                    enrollButton.setEnabled(true);
                }
                break;

            case STEP_DONE:
                setStatus("✓ Device enrolled!\n✓ WireGuard VPN configured\n✓ MDM agent installed\n" +
                        (oem ? "✓ Remote access enabled" : "✓ Enable Wireless Debugging to activate remote access"));
                enrollButton.setText("Finish");
                enrollButton.setEnabled(true);
                break;
        }
    }

    /**
     * User tapped Next/Done/Finish — advance to the next step.
     */
    private void advanceStep() {
        currentStep++;
        if (currentStep > STEP_DONE) return;
        runStep(currentStep);
    }

    // ─── Step: Install APK ────────────────────────────────────────────────────

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

            if (tryRootInstall(outFile)) return; // silent succeeded

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

    // ─── Step: WireGuard VPN import ───────────────────────────────────────────

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
                // Root path
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
                // Official WireGuard import Intent — opens WireGuard app, user taps import
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

    // ─── Step: ADB (OEM root path) ────────────────────────────────────────────

    private void configureAdbRoot() {
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
        } catch (Exception e) {
            setStatus("ADB setup failed: " + e.getMessage());
        }
    }

    // ─── Headless flow (no UI) ────────────────────────────────────────────────

    private void startInstall(String districtId, String blockId, String schoolId) {
        new Thread(() -> {
            try {
                String rawSerial = android.os.Build.SERIAL;
                if (rawSerial == null || rawSerial.equals("unknown")) {
                    rawSerial = android.provider.Settings.Secure.getString(
                            getContentResolver(),
                            android.provider.Settings.Secure.ANDROID_ID);
                }

                Request checkRequest = new Request.Builder()
                        .url(flaskUrl + "/api/devices/check/" + rawSerial).build();
                Response checkResponse = client.newCall(checkRequest).execute();

                if (checkResponse.code() != 200) {
                    JSONObject payload = new JSONObject();
                    payload.put("serial", rawSerial);
                    payload.put("platform", "android");
                    payload.put("name", android.os.Build.MODEL);

                    okhttp3.RequestBody body = okhttp3.RequestBody.create(
                            payload.toString(), okhttp3.MediaType.parse("application/json"));
                    Request registerRequest = new Request.Builder()
                            .url(flaskUrl + "/api/devices/register").post(body).build();
                    client.newCall(registerRequest).execute();
                }

                installApk("wireguard.apk");
                Thread.sleep(3000);
                importWireGuardTunnel();
                Thread.sleep(2000);
                installApk("headwind-agent.apk");
                Thread.sleep(3000);
                if (oem) configureAdbRoot();

            } catch (Exception e) {
                // Headless — nothing to show
            }
        }).start();
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private void setStatus(String msg) {
        mainHandler.post(() -> {
            if (statusText != null) statusText.setText(msg);
        });
    }
}