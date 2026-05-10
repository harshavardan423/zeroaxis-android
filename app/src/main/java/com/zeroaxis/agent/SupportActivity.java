package com.zeroaxis.agent;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import okhttp3.*;

public class SupportActivity extends AppCompatActivity {

    private String flaskUrl, deviceSerial, username;
    private OkHttpClient client = new OkHttpClient();

    private RadioGroup rgCategory;
    private EditText etDescription;
    private Button btnSubmitTicket;
    private TextView tvTicketStatus;
    private LinearLayout feedbackContainer;
    private TextView tvFeedbackStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_support);

        flaskUrl     = getIntent().getStringExtra("flask_url");
        deviceSerial = getIntent().getStringExtra("device_serial");
        username     = getIntent().getStringExtra("username");

        if (flaskUrl == null)     flaskUrl = "https://zeroaxis.live";
        if (deviceSerial == null) deviceSerial = "";
        if (username == null)     username = "";

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Report / Feedback");
        }

        rgCategory        = findViewById(R.id.rgCategory);
        etDescription     = findViewById(R.id.etDescription);
        btnSubmitTicket   = findViewById(R.id.btnSubmitTicket);
        tvTicketStatus    = findViewById(R.id.tvTicketStatus);
        feedbackContainer = findViewById(R.id.feedbackContainer);
        tvFeedbackStatus  = findViewById(R.id.tvFeedbackStatus);

        btnSubmitTicket.setOnClickListener(v -> submitTicket());
        loadFeedbackForms();
    }

    private String getSelectedCategory() {
        int id = rgCategory.getCheckedRadioButtonId();
        if (id == R.id.rbHardware) return "hardware";
        if (id == R.id.rbSoftware) return "software";
        if (id == R.id.rbNetwork)  return "network";
        return "other";
    }

    private void submitTicket() {
        String desc = etDescription.getText().toString().trim();
        if (desc.isEmpty()) {
            tvTicketStatus.setText("Please describe your issue.");
            tvTicketStatus.setTextColor(0xFFD32F2F);
            return;
        }
        btnSubmitTicket.setEnabled(false);
        btnSubmitTicket.setText("Submitting…");
        tvTicketStatus.setText("");

        try {
            JSONObject payload = new JSONObject();
            payload.put("device_serial", deviceSerial);
            payload.put("username", username);
            payload.put("description", desc);
            payload.put("category", getSelectedCategory());

            RequestBody body = RequestBody.create(
                    payload.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(flaskUrl + "/api/enduser/ticket")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        btnSubmitTicket.setEnabled(true);
                        btnSubmitTicket.setText("Submit Ticket");
                        tvTicketStatus.setText("Network error. Try again.");
                        tvTicketStatus.setTextColor(0xFFD32F2F);
                    });
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    boolean ok = response.isSuccessful();
                    response.close();
                    runOnUiThread(() -> {
                        btnSubmitTicket.setEnabled(true);
                        btnSubmitTicket.setText("Submit Ticket");
                        if (ok) {
                            etDescription.setText("");
                            rgCategory.check(R.id.rbOther);
                            tvTicketStatus.setText("✓ Ticket submitted successfully.");
                            tvTicketStatus.setTextColor(0xFF388E3C);
                        } else {
                            tvTicketStatus.setText("Failed to submit. Try again.");
                            tvTicketStatus.setTextColor(0xFFD32F2F);
                        }
                    });
                }
            });
        } catch (Exception e) {
            btnSubmitTicket.setEnabled(true);
            btnSubmitTicket.setText("Submit Ticket");
            tvTicketStatus.setText("Error: " + e.getMessage());
            tvTicketStatus.setTextColor(0xFFD32F2F);
        }
    }

    private void loadFeedbackForms() {
        tvFeedbackStatus.setText("Loading feedback forms…");
        Request request = new Request.Builder()
                .url(flaskUrl + "/api/enduser/feedback/forms/" + deviceSerial + "/" + username)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> tvFeedbackStatus.setText("Could not load feedback forms."));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();
                runOnUiThread(() -> {
                    try {
                        JSONArray forms = new JSONArray(body);
                        tvFeedbackStatus.setText("");
                        feedbackContainer.removeAllViews();
                        if (forms.length() == 0) {
                            tvFeedbackStatus.setText("No active feedback forms right now.");
                            return;
                        }
                        for (int i = 0; i < forms.length(); i++) {
                            buildFeedbackCard(forms.getJSONObject(i));
                        }
                    } catch (Exception e) {
                        tvFeedbackStatus.setText("Error loading forms.");
                    }
                });
            }
        });
    }

    private void buildFeedbackCard(JSONObject form) {
        try {
            int formId   = form.getInt("id");
            String title = form.optString("title", "Feedback");
            String desc  = form.optString("description", "");

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(0xFFFFFFFF);
            card.setPadding(40, 36, 40, 36);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, 0, 32);
            card.setLayoutParams(cardLp);

            TextView tvTitle = new TextView(this);
            tvTitle.setText(title);
            tvTitle.setTextSize(16f);
            tvTitle.setTextColor(0xFF1a1a2e);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(tvTitle);

            if (!desc.isEmpty()) {
                TextView tvDesc = new TextView(this);
                tvDesc.setText(desc);
                tvDesc.setTextSize(13f);
                tvDesc.setTextColor(0xFF6C757D);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                dlp.setMargins(0, 8, 0, 0);
                tvDesc.setLayoutParams(dlp);
                card.addView(tvDesc);
            }

            final int[] selectedRating = {0};
            LinearLayout starsRow = new LinearLayout(this);
            starsRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            srlp.setMargins(0, 20, 0, 8);
            starsRow.setLayoutParams(srlp);

            TextView[] stars = new TextView[5];
            for (int s = 0; s < 5; s++) {
                TextView star = new TextView(this);
                star.setText("★");
                star.setTextSize(32f);
                star.setTextColor(0xFFCCCCCC);
                star.setPadding(4, 0, 4, 0);
                star.setClickable(true);
                stars[s] = star;
                final int rating = s + 1;
                star.setOnClickListener(v -> {
                    selectedRating[0] = rating;
                    for (int k = 0; k < 5; k++) {
                        stars[k].setTextColor(k < rating ? 0xFFf59f00 : 0xFFCCCCCC);
                    }
                });
                starsRow.addView(star);
            }
            card.addView(starsRow);

            EditText etComment = new EditText(this);
            etComment.setHint("Comments (optional)");
            etComment.setMinLines(2);
            etComment.setGravity(android.view.Gravity.TOP);
            etComment.setPadding(24, 20, 24, 20);
            LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            etlp.setMargins(0, 12, 0, 16);
            etComment.setLayoutParams(etlp);
            card.addView(etComment);

            Button btnSubmit = new Button(this);
            btnSubmit.setText("Submit Feedback");
            btnSubmit.setBackgroundColor(0xFF8b5cf6);
            btnSubmit.setTextColor(0xFFFFFFFF);

            TextView tvCardStatus = new TextView(this);
            tvCardStatus.setTextSize(12f);
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            stlp.setMargins(0, 8, 0, 0);
            tvCardStatus.setLayoutParams(stlp);

            btnSubmit.setOnClickListener(v -> {
                int rating = selectedRating[0];
                if (rating == 0) {
                    tvCardStatus.setText("Please select a star rating.");
                    tvCardStatus.setTextColor(0xFFD32F2F);
                    return;
                }
                String comment = etComment.getText().toString().trim();
                btnSubmit.setEnabled(false);
                btnSubmit.setText("Submitting…");
                tvCardStatus.setText("");
                submitFeedback(formId, rating, comment, btnSubmit, tvCardStatus);
            });

            card.addView(btnSubmit);
            card.addView(tvCardStatus);
            feedbackContainer.addView(card);

        } catch (Exception e) {
            tvFeedbackStatus.setText("Error rendering form.");
        }
    }

    private void submitFeedback(int formId, int rating, String text,
                                Button btn, TextView statusView) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("device_serial", deviceSerial);
            payload.put("username", username);
            payload.put("form_id", formId);
            payload.put("rating", rating);
            payload.put("text", text);

            RequestBody body = RequestBody.create(
                    payload.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(flaskUrl + "/api/enduser/feedback/submit")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        btn.setEnabled(true);
                        btn.setText("Submit Feedback");
                        statusView.setText("Network error. Try again.");
                        statusView.setTextColor(0xFFD32F2F);
                    });
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    boolean ok = response.isSuccessful();
                    response.close();
                    runOnUiThread(() -> {
                        if (ok) {
                            btn.setEnabled(false);
                            btn.setText("Submitted ✓");
                            statusView.setText("Thank you for your feedback!");
                            statusView.setTextColor(0xFF388E3C);
                        } else {
                            btn.setEnabled(true);
                            btn.setText("Submit Feedback");
                            statusView.setText("Failed. Try again.");
                            statusView.setTextColor(0xFFD32F2F);
                        }
                    });
                }
            });
        } catch (Exception e) {
            btn.setEnabled(true);
            btn.setText("Submit Feedback");
            statusView.setText("Error: " + e.getMessage());
            statusView.setTextColor(0xFFD32F2F);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}