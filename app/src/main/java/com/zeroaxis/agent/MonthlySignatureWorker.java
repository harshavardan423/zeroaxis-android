package com.zeroaxis.agent;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

public class MonthlySignatureWorker extends Worker {

    private static final String TAG             = "MonthlySignatureWorker";
    private static final long   MONTH_MS        = 30L * 24 * 60 * 60 * 1000L;

    public MonthlySignatureWorker(@NonNull Context context,
                                  @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        Log.i(TAG, "Monthly signature update starting");

        try {
            AVEngine engine = new AVEngine(ctx);
            int downloaded = engine.downloadSignatures(null);
            Log.i(TAG, "Monthly signature update done — files=" + downloaded);

            // Record timestamp so AgentService knows when we last ran
            ctx.getSharedPreferences("zeroaxis", Context.MODE_PRIVATE)
               .edit()
               .putLong("last_sig_update", System.currentTimeMillis())
               .apply();

            // Schedule the next run in 30 days
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "monthly_sig_update",
                androidx.work.ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(MonthlySignatureWorker.class)
                    .setInitialDelay(MONTH_MS, TimeUnit.MILLISECONDS)
                    .build()
            );

            return downloaded > 0 ? Result.success() : Result.retry();

        } catch (Exception e) {
            Log.e(TAG, "Monthly signature update failed: " + e.getMessage());
            return Result.retry();
        }
    }
}