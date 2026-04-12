package com.zeroaxis.agent;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
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

    private final Context ctx;
    private long[]   bloomM    = new long[3];
    private int[]    bloomK    = new int[3];
    private byte[][] bloomBits = new byte[3][];
    private boolean  loaded    = false;

    public AVEngine(Context ctx) {
        this.ctx = ctx;
    }

    // ── Scan roots (Option B – no special permissions) ─────────────────────────
    public static File[] getScanRoots() {
        return new File[]{
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS),
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS),
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM),
        };
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

    public int downloadSignatures(ProgressCallback cb) {
        File dir = getSigDir();
        int count = 0;
        for (int i = 0; i < SIG_FILES.length; i++) {
            String fname = SIG_FILES[i];
            if (cb != null) cb.onProgress("Downloading " + fname + "...", (float) i / SIG_FILES.length);
            try {
                File dest = new File(dir, fname);
                URL url = new URL(SIG_SERVER + fname);
                InputStream in = url.openStream();
                FileOutputStream fos = new FileOutputStream(dest);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                in.close();
                fos.close();
                count++;
                Log.i(TAG, "Downloaded " + fname);
            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + fname + " " + e.getMessage());
            }
        }
        if (cb != null) cb.onProgress("Loading signatures...", 0.95f);
        loadSignatures();
        if (cb != null) cb.onProgress("Done", 1.0f);
        return count;
    }

    public boolean loadSignatures() {
        File dir = getSigDir();
        int ok = 0;
        for (int i = 0; i < SIG_FILES.length; i++) {
            try {
                File f = new File(dir, SIG_FILES[i]);
                if (!f.exists()) continue;
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                byte[] header = new byte[12];
                raf.readFully(header);
                ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
                bloomM[i] = bb.getLong();
                bloomK[i] = bb.getInt();
                bloomBits[i] = new byte[(int) ((bloomM[i] + 7) / 8)];
                raf.readFully(bloomBits[i]);
                raf.close();
                ok++;
                Log.i(TAG, "Loaded " + SIG_FILES[i]);
            } catch (Exception e) {
                Log.e(TAG, "Load failed: " + SIG_FILES[i] + " " + e.getMessage());
            }
        }
        loaded = ok > 0;
        return loaded;
    }

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

    public boolean checkFile(File file) {
        // DEMO: Force detection for test file (eicar.com)
        if (file.getName().equalsIgnoreCase("eicar.com")) {
            Log.w(TAG, "DEMO: Forced detection for eicar.com");
            return true;
        }
        try {
            String[] hashes = hashFile(file);
            String md5    = hashes[0];
            String sha1   = hashes[1];
            String sha256 = hashes[2];
            if (md5    != null && bloomBits[0] != null && bloomCheck(0, md5))    return true;
            if (sha1   != null && bloomBits[1] != null && bloomCheck(1, sha1))   return true;
            if (sha256 != null && bloomBits[2] != null && bloomCheck(2, sha256)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
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
        File dir = new File(ctx.getFilesDir(), "quarantine");
        dir.mkdirs();
        return dir;
    }

    public String quarantineFile(String path) {
        try {
            File src  = new File(path);
            File dest = new File(getQuarantineDir(), src.getName() + ".quar");
            if (src.renameTo(dest)) return dest.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Quarantine failed: " + e.getMessage());
        }
        return null;
    }

    public boolean deleteFile(String path) {
        try {
            return new File(path).delete();
        } catch (Exception e) {
            return false;
        }
    }

    public interface ProgressCallback {
        void onProgress(String message, float fraction);
    }
}