package com.zeroaxis.agent;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class EnrollmentActivity extends AppCompatActivity {

    private static final int REQ_LOCATION      = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    

    private String flaskUrl;
    private boolean headless;
    private boolean oem;

    private Spinner districtSpinner, blockSpinner, schoolSpinner;
    private Button enrollButton;
    private ProgressBar progressBar;
    private TextView statusText;

    private List<JSONObject> districts = new ArrayList<>();
    private List<JSONObject> blocks    = new ArrayList<>();
    private List<JSONObject> schools   = new ArrayList<>();

    private OkHttpClient client = new OkHttpClient();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private String deviceNameOverride = "";

    // Enrollment state machine
    // 0=idle, 1=registered, 2=location, 3=usage_access, 4=device_admin, 5=done
    private int enrollStep = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load config.json from assets
        try {
            InputStream is = getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            JSONObject cfg = new JSONObject(new String(buf));
            flaskUrl = cfg.getString("flask_url");
            headless = cfg.optBoolean("headless", false);
            oem      = cfg.optBoolean("oem", false);
        } catch (Exception e) {
            flaskUrl = "https://zeroaxis.live";
            headless = false;
            oem      = false;
        }

        if (headless) {
            headlessEnroll();
            finish();
            return;
        }

        // Already enrolled — just restart the service and exit
        String savedSerial = getSharedPreferences("zeroaxis", MODE_PRIVATE)
                .getString("serial", null);
        if (savedSerial != null) {
            ContextCompat.startForegroundService(this, new Intent(this, AgentService.class));
            finish();
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

        // Add device name field above enroll button
        android.widget.EditText nameField = new android.widget.EditText(this);
        nameField.setHint("Device name (optional, defaults to model)");
        nameField.setText(android.os.Build.MODEL);
        nameField.setId(android.view.View.generateViewId());
        // Insert before enroll button in layout
        android.view.ViewGroup parent = (android.view.ViewGroup) enrollButton.getParent();
        int idx = parent.indexOfChild(enrollButton);
        android.widget.LinearLayout.LayoutParams lp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 16, 0, 0);
        nameField.setLayoutParams(lp);
        parent.addView(nameField, idx);

        enrollButton.setOnClickListener(v -> {
            deviceNameOverride = nameField.getText().toString().trim();
            handleEnrollTap();
        });
    }

    // ─── Hierarchy loaders ───────────────────────────────────────────────────

    private void loadHierarchy() {
        statusText.setText("Connecting to server...");
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(flaskUrl + "/api/groups").build();
                Response res = client.newCall(req).execute();
                String body = res.body().string();
                JSONArray arr = new JSONArray(body);
                districts.clear();
                List<String> names = new ArrayList<>();
                names.add("Select District");
                for (int i = 0; i < arr.length(); i++) {
                    districts.add(arr.getJSONObject(i));
                    names.add(arr.getJSONObject(i).getString("name"));
                }
                mainHandler.post(() -> {
                    ArrayAdapter<String> a = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, names);
                    a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    districtSpinner.setAdapter(a);
                    statusText.setText("Select your location to enroll this device");
                });
            } catch (Exception e) {
                mainHandler.post(() -> statusText.setText("Error connecting to server. Check network."));
            }
        }).start();
    }

    private void populateBlocks(int distIdx) {
        try {
            JSONArray arr = districts.get(distIdx).getJSONArray("blocks");
            blocks.clear();
            List<String> names = new ArrayList<>();
            names.add("Select Block");
            for (int i = 0; i < arr.length(); i++) {
                blocks.add(arr.getJSONObject(i));
                names.add(arr.getJSONObject(i).getString("name"));
            }
            ArrayAdapter<String> a = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, names);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            blockSpinner.setAdapter(a);
            schoolSpinner.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, new String[]{"Select School"}));
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void populateSchools(int blockIdx) {
        try {
            JSONArray arr = blocks.get(blockIdx).getJSONArray("schools");
            schools.clear();
            List<String> names = new ArrayList<>();
            names.add("Select School");
            for (int i = 0; i < arr.length(); i++) {
                schools.add(arr.getJSONObject(i));
                names.add(arr.getJSONObject(i).getString("name"));
            }
            ArrayAdapter<String> a = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, names);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            schoolSpinner.setAdapter(a);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ─── Enroll button state machine ─────────────────────────────────────────

    private void handleEnrollTap() {
        switch (enrollStep) {
            case 0: startRegistration(); break;
            case 1: requestNotificationPermission(); break;
            case 2: openUsageAccessSettings(); break;
            case 3: requestDeviceAdmin(); break;
            case 4: finishEnrollment(); break;
        }
    }

    private void startRegistration() {
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

            enrollButton.setEnabled(false);
            progressBar.setVisibility(View.VISIBLE);
            districtSpinner.setVisibility(View.GONE);
            blockSpinner.setVisibility(View.GONE);
            schoolSpinner.setVisibility(View.GONE);
            statusText.setText("Registering device...");

            new Thread(() -> {
                try {
                    String serial = getSerial();
                    saveSerial(serial);

                    // Check if already enrolled
                    Request check = new Request.Builder()
                            .url(flaskUrl + "/api/devices/check/" + serial).build();
                    Response checkRes = client.newCall(check).execute();

                    if (checkRes.code() != 200) {
                        JSONObject payload = new JSONObject();
                        payload.put("serial", serial);
                        payload.put("platform", "android");
                        String dName = (deviceNameOverride != null && !deviceNameOverride.isEmpty())
                                ? deviceNameOverride : android.os.Build.MODEL;
                        payload.put("name", dName);
                        payload.put("district_id", Integer.parseInt(districtId));
                        payload.put("block_id",    Integer.parseInt(blockId));
                        payload.put("school_id",   Integer.parseInt(schoolId));

                        RequestBody body = RequestBody.create(
                                payload.toString(), MediaType.parse("application/json"));
                        Request reg = new Request.Builder()
                                .url(flaskUrl + "/api/devices/register").post(body).build();
                        Response regRes = client.newCall(reg).execute();

                        if (!regRes.isSuccessful()) {
                            String err = regRes.body().string();
                            mainHandler.post(() -> {
                                statusText.setText("Registration failed: " + err);
                                enrollButton.setEnabled(true);
                                progressBar.setVisibility(View.GONE);
                            });
                            return;
                        }
                    }

                    // Silently install Headwind and enroll in background
                    final String serialFinal = serial;
                    final String flaskUrlFinal = flaskUrl;
                    new Thread(() -> {
                        installHeadwindSilent();
                        enrollInHeadwind(serialFinal, flaskUrlFinal);
                    }).start();

                    mainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        enrollStep = 1;
                        statusText.setText("Device registered!\n\nNext: grant Location permission.");
                        enrollButton.setText("Grant Location →");
                        enrollButton.setEnabled(true);
                    });

                } catch (Exception e) {
                    mainHandler.post(() -> {
                        statusText.setText("Error: " + e.getMessage());
                        enrollButton.setEnabled(true);
                        progressBar.setVisibility(View.GONE);
                    });
                }
            }).start();

        } catch (Exception e) {
            Toast.makeText(this, "Error reading selection", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                return;
            }
        }
        // Already granted or below Android 13 — move to location
        requestLocationPermission();
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            enrollStep = 2;
            handleEnrollTap();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATIONS) {
            requestLocationPermission();
        } else if (requestCode == REQ_LOCATION) {
            enrollStep = 2;
            openUsageAccessSettings();
        }
    }

    private void openUsageAccessSettings() {
        if (UsageStatsHelper.hasPermission(this)) {
            enrollStep = 3;
            requestDeviceAdmin();
            return;
        }
        statusText.setText("Step 2 of 3 — Usage Access\n\n" +
                "1. Find ZeroAxis Agent in the list\n" +
                "2. Enable it\n" +
                "3. Come back and tap Next");
        enrollButton.setText("Open Usage Settings →");
        enrollButton.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            enrollButton.setText("Next →");
            enrollButton.setOnClickListener(v2 -> {
                enrollStep = 3;
                requestDeviceAdmin();
            });
        });
        enrollStep = 2;
    }

    private void requestDeviceAdmin() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, ZeroAxisAdminReceiver.class);
        if (dpm.isAdminActive(admin)) {
            enrollStep = 4;
            finishEnrollment();
            return;
        }
        statusText.setText("Step 3 of 3 — Device Admin\n\n" +
                "Required to lock screen remotely.\nTap Activate in the next screen.");
        enrollButton.setText("Activate Device Admin →");
        enrollButton.setOnClickListener(v -> {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required for ZeroAxis remote management");
            startActivity(intent);
            enrollStep = 4;
            enrollButton.setText("Finish →");
            enrollButton.setOnClickListener(v2 -> finishEnrollment());
        });
        enrollStep = 3;
    }

    private void finishEnrollment() {
        // Start AgentService
        Intent svc = new Intent(this, AgentService.class);
        ContextCompat.startForegroundService(this, svc);

        statusText.setText("✓ Device enrolled!\n✓ Location permission set\n" +
                "✓ Usage access configured\n✓ Agent service started\n\n" +
                "You can close this app.");
        enrollButton.setText("Done");
        enrollButton.setEnabled(true);
        enrollButton.setOnClickListener(v -> finish());
        enrollStep = 5;
    }

    private void installHeadwindSilent() {
        try {
            java.io.InputStream in = getAssets().open("headwind-agent.apk");
            java.io.File outFile = new java.io.File(getFilesDir(), "headwind-agent.apk");
            java.io.OutputStream out = new java.io.FileOutputStream(outFile);
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            in.close(); out.close();

            // Try root/silent install first
            try {
                Process p = Runtime.getRuntime().exec("su");
                java.io.OutputStream os = p.getOutputStream();
                os.write(("pm install -r " + outFile.getAbsolutePath() + "\n").getBytes());
                os.write("exit\n".getBytes());
                os.flush();
                p.waitFor();
            } catch (Exception ignored) {
                // Root not available — show install prompt as fallback
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", outFile);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Exception e) {
            // headwind-agent.apk not in assets — skip silently
        }
    }

    private void enrollInHeadwind(String serial, String flaskUrl) {
        try {
            // Wait for Headwind agent to finish installing
            Thread.sleep(15000);

            JSONObject payload = new JSONObject();
            payload.put("serial", serial);
            RequestBody body = RequestBody.create(
                    payload.toString(), MediaType.parse("application/json"));
            Request req = new Request.Builder()
                    .url(flaskUrl + "/api/headwind/enroll")
                    .post(body).build();
            Response res = client.newCall(req).execute();
            if (res.isSuccessful()) {
                JSONObject resp = new JSONObject(res.body().string());
                String hmdmId = resp.optString("hmdm_id", "");
                if (!hmdmId.isEmpty()) {
                    getSharedPreferences("zeroaxis", MODE_PRIVATE)
                            .edit().putString("hmdm_id", hmdmId).apply();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─── Headless mode ───────────────────────────────────────────────────────

    private void headlessEnroll() {
        new Thread(() -> {
            try {
                String serial = getSerial();
                saveSerial(serial);

                Request check = new Request.Builder()
                        .url(flaskUrl + "/api/devices/check/" + serial).build();
                Response checkRes = client.newCall(check).execute();

                if (checkRes.code() != 200) {
                    JSONObject payload = new JSONObject();
                    payload.put("serial", serial);
                    payload.put("platform", "android");
                    String dName = (deviceNameOverride != null && !deviceNameOverride.isEmpty())
                            ? deviceNameOverride : android.os.Build.MODEL;
                    payload.put("name", dName);
                    RequestBody body = RequestBody.create(
                            payload.toString(), MediaType.parse("application/json"));
                    Request reg = new Request.Builder()
                            .url(flaskUrl + "/api/devices/register").post(body).build();
                    client.newCall(reg).execute();
                }

                Intent svc = new Intent(this, AgentService.class);
                ContextCompat.startForegroundService(this, svc);

            } catch (Exception ignored) {}
        }).start();
    }

    // ─── Utilities ───────────────────────────────────────────────────────────

    private String getSerial() {
        String serial = android.os.Build.SERIAL;
        if (serial == null || serial.equals("unknown")) {
            serial = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ANDROID_ID);
        }
        return serial;
    }

    private void saveSerial(String serial) {
        getSharedPreferences("zeroaxis", MODE_PRIVATE)
                .edit().putString("serial", serial).apply();
    }
}
