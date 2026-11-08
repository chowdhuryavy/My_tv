package com.avy.tvhome;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal ADB client that talks to this TV's own debugging service (127.0.0.1:5555),
 * the same connection the PC uses. Lets the home screen run a few shell commands itself
 * (turn the Google TV launcher on/off so Settings > Accounts & Profiles works).
 * The first connection shows "Allow USB debugging?" on the TV; choose "Always allow".
 */
final class LocalAdb {

    private static final String TAG = "AvyAdb";
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private LocalAdb() { }

    /** One-off shell command (opens and closes its own connection). Blocks; call off the main thread. */
    static String shell(Context c, String command, int timeoutMs) throws Exception {
        try (Session s = open(c, timeoutMs)) { return s.shell(command); }
    }

    /**
     * Opens one authenticated connection that can run many commands. Keeping it open avoids
     * the TV asking "Allow USB debugging?" again for every command.
     */
    static synchronized Session open(Context c, int timeoutMs) throws Exception {
        KeyPair keys = keys(c);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(timeoutMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            send(out, CNXN, VERSION, MAX_DATA, "host::avytvhome\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new Exception("unexpected reply");
                if (!sentSig) {             // try our key (works once "Always allow" was chosen)
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    sentSig = true;
                } else if (!sentKey) {      // not known yet: offer the public key -> TV asks the user
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload((RSAPublicKey) keys.getPublic()));
                    sentKey = true;
                    s.setSoTimeout(90_000); // give the person time to press "Allow" on the TV
                } else {
                    throw new Exception("debugging not allowed");
                }
            }
            s.setSoTimeout(timeoutMs);
            return new Session(s, in, out);
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    /** An open, authenticated connection; run shell commands one after another, then close. */
    static final class Session implements AutoCloseable {
        private final Socket sock;
        private final DataInputStream in;
        private final OutputStream out;
        private int nextId = 1;

        Session(Socket sock, DataInputStream in, OutputStream out) { this.sock = sock; this.in = in; this.out = out; }

        synchronized String shell(String command) throws Exception {
            int localId = nextId++;
            send(out, OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remoteId = 0;
            while (true) {
                Msg m = read(in);
                if (m.arg1 != localId && m.cmd != CLSE) continue; // ignore stray packets
                if (m.cmd == OKAY) { remoteId = m.arg0; continue; }
                if (m.cmd == WRTE) {
                    result.write(m.data);
                    send(out, OKAY, localId, m.arg0, new byte[0]);
                    continue;
                }
                if (m.cmd == CLSE) {
                    if (m.arg1 != localId) continue;
                    send(out, CLSE, localId, remoteId == 0 ? m.arg0 : remoteId, new byte[0]);
                    break;
                }
            }
            return result.toString("UTF-8");
        }

        @Override public void close() { try { sock.close(); } catch (Exception ignored) { } }
    }

    // ------------------------------------------------------------------ protocol

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws Exception {
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static Msg read(DataInputStream in) throws Exception {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------------ keys

    private static KeyPair keys(Context c) throws Exception {
        File pri = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pri.isFile() && pub.isFile()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(pri))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(pri)) { o.write(kp.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(pub)) { o.write(kp.getPublic().getEncoded()); }
        Log.i(TAG, "created debugging key");
        return kp;
    }

    /** adb signs the 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5). */
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher cph = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cph.init(Cipher.ENCRYPT_MODE, key);
        cph.update(SHA1_DIGEST_INFO);
        cph.update(token);
        return cph.doFinal();
    }

    /** Android's adb public key format: base64(struct RSAPublicKey) + " user@host\0". */
    private static byte[] publicKeyPayload(RSAPublicKey pub) {
        final int words = 64; // 2048-bit
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Avy-TV-Home@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }
}
