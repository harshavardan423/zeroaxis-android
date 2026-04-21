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
import java.io.File;

public class EnrollmentActivity extends AppCompatActivity {

    private static final int REQ_LOCATION      = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static String DEBUG_LOG = null;

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

    private int enrollStep = 0;

    private void log(String msg) {
        if (DEBUG_LOG == null) return;
        try {
            java.io.FileWriter fw = new java.io.FileWriter(DEBUG_LOG, true);
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " - " + msg + "\n");
            fw.close();
        } catch (Exception e) { }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialize log path
        File logDir = getExternalFilesDir(null);
        if (logDir != null) {
            DEBUG_LOG = new File(logDir, "zeroaxis_debug.log").getAbsolutePath();
        } else {
            DEBUG_LOG = getFilesDir() + "/zeroaxis_debug.log";
        }
        log("EnrollmentActivity onCreate");

        try {
            InputStream is = getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            JSONObject cfg = new JSONObject(new String(buf));
            flaskUrl = cfg.getString("flask_url");
            headless = cfg.optBoolean("headless", false);
            oem      = cfg.optBoolean("oem", false);
            log("Config loaded: flaskUrl=" + flaskUrl + " headless=" + headless);
        } catch (Exception e) {
            flaskUrl = "https://zeroaxis.live";
            headless = false;
            oem      = false;
            log("Config error: " + e.getMessage());
        }

        if (headless) {
            log("Headless mode, enrolling");
            headlessEnroll();
            finish();
            return;
        }

        String savedSerial = getSharedPreferences("zeroaxis", MODE_PRIVATE)
                .getString("serial", null);
        if (savedSerial != null) {
            log("Already enrolled, starting service");
            ContextCompat.startForegroundService(this, new Intent(this, AgentService.class));
            startActivity(new Intent(this, LoginActivity.class));
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
                saveSelections();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        blockSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos > 0) populateSchools(pos - 1);
                saveSelections();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        schoolSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                saveSelections();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // Add device name field
        android.widget.EditText nameField = new android.widget.EditText(this);
        nameField.setHint("Device name (optional, defaults to model)");
        nameField.setText(android.os.Build.MODEL);
        nameField.setId(android.view.View.generateViewId());
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

    private void saveSelections() {
        SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
        prefs.edit()
            .putInt("selected_district", districtSpinner.getSelectedItemPosition())
            .putInt("selected_block", blockSpinner.getSelectedItemPosition())
            .putInt("selected_school", schoolSpinner.getSelectedItemPosition())
            .apply();
        log("Selections saved");
    }

    private void restoreSelections() {
        SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
        int distPos = prefs.getInt("selected_district", 0);
        int blockPos = prefs.getInt("selected_block", 0);
        int schoolPos = prefs.getInt("selected_school", 0);
        if (distPos > 0 && districtSpinner.getAdapter() != null && districtSpinner.getAdapter().getCount() > distPos) {
            districtSpinner.setSelection(distPos);
            log("Restored district selection: " + distPos);
        }
        if (blockPos > 0 && blockSpinner.getAdapter() != null && blockSpinner.getAdapter().getCount() > blockPos) {
            blockSpinner.setSelection(blockPos);
            log("Restored block selection: " + blockPos);
        }
        if (schoolPos > 0 && schoolSpinner.getAdapter() != null && schoolSpinner.getAdapter().getCount() > schoolPos) {
            schoolSpinner.setSelection(schoolPos);
            log("Restored school selection: " + schoolPos);
        }
    }

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
                    restoreSelections();
                });
            } catch (Exception e) {
                log("loadHierarchy error: " + e.getMessage());
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
        } catch (Exception e) { log("populateBlocks error: " + e.getMessage()); e.printStackTrace(); }
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
        } catch (Exception e) { log("populateSchools error: " + e.getMessage()); e.printStackTrace(); }
    }

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
                    log("Serial: " + serial);

                    Request check = new Request.Builder()
                            .url(flaskUrl + "/api/devices/check/" + serial).build();
                    Response checkRes = client.newCall(check).execute();
                    log("Check enrollment response: " + checkRes.code());

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
                        log("Registration response: " + regRes.code() + " " + regRes.body().string());

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

                    new Thread(() -> installHeadwindSilent()).start();

                    mainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        enrollStep = 1;
                        statusText.setText("Device registered!\n\nNext: grant Location permission.");
                        enrollButton.setText("Grant Location →");
                        enrollButton.setEnabled(true);
                    });

                } catch (Exception e) {
                    log("Registration thread error: " + e.getMessage());
                    mainHandler.post(() -> {
                        statusText.setText("Error: " + e.getMessage());
                        enrollButton.setEnabled(true);
                        progressBar.setVisibility(View.GONE);
                    });
                }
            }).start();

        } catch (Exception e) {
            Toast.makeText(this, "Error reading selection", Toast.LENGTH_SHORT).show();
            log("startRegistration error: " + e.getMessage());
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
        Intent svc = new Intent(this, AgentService.class);
        ContextCompat.startForegroundService(this, svc);

        // Launch LoginActivity (user must authenticate)
        Intent login = new Intent(this, LoginActivity.class);
        login.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(login);
        finish();
        log("Enrollment finished, launched LoginActivity");
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

            try {
                Process p = Runtime.getRuntime().exec("su");
                java.io.OutputStream os = p.getOutputStream();
                os.write(("pm install -r " + outFile.getAbsolutePath() + "\n").getBytes());
                os.write("exit\n".getBytes());
                os.flush();
                p.waitFor();
            } catch (Exception ignored) {
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", outFile);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Exception e) {
            log("installHeadwindSilent error: " + e.getMessage());
        }
    }

    private void headlessEnroll() {
        new Thread(() -> {
            try {
                String serial = getSerial();
                saveSerial(serial);
                log("Headless serial: " + serial);

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

            } catch (Exception ignored) {
                log("headlessEnroll error: " + ignored.getMessage());
            }
        }).start();
    }

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