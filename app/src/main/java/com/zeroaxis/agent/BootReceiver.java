package com.zeroaxis.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // Only start if device was previously enrolled
        SharedPreferences prefs = context.getSharedPreferences("zeroaxis", Context.MODE_PRIVATE);
        String serial = prefs.getString("serial", null);
        if (serial == null) return;

        Intent svc = new Intent(context, AgentService.class);
        ContextCompat.startForegroundService(context, svc);
    }
}
