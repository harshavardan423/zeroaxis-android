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

    private static final int REQ_LOCATION = 1001;

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

        enrollButton.setOnClickListener(v -> handleEnrollTap());
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
            case 1: requestLocationPermission(); break;
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
                        payload.put("name", android.os.Build.MODEL);
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
        if (requestCode == REQ_LOCATION) {
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
                    payload.put("name", android.os.Build.MODEL);
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
