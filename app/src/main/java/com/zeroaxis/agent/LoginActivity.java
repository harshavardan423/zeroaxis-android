package com.zeroaxis.agent;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import okhttp3.*;

import java.io.IOException;

public class LoginActivity extends AppCompatActivity {

    private EditText etUsername, etPin;
    private Button btnLogin;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private OkHttpClient client = new OkHttpClient();
    private String flaskUrl;
    private String deviceSerial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etUsername = findViewById(R.id.etUsername);
        etPin = findViewById(R.id.etPin);
        btnLogin = findViewById(R.id.btnLogin);
        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);

        flaskUrl = loadFlaskUrl();
        deviceSerial = getSharedPreferences("zeroaxis", MODE_PRIVATE).getString("serial", null);
        if (deviceSerial == null) {
            // Not enrolled, go to EnrollmentActivity
            startActivity(new Intent(this, EnrollmentActivity.class));
            finish();
            return;
        }

        btnLogin.setOnClickListener(v -> performLogin());
    }

    private void performLogin() {
        String username = etUsername.getText().toString().trim();
        String pin = etPin.getText().toString().trim();
        if (username.isEmpty()) {
            Toast.makeText(this, "Username required", Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Authenticating...");

        JSONObject payload = new JSONObject();
        try {
            payload.put("device_serial", deviceSerial);
            payload.put("username", username);
            payload.put("pin", pin);
        } catch (Exception e) {
            onError(e.getMessage());
            return;
        }

        RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(flaskUrl + "/api/enduser/login")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> onError("Network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String respBody = response.body().string();
                if (response.isSuccessful()) {
                    try {
                        JSONObject data = new JSONObject(respBody);
                        if (data.optBoolean("success", false)) {
                            // Save user and policies
                            SharedPreferences prefs = getSharedPreferences("zeroaxis", MODE_PRIVATE);
                            prefs.edit()
                                    .putString("logged_in_user", username)
                                    .putString("user_policies", data.getJSONObject("policies").toString())
                                    .putLong("policy_fetch_time", System.currentTimeMillis())
                                    .apply();
                            runOnUiThread(() -> {
                                Intent intent = new Intent(LoginActivity.this, LauncherActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            });
                        } else {
                            runOnUiThread(() -> onError(data.optString("error", "Invalid credentials")));
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> onError("Server response error"));
                    }
                } else {
                    runOnUiThread(() -> onError("Login failed: " + respBody));
                }
            }
        });
    }

    private void onError(String msg) {
        tvStatus.setText(msg);
        btnLogin.setEnabled(true);
        progressBar.setVisibility(View.GONE);
    }

    private String loadFlaskUrl() {
        try {
            java.io.InputStream is = getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new org.json.JSONObject(new String(buf)).getString("flask_url");
        } catch (Exception e) {
            return "https://zeroaxis.live";
        }
    }
}