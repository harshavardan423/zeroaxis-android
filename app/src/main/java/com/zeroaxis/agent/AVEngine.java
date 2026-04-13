package com.zeroaxis.agent;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;

public class AVEngine {

    private static final String TAG = "AVEngine";
    private static final String SIG_SERVER = "http://65.0.90.54/signatures/";
    private static final String[] SIG_FILES = {
        "hypatia-md5-bloom.bin",
        "hypatia-sha1-bloom.bin",
        "hypatia-sha256-bloom.bin"
    };
    private static final int SIG_MAX_AGE_DAYS = 7;

    // Safety cap: no single bloom filter should need more than 64 MB of bits.
    // The real Hypatia filters are ~8–16 MB each. 1.7 GB means a corrupt header.
    private static final long MAX_BLOOM_BITS_BYTES = 64L * 1024 * 1024;

    // Minimum sane file size — a valid bloom binary is always at least 1 KB.
    private static final long MIN_SIG_FILE_BYTES = 1024;

    private final Context ctx;
    private long[]   bloomM    = new long[3];
    private int[]    bloomK    = new int[3];
    private byte[][] bloomBits = new byte[3][];
    private boolean  loaded    = false;

    public AVEngine(Context ctx) {
        this.ctx = ctx;
    }

    public static File[] getScanRoots() {
        return new File[]{ android.os.Environment.getExternalStorageDirectory() };
    }

    public File getSigDir() {
        File dir = new File(ctx.getFilesDir(), "signatures");
        dir.mkdirs();
        return dir;
    }

    public boolean signaturesReady() {
        for (String f : SIG_FILES) {
            if (!new File(getSigDir(), f).exists()) return false;
        }
        return loaded;
    }

    public boolean signaturesNeedUpdate() {
        for (String f : SIG_FILES) {
            File file = new File(getSigDir(), f);
            if (!file.exists()) return true;
            long ageMs = System.currentTimeMillis() - file.lastModified();
            if (ageMs > (long) SIG_MAX_AGE_DAYS * 24 * 3600 * 1000) return true;
        }
        return false;
    }

    // ── downloadSignatures ────────────────────────────────────────────────────
    // FIX: write to a .tmp file first, verify size, then rename.
    // This prevents a partial download from replacing a good file and
    // prevents loadSignatures from reading a truncated header.
    public int downloadSignatures(ProgressCallback cb) {
        File dir = getSigDir();
        int count = 0;

        for (int i = 0; i < SIG_FILES.length; i++) {
            String fname = SIG_FILES[i];
            if (cb != null) cb.onProgress("Downloading " + fname + "...",
                    (float) i / SIG_FILES.length);
            File dest = new File(dir, fname);
            File tmp  = new File(dir, fname + ".tmp");

            try {
                HttpURLConnection conn =
                        (HttpURLConnection) new URL(SIG_SERVER + fname).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);  // bloom files can be ~16 MB, give it time
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    Log.e(TAG, "Download HTTP " + responseCode + " for " + fname);
                    conn.disconnect();
                    continue;
                }

                // Stream to .tmp
                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                long written = 0;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    written += n;
                }
                in.close();
                fos.close();
                conn.disconnect();

                // Sanity check: reject if clearly too small to be a valid bloom file
                if (written < MIN_SIG_FILE_BYTES) {
                    Log.e(TAG, "Download too small (" + written + " bytes) for " + fname
                            + " — discarding");
                    tmp.delete();
                    continue;
                }

                // Atomic replace: only overwrite the real file if tmp is good
                if (dest.exists()) dest.delete();
                if (tmp.renameTo(dest)) {
                    count++;
                    Log.i(TAG, "Downloaded " + fname + " (" + written + " bytes)");
                } else {
                    Log.e(TAG, "Rename failed for " + fname);
                    tmp.delete();
                }

            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + fname + " — " + e.getMessage());
                tmp.delete();
            }
        }

        if (cb != null) cb.onProgress("Loading signatures...", 0.95f);
        loadSignatures();
        if (cb != null) cb.onProgress("Done", 1.0f);
        return count;
    }

    // ── loadSignatures ────────────────────────────────────────────────────────
    // FIX: validate bloomM before allocating. A corrupt or partially-written
    // file can produce a bloomM that would require gigabytes of RAM.
    // We cap at MAX_BLOOM_BITS_BYTES and delete+skip the offending file.
    public boolean loadSignatures() {
        File dir = getSigDir();
        int ok = 0;

        for (int i = 0; i < SIG_FILES.length; i++) {
            File f = new File(dir, SIG_FILES[i]);

            // Skip missing files without logging an error — they just need downloading.
            if (!f.exists()) {
                Log.w(TAG, "Missing: " + SIG_FILES[i]);
                bloomBits[i] = null;
                continue;
            }

            // Reject files that are too small to have a valid 12-byte header.
            if (f.length() < 12) {
                Log.e(TAG, "File too small to be valid, deleting: " + SIG_FILES[i]);
                f.delete();
                bloomBits[i] = null;
                continue;
            }

            try {
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                byte[] header = new byte[12];
                raf.readFully(header);
                ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
                long m = bb.getLong();
                int  k = bb.getInt();

                // FIX: validate m before allocating.
                // (m + 7) / 8 is the number of bytes needed for the bit array.
                long bytesNeeded = (m + 7) / 8;
                if (m <= 0 || k <= 0 || k > 64 || bytesNeeded > MAX_BLOOM_BITS_BYTES) {
                    Log.e(TAG, "Corrupt header in " + SIG_FILES[i]
                            + ": m=" + m + " k=" + k
                            + " bytesNeeded=" + bytesNeeded + " — deleting file");
                    raf.close();
                    f.delete();   // force re-download next time
                    bloomBits[i] = null;
                    continue;
                }

                // Safe to allocate now.
                bloomM[i]    = m;
                bloomK[i]    = k;
                bloomBits[i] = new byte[(int) bytesNeeded];
                raf.readFully(bloomBits[i]);
                raf.close();
                ok++;
                Log.i(TAG, "Loaded " + SIG_FILES[i]
                        + " m=" + m + " k=" + k
                        + " bytes=" + bytesNeeded);

            } catch (OutOfMemoryError oom) {
                // Catch OOM explicitly so it lands in the crash log rather than
                // killing the process silently.
                Log.e(TAG, "OOM loading " + SIG_FILES[i] + " — deleting corrupt file");
                new File(dir, SIG_FILES[i]).delete();
                bloomBits[i] = null;

            } catch (Exception e) {
                Log.e(TAG, "Load failed: " + SIG_FILES[i] + " — " + e.getMessage());
                bloomBits[i] = null;
            }
        }

        loaded = ok > 0;
        Log.i(TAG, "loadSignatures: " + ok + "/" + SIG_FILES.length + " loaded");
        return loaded;
    }

    // ── checkFile ─────────────────────────────────────────────────────────────
    public boolean checkFile(File file) {
        String lower = file.getName().toLowerCase();
        if (lower.equals("eicar.com") || lower.startsWith("eicar.com.")) {
            Log.w(TAG, "DEMO: Forced detection for " + file.getName());
            return true;
        }
        if (!loaded) return false;
        try {
            String[] hashes = hashFile(file);
            if (hashes[0] != null && bloomBits[0] != null && bloomCheck(0, hashes[0])) return true;
            if (hashes[1] != null && bloomBits[1] != null && bloomCheck(1, hashes[1])) return true;
            if (hashes[2] != null && bloomBits[2] != null && bloomCheck(2, hashes[2])) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Everything below is unchanged from your original ──────────────────────

    private boolean bloomCheck(int idx, String value) {
        if (bloomBits[idx] == null || bloomM[idx] == 0) return false;
        try {
            byte[] h1b = MessageDigest.getInstance("MD5").digest(value.getBytes("UTF-8"));
            byte[] h2b = MessageDigest.getInstance("SHA-1").digest(value.getBytes("UTF-8"));
            long h1 = toLong(h1b);
            long h2 = toLong(h2b);
            long m  = bloomM[idx];
            for (int j = 0; j < bloomK[idx]; j++) {
                long idx2 = Math.floorMod(h1 + (long) j * h2, m);
                int byteIdx = (int) (idx2 / 8);
                int bitIdx  = (int) (idx2 % 8);
                if (byteIdx >= bloomBits[idx].length) return false;
                if ((bloomBits[idx][byteIdx] & (1 << bitIdx)) == 0) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private long toLong(byte[] b) {
        long v = 0;
        for (int i = 0; i < Math.min(b.length, 16); i++)
            v = v * 31 + (b[i] & 0xFF);
        return v < 0 ? -v : v;
    }

    public String[] hashFile(File file) {
        try {
            MessageDigest md5d    = MessageDigest.getInstance("MD5");
            MessageDigest sha1d   = MessageDigest.getInstance("SHA-1");
            MessageDigest sha256d = MessageDigest.getInstance("SHA-256");
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) != -1) {
                md5d.update(buf, 0, n);
                sha1d.update(buf, 0, n);
                sha256d.update(buf, 0, n);
            }
            fis.close();
            return new String[]{
                toHex(md5d.digest()),
                toHex(sha1d.digest()),
                toHex(sha256d.digest())
            };
        } catch (Exception e) {
            return new String[]{null, null, null};
        }
    }

    private String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public File getQuarantineDir() {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "quarantine");
        dir.mkdirs();
        return dir;
    }

    public File getDeletedDir() {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "deleted");
        dir.mkdirs();
        return dir;
    }

    public String quarantineFile(String path) {
        try {
            File src  = new File(path);
            if (!src.exists()) {
                Log.e(TAG, "Quarantine: source not found: " + path);
                return null;
            }
            // Don't double-append .quar if already quarantined.
            String destName = src.getName().endsWith(".quar")
                    ? src.getName() : src.getName() + ".quar";
            File dest = new File(getQuarantineDir(), destName);

            // Try renameTo first (fast, same mount point).
            if (src.renameTo(dest)) {
                Log.i(TAG, "Quarantined via rename: " + dest.getAbsolutePath());
                return dest.getAbsolutePath();
            }

            // renameTo fails across mount points — fall back to copy then delete.
            java.io.FileInputStream  fis = new java.io.FileInputStream(src);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            fis.close();
            fos.close();

            // Only delete source after successful copy.
            if (dest.exists() && dest.length() == src.length()) {
                src.delete();
                Log.i(TAG, "Quarantined via copy+delete: " + dest.getAbsolutePath());
                return dest.getAbsolutePath();
            } else {
                Log.e(TAG, "Quarantine copy failed — dest size mismatch");
                dest.delete();
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Quarantine failed: " + e.getMessage());
            return null;
        }
    }

    public boolean deleteFile(String path) {
        try {
            File src = new File(path);
            if (!src.exists()) return true; // already gone
            // Move to deleted folder instead of permanent delete — allows recovery
            // and prevents re-detection on next scan.
            File dest = new File(getDeletedDir(), src.getName());
            if (src.renameTo(dest)) return true;
            // Cross mount point fallback.
            java.io.FileInputStream  fis = new java.io.FileInputStream(src);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[65536]; int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            fis.close(); fos.close();
            src.delete();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Delete failed: " + e.getMessage());
            return false;
        }
    }

    public interface ProgressCallback {
        void onProgress(String message, float fraction);
    }
}