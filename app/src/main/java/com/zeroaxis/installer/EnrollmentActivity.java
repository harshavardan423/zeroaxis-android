package com.zeroaxis.installer;

import android.content.Intent;
import android.os.Bundle;
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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EnrollmentActivity extends AppCompatActivity {

    private String flaskUrl;
    private boolean headless;
    private Spinner districtSpinner, blockSpinner, schoolSpinner;
    private Button enrollButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private List<JSONObject> districts = new ArrayList<>();
    private List<JSONObject> blocks = new ArrayList<>();
    private List<JSONObject> schools = new ArrayList<>();
    private OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            InputStream is = getAssets().open("config.json");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            JSONObject config = new JSONObject(new String(buffer));
            flaskUrl = config.getString("flask_url");
            headless = config.getBoolean("headless");
        } catch (Exception e) {
            flaskUrl = "http://3.6.85.206";
            headless = false;
        }

        if (headless) {
            // Skip UI, go straight to install
            startInstall(null, null, null);
            return;
        }

        setContentView(R.layout.activity_enrollment);

        districtSpinner = findViewById(R.id.districtSpinner);
        blockSpinner = findViewById(R.id.blockSpinner);
        schoolSpinner = findViewById(R.id.schoolSpinner);
        enrollButton = findViewById(R.id.enrollButton);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);

        loadHierarchy();

        districtSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) populateBlocks(position - 1);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        blockSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) populateSchools(position - 1);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
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
                String blockId = blocks.get(bPos - 1).getString("id");
                String schoolId = schools.get(sPos - 1).getString("id");
                startInstall(districtId, blockId, schoolId);
            } catch (Exception e) {
                Toast.makeText(this, "Error reading selection", Toast.LENGTH_SHORT).show();
            }
        });
    }

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

                runOnUiThread(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, districtNames);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    districtSpinner.setAdapter(adapter);
                    statusText.setText("Select your location to enroll this device");
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error connecting to server. Check network."));
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
        } catch (Exception e) {
            e.printStackTrace();
        }
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startInstall(String districtId, String blockId, String schoolId) {
        enrollButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("Registering device...");
    
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
                        .url(flaskUrl + "/api/devices/check/" + serial)
                        .build();
                Response checkResponse = client.newCall(checkRequest).execute();
                
                if (checkResponse.code() == 200) {
                    runOnUiThread(() -> statusText.setText("Already enrolled. ID: " + serial));
                    return;
                }
    
                // Register device
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("serial", serial);
                payload.put("platform", "android");
                payload.put("name", android.os.Build.MODEL);
                if (districtId != null) payload.put("district_id", Integer.parseInt(districtId));
                if (blockId != null) payload.put("block_id", Integer.parseInt(blockId));
                if (schoolId != null) payload.put("school_id", Integer.parseInt(schoolId));
    
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        payload.toString(),
                        okhttp3.MediaType.parse("application/json"));
    
                Request registerRequest = new Request.Builder()
                        .url(flaskUrl + "/api/devices/register")
                        .post(body)
                        .build();
    
                Response registerResponse = client.newCall(registerRequest).execute();
                String responseBody = registerResponse.body().string();
    
                if (registerResponse.isSuccessful()) {
                    runOnUiThread(() -> {
                        statusText.setText("Device enrolled successfully!");
                        progressBar.setVisibility(View.GONE);
                    });
                } else {
                    runOnUiThread(() -> statusText.setText("Registration failed: " + responseBody));
                }
    
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    
}
