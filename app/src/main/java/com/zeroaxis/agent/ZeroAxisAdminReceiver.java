package com.zeroaxis.agent;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import android.content.IntentFilter;
import android.os.UserManager;

public class ZeroAxisAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "ZeroAxis device admin enabled", Toast.LENGTH_SHORT).show();
        if (isOemMode(context)) {
            applyOemPolicies(context);
        }
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "ZeroAxis device admin disabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        // Called when Device Owner provisioning completes (OEM/QR/NFC flow)
        applyOemPolicies(context);
    }

    private void applyOemPolicies(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, ZeroAxisAdminReceiver.class);

            if (!dpm.isDeviceOwnerApp(context.getPackageName())) return;

            // Set ZeroAxis as persistent preferred home app — can't be changed by user
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            ComponentName launcher = new ComponentName(context, LauncherActivity.class);
            dpm.addPersistentPreferredActivity(admin, homeFilter, launcher);

            // Prevent user from changing default home app
            dpm.setUserRestriction(admin,
                    UserManager.DISALLOW_MODIFY_ACCOUNTS, true);

            // Block Settings from being accessible
            dpm.setApplicationHidden(admin,
                    "com.android.settings", true);

            // Prevent installing/uninstalling apps
            dpm.setUserRestriction(admin,
                    UserManager.DISALLOW_INSTALL_APPS, true);
            dpm.setUserRestriction(admin,
                    UserManager.DISALLOW_UNINSTALL_APPS, true);

            // Prevent factory reset by user
            dpm.setUserRestriction(admin,
                    UserManager.DISALLOW_FACTORY_RESET, true);

            // Prevent adding new users
            dpm.setUserRestriction(admin,
                    UserManager.DISALLOW_ADD_USER, true);

            // Lock status bar based on config
            if (isHideStatusBar(context)) {
                dpm.setStatusBarDisabled(admin, true);
            } else {
                // Just disable notifications/quick settings pull down
                dpm.setKeyguardDisabledFeatures(admin,
                        DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS);
            }

        } catch (Exception e) {
            android.util.Log.e("ZeroAxisAdmin", "applyOemPolicies failed: " + e.getMessage());
        }
    }

    private boolean isOemMode(Context context) {
        try {
            java.io.InputStream is = context.getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new org.json.JSONObject(new String(buf)).optBoolean("oem", false);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isHideStatusBar(Context context) {
        try {
            java.io.InputStream is = context.getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new org.json.JSONObject(new String(buf)).optBoolean("hide_status_bar", false);
        } catch (Exception e) {
            return false;
        }
    }
}
