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
import java.io.File;

public class UsageStatsHelper {

    private static String DEBUG_LOG = null;

    private static void initLog(Context context) {
        if (DEBUG_LOG == null) {
            File logDir = context.getExternalFilesDir(null);
            if (logDir != null) {
                DEBUG_LOG = new File(logDir, "zeroaxis_debug.log").getAbsolutePath();
            } else {
                DEBUG_LOG = context.getFilesDir() + "/zeroaxis_debug.log";
            }
        }
    }

    private static void log(Context context, String msg) {
        initLog(context);
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
        public long   foregroundMs;  // raw milliseconds, avoids precision loss

        AppUsage(String pkg, String name, int mins, long ms) {
            packageName    = pkg;
            appName        = name;
            foregroundMins = mins;
            foregroundMs   = ms;
        }
    }

    private static java.util.Map<String, Long> sessionBaselineMs = new java.util.HashMap<>();
    private static long sessionBaselineTimestamp = 0;

    public static void recordSessionBaseline(Context context) {
        UsageStatsManager usm = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfDay = cal.getTimeInMillis();
        long now = System.currentTimeMillis();
        List<UsageStats> dailyList = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startOfDay, now);
        Map<String, UsageStats> statsMap = new java.util.HashMap<>();
        if (dailyList != null) {
            for (UsageStats s : dailyList) {
                String pkg = s.getPackageName();
                if (!statsMap.containsKey(pkg) ||
                        s.getTotalTimeInForeground() > statsMap.get(pkg).getTotalTimeInForeground()) {
                    statsMap.put(pkg, s);
                }
            }
        }
        if (statsMap.isEmpty()) {
            Map<String, UsageStats> fallback = usm.queryAndAggregateUsageStats(startOfDay, now);
            if (fallback != null) statsMap.putAll(fallback);
        }
        sessionBaselineMs.clear();
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            for (Map.Entry<String, UsageStats> entry : statsMap.entrySet()) {
                long ms = entry.getValue().getTotalTimeInForeground();
                sessionBaselineMs.put(entry.getKey(), ms);
                json.put(entry.getKey(), ms);
            }
            context.getSharedPreferences("zeroaxis", Context.MODE_PRIVATE)
                    .edit()
                    .putString("session_baseline", json.toString())
                    .putLong("session_baseline_ts", now)
                    .apply();
        } catch (Exception e) {
            log(context, "recordSessionBaseline persist error: " + e.getMessage());
        }
        sessionBaselineTimestamp = now;
        log(context, "recordSessionBaseline: snapshotted " + sessionBaselineMs.size() + " apps at " + now);
    }

    public static java.util.Map<String, Long> getSessionBaselineMs(Context context) {
        if (sessionBaselineMs.isEmpty()) {
            try {
                String saved = context.getSharedPreferences("zeroaxis", Context.MODE_PRIVATE)
                        .getString("session_baseline", null);
                if (saved != null) {
                    org.json.JSONObject json = new org.json.JSONObject(saved);
                    java.util.Iterator<String> keys = json.keys();
                    while (keys.hasNext()) {
                        String pkg = keys.next();
                        sessionBaselineMs.put(pkg, json.getLong(pkg));
                    }
                    log(context, "getSessionBaselineMs: reloaded " + sessionBaselineMs.size() + " apps from prefs");
                }
            } catch (Exception e) {
                log(context, "getSessionBaselineMs reload error: " + e.getMessage());
            }
        }
        return sessionBaselineMs;
    }

    public static void clearSessionBaseline(Context context) {
        sessionBaselineMs.clear();
        sessionBaselineTimestamp = 0;
        context.getSharedPreferences("zeroaxis", Context.MODE_PRIVATE)
                .edit()
                .remove("session_baseline")
                .remove("session_baseline_ts")
                .apply();
        log(context, "clearSessionBaseline: cleared");
    }

    public static List<AppUsage> getTodayUsage(Context context) {
        List<AppUsage> result = new ArrayList<>();
        if (!hasPermission(context)) {
            log(context, "getTodayUsage: no permission");
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
        long now = System.currentTimeMillis();

        List<UsageStats> dailyList = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startOfDay, now);
        Map<String, UsageStats> statsMap = new java.util.HashMap<>();
        if (dailyList != null) {
            for (UsageStats s : dailyList) {
                String pkg = s.getPackageName();
                if (!statsMap.containsKey(pkg) ||
                        s.getTotalTimeInForeground() > statsMap.get(pkg).getTotalTimeInForeground()) {
                    statsMap.put(pkg, s);
                }
            }
        }
        if (statsMap.isEmpty()) {
            Map<String, UsageStats> fallback = usm.queryAndAggregateUsageStats(startOfDay, now);
            if (fallback != null) statsMap.putAll(fallback);
        }
        PackageManager pm = context.getPackageManager();

        for (Map.Entry<String, UsageStats> entry : statsMap.entrySet()) {
            String pkg = entry.getKey();
            long ms = entry.getValue().getTotalTimeInForeground();
            if (ms < 60000) continue;

            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    java.util.Set<String> allowedSystem = new java.util.HashSet<>(java.util.Arrays.asList(
                        "com.android.chrome",
                        "com.google.android.youtube",
                        "com.google.android.gm",
                        "com.google.android.apps.maps",
                        "com.google.android.calculator",
                        "com.google.android.calendar"
                    ));
                    if (!allowedSystem.contains(pkg)) continue;
                }
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }

            String appName = pkg;
            try {
                appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) {}

            int mins = (int) TimeUnit.MILLISECONDS.toMinutes(ms);
            result.add(new AppUsage(pkg, appName, mins, ms));
        }

        Collections.sort(result, (a, b) -> b.foregroundMins - a.foregroundMins);
        log(context, "getTodayUsage: found " + result.size() + " apps");
        return result;
    }

    public static List<AppUsage> getUsageSince(Context context, long sinceTimestamp) {
        List<AppUsage> result = new ArrayList<>();
        if (!hasPermission(context)) return result;
        
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, sinceTimestamp, now);
        Map<String, Long> statsMap = new java.util.HashMap<>();
        
        if (stats != null) {
            for (UsageStats s : stats) {
                String pkg = s.getPackageName();
                long ms = s.getTotalTimeInForeground();
                if (statsMap.containsKey(pkg)) {
                    statsMap.put(pkg, statsMap.get(pkg) + ms);
                } else {
                    statsMap.put(pkg, ms);
                }
            }
        }
        
        PackageManager pm = context.getPackageManager();
        for (Map.Entry<String, Long> entry : statsMap.entrySet()) {
            long ms = entry.getValue();
            if (ms < 60000) continue;
            String appName = entry.getKey();
            try {
                appName = pm.getApplicationLabel(pm.getApplicationInfo(entry.getKey(), 0)).toString();
            } catch (Exception e) {}
            result.add(new AppUsage(entry.getKey(), appName, (int) TimeUnit.MILLISECONDS.toMinutes(ms), ms));
        }
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
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            String label = pm.getApplicationLabel(ai).toString();
            String version = "";
            try { version = pm.getPackageInfo(ai.packageName, 0).versionName; } catch (Exception e) {}
            result.add(new AppInfo(ai.packageName, label, version));
        }
        Collections.sort(result, (a, b) -> a.appName.compareToIgnoreCase(b.appName));
        log(context, "getInstalledApps: found " + result.size() + " apps");
        return result;
    }

    public static long getRawForegroundMs(Context context, String packageName) {
        if (!hasPermission(context)) return 0L;
        UsageStatsManager usm = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfDay = cal.getTimeInMillis();
        long now = System.currentTimeMillis();
        List<UsageStats> list = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startOfDay, now);
        long maxMs = 0;
        if (list != null) {
            for (UsageStats s : list) {
                if (packageName.equals(s.getPackageName())) {
                    maxMs = Math.max(maxMs, s.getTotalTimeInForeground());
                }
            }
        }
        if (maxMs == 0) {
            Map<String, UsageStats> agg = usm.queryAndAggregateUsageStats(startOfDay, now);
            if (agg != null && agg.containsKey(packageName)) {
                maxMs = agg.get(packageName).getTotalTimeInForeground();
            }
        }
        return maxMs;
    }

    public static boolean hasPermission(Context context) {
        AppOpsManager aom = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = aom.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.getPackageName());
        boolean allowed = mode == AppOpsManager.MODE_ALLOWED;
        log(context, "hasPermission: " + allowed);
        return allowed;
    }
}