package com.zeroaxis.agent;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class UsageStatsHelper {

    private static final String DEBUG_LOG = "/sdcard/zeroaxis_debug.log";
    private static void log(String msg) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(DEBUG_LOG, true);
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " - " + msg + "\n");
            fw.close();
        } catch (Exception e) { }
    }

    public static class AppUsage {
        public String packageName;
        public String appName;
        public int    foregroundMins;

        AppUsage(String pkg, String name, int mins) {
            packageName   = pkg;
            appName       = name;
            foregroundMins = mins;
        }
    }

    public static List<AppUsage> getTodayUsage(Context context) {
        List<AppUsage> result = new ArrayList<>();
        if (!hasPermission(context)) {
            log("getTodayUsage: no permission");
            return result;
        }

        UsageStatsManager usm = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfDay = cal.getTimeInMillis();
        long now        = System.currentTimeMillis();

        Map<String, UsageStats> statsMap = usm.queryAndAggregateUsageStats(startOfDay, now);
        PackageManager pm = context.getPackageManager();

        for (Map.Entry<String, UsageStats> entry : statsMap.entrySet()) {
            String pkg = entry.getKey();
            long   ms  = entry.getValue().getTotalTimeInForeground();
            if (ms < 60_000) continue;

            // Skip system apps (optional – comment out to include all)
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }

            String appName = pkg;
            try {
                appName = pm.getApplicationLabel(
                        pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) {}

            int mins = (int) TimeUnit.MILLISECONDS.toMinutes(ms);
            result.add(new AppUsage(pkg, appName, mins));
        }

        Collections.sort(result, (a, b) -> b.foregroundMins - a.foregroundMins);
        log("getTodayUsage: found " + result.size() + " apps");
        return result;
    }

    public static class AppInfo {
        public String packageName;
        public String appName;
        public String version;
        AppInfo(String pkg, String name, String ver) {
            packageName = pkg;
            appName = name;
            version = ver;
        }
    }

    public static List<AppInfo> getInstalledApps(Context context) {
        List<AppInfo> result = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        // Removed FLAG_SYSTEM filter – now includes all apps (including system)
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            // Optionally exclude only a few critical system packages:
            // String[] excludePkgs = {"com.android.systemui", "com.android.settings", "com.android.phone"};
            // if (Arrays.asList(excludePkgs).contains(ai.packageName)) continue;
            String label = pm.getApplicationLabel(ai).toString();
            String version = "";
            try { version = pm.getPackageInfo(ai.packageName, 0).versionName; } catch (Exception e) {}
            result.add(new AppInfo(ai.packageName, label, version));
        }
        Collections.sort(result, (a, b) -> a.appName.compareToIgnoreCase(b.appName));
        log("getInstalledApps: found " + result.size() + " apps");
        return result;
    }

    public static boolean hasPermission(Context context) {
        AppOpsManager aom = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = aom.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.getPackageName());
        boolean allowed = mode == AppOpsManager.MODE_ALLOWED;
        log("hasPermission: " + allowed);
        return allowed;
    }
}