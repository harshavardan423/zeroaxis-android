package com.zeroaxis.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class DnsVpnService extends VpnService {

    private static final String TAG = "DnsVpnService";
    private static final String CHANNEL_ID = "zeroaxis_vpn";
    private static final int NOTIF_ID = 2002;
    private static final String UPSTREAM_DNS = "8.8.8.8";
    private static final int DNS_PORT = 53;
    private static final int BATCH_INTERVAL_MS = 60_000; // flush DNS log every 60s

    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executor;
    private volatile boolean running = false;

    private final List<String> dnsLogBatch = new ArrayList<>();
    private volatile Set<String> blockedDomains = new HashSet<>();
    private final android.content.BroadcastReceiver domainsReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            reloadBlockedDomains();
        }
    };
    private long lastFlushMs = 0;
    private OkHttpClient httpClient;
    private String flaskUrl;
    private String serial;

    private void reloadBlockedDomains() {
        Set<String> stored = getSharedPreferences("zeroaxis", MODE_PRIVATE)
                .getStringSet("blocked_domains", new HashSet<>());
        blockedDomains = new HashSet<>(stored); // copy to break cache reference
        Log.d(TAG, "Reloaded blocked domains: " + blockedDomains.size());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient();
        flaskUrl = loadFlaskUrl();
        serial = getSharedPreferences("zeroaxis", MODE_PRIVATE)
                .getString("serial", android.os.Build.SERIAL);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        reloadBlockedDomains();
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .registerReceiver(domainsReceiver,
                        new android.content.IntentFilter("com.zeroaxis.DOMAINS_UPDATED"));
    }

    @Override
    public void onDestroy() {
        running = false;
        List<String> remaining;
        synchronized (dnsLogBatch) {
            remaining = new ArrayList<>(dnsLogBatch);
            dnsLogBatch.clear();
        }
        if (!remaining.isEmpty()) flushDnsLog(remaining);
        if (executor != null) executor.shutdownNow();
        closeTunnel();
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(domainsReceiver);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        running = true;
        executor = Executors.newCachedThreadPool();
        executor.submit(this::runVpn);
        return START_STICKY;
    }

    private void runVpn() {
        try {
            Builder builder = new Builder()
                    .setSession("ZeroAxis DNS")
                    .addAddress("10.0.0.2", 32)
                    .addDnsServer(UPSTREAM_DNS)
                    .addRoute("0.0.0.0", 0)  // route all traffic through TUN
                    .setMtu(1500);
            // Exclude our own app from the VPN to avoid routing loops
            builder.addDisallowedApplication(getPackageName());
            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface");
                return;
            }

            FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

            ByteBuffer packet = ByteBuffer.allocate(32767);
            Log.d(TAG, "VPN tunnel established");

            while (running) {
                packet.clear();
                int len = in.read(packet.array());
                if (len <= 0) continue;
                packet.limit(len);

                // Parse IP header to check if it's UDP to port 53
                if (!isUdpDns(packet.array(), len)) {
                    out.write(packet.array(), 0, len);
                    continue;
                }

                // Extract domain from DNS query
                String domain = extractDomain(packet.array(), len);
                if (domain != null && !domain.isEmpty()) {
                    String cleanDomain = domain.toLowerCase().trim();
                    synchronized (dnsLogBatch) {
                        dnsLogBatch.add(cleanDomain);
                    }

                    // Check if domain is blocked
                    if (isDomainBlocked(cleanDomain)) {
                        Log.d(TAG, "Blocked DNS: " + cleanDomain);
                        // Write NXDOMAIN response back to TUN
                        byte[] nxdomain = buildNxDomainResponse(packet.array(), len);
                        if (nxdomain != null) {
                            out.write(nxdomain);
                        }
                        maybeFlushDnsLog();
                        continue;
                    }
                }

                // Forward to real DNS upstream and relay response back
                executor.submit(() -> {
                    try {
                        byte[] dnsPayload = extractDnsPayload(packet.array(), len);
                        if (dnsPayload == null) return;

                        DatagramSocket socket = new DatagramSocket();
                        protect(socket); // critical: exclude from VPN routing
                        InetAddress upstream = InetAddress.getByName(UPSTREAM_DNS);
                        DatagramPacket query = new DatagramPacket(dnsPayload, dnsPayload.length, upstream, DNS_PORT);
                        socket.send(query);

                        byte[] responseBuf = new byte[4096];
                        DatagramPacket response = new DatagramPacket(responseBuf, responseBuf.length);
                        socket.setSoTimeout(3000);
                        socket.receive(response);
                        socket.close();

                        // Wrap response in IP+UDP headers and write back to TUN
                        byte[] ipResponse = wrapInIpUdp(
                                packet.array(), len,
                                response.getData(), response.getLength()
                        );
                        if (ipResponse != null) {
                            synchronized (out) {
                                out.write(ipResponse);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "DNS forward error: " + e.getMessage());
                    }
                });

                maybeFlushDnsLog();
            }
        } catch (Exception e) {
            Log.e(TAG, "VPN loop error: " + e.getMessage());
        } finally {
            closeTunnel();
        }
    }

    // ── DNS packet parsing ────────────────────────────────────────────────────

    private boolean isUdpDns(byte[] pkt, int len) {
        if (len < 28) return false;
        int ipVersion = (pkt[0] >> 4) & 0xF;
        if (ipVersion != 4) return false;
        int protocol = pkt[9] & 0xFF;  // 17 = UDP
        if (protocol != 17) return false;
        int ipHeaderLen = (pkt[0] & 0x0F) * 4;
        if (len < ipHeaderLen + 8) return false;
        int destPort = ((pkt[ipHeaderLen + 2] & 0xFF) << 8) | (pkt[ipHeaderLen + 3] & 0xFF);
        return destPort == DNS_PORT;
    }

    private byte[] extractDnsPayload(byte[] pkt, int len) {
        int ipHeaderLen = (pkt[0] & 0x0F) * 4;
        int udpHeaderLen = 8;
        int dnsStart = ipHeaderLen + udpHeaderLen;
        if (len < dnsStart + 12) return null;
        int dnsLen = len - dnsStart;
        byte[] dns = new byte[dnsLen];
        System.arraycopy(pkt, dnsStart, dns, 0, dnsLen);
        return dns;
    }

    private String extractDomain(byte[] pkt, int len) {
        try {
            int ipHeaderLen = (pkt[0] & 0x0F) * 4;
            int dnsStart = ipHeaderLen + 8; // skip UDP header
            if (len < dnsStart + 12) return null;
            // DNS questions start at byte 12 of the DNS header
            int pos = dnsStart + 12;
            StringBuilder domain = new StringBuilder();
            while (pos < len) {
                int labelLen = pkt[pos] & 0xFF;
                if (labelLen == 0) break;
                if (domain.length() > 0) domain.append('.');
                pos++;
                for (int i = 0; i < labelLen && pos < len; i++, pos++) {
                    domain.append((char) (pkt[pos] & 0xFF));
                }
            }
            return domain.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isDomainBlocked(String domain) {
        for (String rule : blockedDomains) {
            if (domain.equals(rule) || domain.endsWith("." + rule)) {
                return true;
            }
        }
        return false;
    }

    // ── Packet building ───────────────────────────────────────────────────────

    /**
     * Build an NXDOMAIN (name not found) response to send back to the TUN
     * without forwarding to upstream. Swaps src/dst, sets QR=1 RCODE=3.
     */
    private byte[] buildNxDomainResponse(byte[] origPkt, int origLen) {
        try {
            int ipHeaderLen = (origPkt[0] & 0x0F) * 4;
            byte[] origDns = extractDnsPayload(origPkt, origLen);
            if (origDns == null) return null;

            // Flip QR bit, set RCODE = 3 (NXDOMAIN), zero ANCOUNT/NSCOUNT/ARCOUNT
            byte[] dns = origDns.clone();
            dns[2] = (byte) 0x81; // QR=1, OPCODE=0, AA=0, TC=0, RD=1
            dns[3] = (byte) 0x83; // RA=1, RCODE=3
            dns[6] = 0; dns[7] = 0; // ANCOUNT=0
            dns[8] = 0; dns[9] = 0; // NSCOUNT=0
            dns[10] = 0; dns[11] = 0; // ARCOUNT=0

            return wrapInIpUdpRaw(origPkt, ipHeaderLen, dns);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Wrap a real upstream DNS response in IP+UDP headers for writing back to TUN.
     * Swaps src/dst IP and ports from the original query.
     */
    private byte[] wrapInIpUdp(byte[] origPkt, int origLen, byte[] dnsPayload, int dnsLen) {
        try {
            int ipHeaderLen = (origPkt[0] & 0x0F) * 4;
            byte[] dns = new byte[dnsLen];
            System.arraycopy(dnsPayload, 0, dns, 0, dnsLen);
            return wrapInIpUdpRaw(origPkt, ipHeaderLen, dns);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] wrapInIpUdpRaw(byte[] origPkt, int ipHeaderLen, byte[] dns) {
        int totalLen = 20 + 8 + dns.length; // fixed 20-byte IP + 8-byte UDP
        byte[] resp = new byte[totalLen];

        // IP header (simplified, no options)
        resp[0] = 0x45; // version 4, IHL 5
        resp[1] = 0;
        resp[2] = (byte) ((totalLen >> 8) & 0xFF);
        resp[3] = (byte) (totalLen & 0xFF);
        resp[4] = origPkt[4]; resp[5] = origPkt[5]; // ID
        resp[6] = 0x40; resp[7] = 0; // DF, no fragment
        resp[8] = 64; // TTL
        resp[9] = 17; // UDP
        // Src IP = original dst IP (the fake DNS server), Dst IP = original src IP
        System.arraycopy(origPkt, 16, resp, 12, 4); // orig dst → new src
        System.arraycopy(origPkt, 12, resp, 16, 4); // orig src → new dst
        // IP checksum
        int ipCsum = checksum(resp, 0, 20);
        resp[10] = (byte) ((ipCsum >> 8) & 0xFF);
        resp[11] = (byte) (ipCsum & 0xFF);

        // UDP header — swap src/dst ports
        resp[20] = origPkt[ipHeaderLen + 2]; resp[21] = origPkt[ipHeaderLen + 3]; // orig dst port (53) → src
        resp[22] = origPkt[ipHeaderLen + 0]; resp[23] = origPkt[ipHeaderLen + 1]; // orig src port → dst
        int udpLen = 8 + dns.length;
        resp[24] = (byte) ((udpLen >> 8) & 0xFF);
        resp[25] = (byte) (udpLen & 0xFF);
        resp[26] = 0; resp[27] = 0; // checksum optional for IPv4

        System.arraycopy(dns, 0, resp, 28, dns.length);
        return resp;
    }

    private int checksum(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length - 1; i += 2) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
        }
        if (length % 2 != 0) sum += (data[offset + length - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    private void maybeFlushDnsLog() {
        long now = System.currentTimeMillis();
        if (now - lastFlushMs < BATCH_INTERVAL_MS) return;
        lastFlushMs = now;
        List<String> batch;
        synchronized (dnsLogBatch) {
            if (dnsLogBatch.isEmpty()) return;
            batch = new ArrayList<>(dnsLogBatch);
            dnsLogBatch.clear();
        }
        executor.submit(() -> flushDnsLog(batch));
    }

    private void flushDnsLog(List<String> domains) {
        try {
            JSONArray arr = new JSONArray();
            for (String d : domains) arr.put(d);
            JSONObject body = new JSONObject();
            body.put("dns_domains", arr);
            RequestBody rb = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            Request req = new Request.Builder()
                    .url(flaskUrl + "/api/devices/" + serial + "/network_usage")
                    .post(rb).build();
            httpClient.newCall(req).execute().close();
            Log.d(TAG, "Flushed " + domains.size() + " DNS domains");
        } catch (Exception e) {
            Log.w(TAG, "DNS flush error: " + e.getMessage());
        }
    }

    private void closeTunnel() {
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception ignored) {}
    }

    private String loadFlaskUrl() {
        try {
            java.io.InputStream is = getAssets().open("config.json");
            byte[] buf = new byte[is.available()];
            is.read(buf); is.close();
            return new JSONObject(new String(buf)).getString("flask_url");
        } catch (Exception e) {
            return "https://zeroaxis.live";
        }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "ZeroAxis DNS Filter",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ZeroAxis DNS Filter")
                .setContentText("DNS monitoring active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}