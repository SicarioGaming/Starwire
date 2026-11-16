package com.limelight.binding.audio.securemic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.ECPrivateKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The encrypted microphone with the device's own crypto (its AES-GCM, which is Conscrypt's on
 * Android 6.0 and later and BouncyCastle's on 5.0 and 5.1, and the platform's ECDH): every line
 * of the vectors the PC's receiver is tested against, the real sender (its own thread and a UDP
 * socket) to a receiver in this test on 127.0.0.1, and what sealing costs here. The cost goes to
 * secure-mic-bench.txt in the app's external files folder.
 */
@RunWith(AndroidJUnit4.class)
public class SecureMicDeviceTest {
    private static final byte[] PAIR_KEY = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final byte[] DEVICE_ID = hex("a0a1a2a3a4a5a6a7");

    @Test
    public void theSharedVectorsHoldOnThisDevice() throws Exception {
        byte[] file = resource("swmc-v1-vectors.txt");
        assertEquals("bc79b6d1e099e89bbfc93125a987afb96a02bd5bfe8f4480ced36b369bfd810a",
                hex(MessageDigest.getInstance("SHA-256").digest(file)));
        Map<String, String> want = new LinkedHashMap<>();
        for (String line : new String(file, StandardCharsets.US_ASCII).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                want.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        Map<String, String> got = new LinkedHashMap<>();

        byte[] ct = new byte[76];
        new Aead(hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")).seal(
                hex("cafebabefacedbaddecaf888"), 0, hex("feedfacedeadbeeffeedfacedeadbeefabaddad2"), 0, 20,
                hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"),
                0, 60, ct, 0);
        got.put("kat.gcm_tc16", hex(ct));
        got.put("kat.hkdf_rfc5869_1", hex(SwmcCodec.hkdf(hex("000102030405060708090a0b0c"),
                hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)));
        byte[] phonePoint = point("DAD0B65394221CF9B051E1FECA5787D098DFE637FC90B9EF945D0C3772581180",
                "5271A0461CDB8252D61F1C456FA3E59AB1F45B33ACCF5F58389E0577B8990BB3");
        byte[] receiverPoint = point("D12DFB5289C8D4F81208B70270398C342296970A0BCCB74C736FC7554494BF63",
                "56FBF3CA366CC23E8157854C13C58D6AAC23F046ADA30F8353E74F33039872AB");
        byte[] z = SwmcCodec.ecdhHash(privateKey("C88F01F510D9AC3F70A292DAA2316DE544E9AAB8AFE84049C62A9C57862D1433"),
                SwmcCodec.decodePoint(receiverPoint, 0));
        byte[] zr = SwmcCodec.ecdhHash(privateKey("C6EF9C5D78AE012A011164ACB397CE2088685D8F06BF9BE0B283AB46476BEE53"),
                SwmcCodec.decodePoint(phonePoint, 0));
        assertArrayEquals("ECDH both ways", z, zr);
        got.put("kat.ecdh_rfc5903_sha256", hex(z));

        byte[] clientRandom = hex("c0c1c2c3c4c5c6c7");
        byte[] serverRandom = hex("5051525354555657");
        byte[] kAuth = SwmcCodec.authKey(PAIR_KEY, DEVICE_ID);
        byte[] kAudio = SwmcCodec.audioKey(PAIR_KEY, DEVICE_ID, clientRandom, serverRandom);
        got.put("key.auth", hex(kAuth));
        got.put("key.audio", hex(kAudio));
        got.put("pkt.hello", hex(SwmcCodec.hello(kAuth, DEVICE_ID, clientRandom)));
        got.put("pkt.hello_ack", hex(SwmcCodec.helloAck(kAuth, DEVICE_ID, clientRandom, serverRandom, 0)));
        got.put("pkt.rehello", hex(SwmcCodec.rehello(kAuth, DEVICE_ID, serverRandom)));
        byte[] vban = vbanFrame(7, 0);
        got.put("vban.frame7", hex(vban));
        byte[] out = new byte[SwmcCodec.MAX_AUDIO_LENGTH];
        int n = SwmcCodec.audio(new Aead(kAudio), DEVICE_ID, serverRandom, 7, vban, vban.length, out);
        got.put("pkt.audio7", hex(Arrays.copyOf(out, n)));
        n = SwmcCodec.audio(new Aead(kAudio), DEVICE_ID, serverRandom, SwmcCodec.MAX_COUNTER, vban, vban.length, out);
        got.put("pkt.audio_last", hex(Arrays.copyOf(out, n)));
        byte[] code = hex("11121314151617181920");
        got.put("pair.code", PairingCode.encode(code));
        byte[] kCode = SwmcCodec.codeKey(code);
        got.put("pair.code_key", hex(kCode));
        byte[] nonce = hex("7071727374757677");
        got.put("pkt.pair_request", hex(SwmcCodec.pairRequest(kCode, DEVICE_ID, nonce, phonePoint, "Test phone")));
        byte[] kPair = SwmcCodec.pairKey(kCode, z, DEVICE_ID, nonce, phonePoint, receiverPoint);
        got.put("pair.key", hex(kPair));
        got.put("pkt.pair_accept", hex(SwmcCodec.pairAccept(kCode, kPair, DEVICE_ID, nonce, receiverPoint,
                hex("b0b1b2b3b4b5b6b7"), "TEST-PC")));

        assertEquals(want, got);
        // And this device's GCM opens the vectors' packet
        byte[] pcm = new byte[2048];
        byte[] audio7 = hex(want.get("pkt.audio7"));
        assertEquals(540, SwmcCodec.openAudio(new Aead(kAudio), audio7, audio7.length, pcm));
        audio7[100] ^= 1;
        assertEquals(-1, SwmcCodec.openAudio(new Aead(kAudio), audio7, audio7.length, pcm));
    }

    // The real sender, with its own thread and socket, to a receiver on this device's loopback
    @Test
    public void theSenderStreamsToALoopbackReceiver() throws Exception {
        LoopbackReceiver receiver = new LoopbackReceiver();
        receiver.start();
        CountDownLatch sending = new CountDownLatch(1);
        SecureVbanSender sender = new SecureVbanSender(InetAddress.getByName("127.0.0.1"), receiver.port(),
                new MicPairing("test", "Test PC", DEVICE_ID, PAIR_KEY, new byte[8], "TEST-PC", 0), status -> {
                    if (status == SecureVbanSender.STATUS_SENDING) {
                        sending.countDown();
                    }
                });
        try {
            assertEquals(0, sender.open());
            assertTrue("the receiver answered the HELLO", sending.await(3, TimeUnit.SECONDS));
            // 2 seconds of a ramp, in the 20 ms pieces the microphone gives
            short[] chunk = new short[960];
            int total = 375 * 256;
            for (int done = 0; done < total; done += chunk.length) {
                for (int i = 0; i < chunk.length; i++) {
                    chunk[i] = (short) ((done + i) * 37 - 16000);
                }
                sender.write(chunk, chunk.length);
                // Faster than real time, but not so fast that loopback UDP runs out of buffer
                Thread.sleep(5);
            }
            assertTrue("all 375 packets arrived", receiver.await(375, 5000));
        }
        finally {
            sender.close();
            receiver.stop();
        }
        for (int p = 0; p < 375; p++) {
            assertArrayEquals("packet " + p, vbanFrame(p, p * 256), receiver.played.get(p));
            assertEquals(p, (long) receiver.counters.get(p));
        }
        assertEquals(0, receiver.refused);
    }

    // What sealing costs with this device's provider; sealing must take well under the 5.3 ms
    // between packets
    @Test
    public void whatEncryptionCostsHere() throws Exception {
        byte[] kAudio = SwmcCodec.audioKey(PAIR_KEY, DEVICE_ID, new byte[8], new byte[8]);
        Aead seal = new Aead(kAudio);
        Aead open = new Aead(kAudio);
        byte[] vban = vbanFrame(0, 0);
        byte[] packet = new byte[SwmcCodec.MAX_AUDIO_LENGTH];
        byte[] pcm = new byte[2048];
        int n = 0;
        for (int i = 0; i < 2000; i++) {
            n = SwmcCodec.audio(seal, DEVICE_ID, new byte[8], i, vban, vban.length, packet);
            SwmcCodec.openAudio(open, packet, n, pcm);
        }
        int runs = 20000;
        long t0 = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            n = SwmcCodec.audio(seal, DEVICE_ID, new byte[8], 2000 + i, vban, vban.length, packet);
        }
        long t1 = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            SwmcCodec.openAudio(open, packet, n, pcm);
        }
        long t2 = System.nanoTime();
        int sessions = 500;
        for (int i = 0; i < sessions; i++) {
            new Aead(SwmcCodec.audioKey(PAIR_KEY, DEVICE_ID, new byte[8], new byte[8]));
        }
        long t3 = System.nanoTime();
        int pairings = 50;
        SecureRandom random = new SecureRandom();
        byte[] peer = point("D12DFB5289C8D4F81208B70270398C342296970A0BCCB74C736FC7554494BF63",
                "56FBF3CA366CC23E8157854C13C58D6AAC23F046ADA30F8353E74F33039872AB");
        for (int i = 0; i < pairings; i++) {
            KeyPair mine = SwmcCodec.newKeyPair(random);
            SwmcCodec.ecdhHash(mine.getPrivate(), SwmcCodec.decodePoint(peer, 0));
        }
        long t4 = System.nanoTime();

        double sealUs = (t1 - t0) / 1000.0 / runs;
        double openUs = (t2 - t1) / 1000.0 / runs;
        double sessionUs = (t3 - t2) / 1000.0 / sessions;
        double pairMs = (t4 - t3) / 1e6 / pairings;
        String report = String.format(Locale.ROOT,
                "device %s %s, Android %s (API %d), AES-GCM from %s%n"
                        + "seal %.2f us per 540-byte VBAN packet (584 on the wire), open %.2f us, %d runs each%n"
                        + "at 187.5 packets a second, sealing takes %.3f%% of one core%n"
                        + "new session (HKDF + AES key) %.1f us; pairing (new P-256 key + ECDH) %.2f ms%n",
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, seal.providerName(),
                sealUs, openUs, runs, sealUs * 187.5 / 1e4, sessionUs, pairMs);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = context.getExternalFilesDir(null);
        if (dir != null) {
            try (FileOutputStream out = new FileOutputStream(new File(dir, "secure-mic-bench.txt"))) {
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
        }
        assertTrue(report, sealUs < 2666);
    }

    // ---------------------------------------------------------------- a receiver, as the spec says
    private static final class LoopbackReceiver implements Runnable {
        private final DatagramSocket socket;
        private final Thread thread = new Thread(this, "TestMicReceiver");
        final List<byte[]> played = new ArrayList<>();
        final List<Long> counters = new ArrayList<>();
        int refused;
        private volatile boolean stopped;

        LoopbackReceiver() throws Exception {
            socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(100);
            socket.setReceiveBufferSize(1 << 20);
        }

        int port() {
            return socket.getLocalPort();
        }

        void start() {
            thread.start();
        }

        void stop() throws InterruptedException {
            stopped = true;
            socket.close();
            thread.join(2000);
        }

        synchronized boolean await(int packets, long timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            while (played.size() < packets) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                wait(left);
            }
            return true;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[2048];
            byte[] pcm = new byte[2048];
            Aead aead = null;
            byte[] session = null;
            long highest = -1;
            try {
                byte[] authKey = SwmcCodec.authKey(PAIR_KEY, DEVICE_ID);
                while (!stopped) {
                    DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(in);
                    }
                    catch (SocketTimeoutException e) {
                        continue;
                    }
                    int length = in.getLength();
                    int type = SwmcCodec.typeOf(buffer, length);
                    if (type == SwmcCodec.TYPE_HELLO && length == SwmcCodec.HELLO_LENGTH && SwmcCodec.macOk(authKey, buffer, length)) {
                        byte[] clientRandom = Arrays.copyOfRange(buffer, 16, 24);
                        if (session == null) {
                            session = new byte[8];
                            new SecureRandom().nextBytes(session);
                            aead = new Aead(SwmcCodec.audioKey(PAIR_KEY, DEVICE_ID, clientRandom, session));
                        }
                        byte[] ack = SwmcCodec.helloAck(authKey, DEVICE_ID, clientRandom, session, SwmcCodec.STATUS_READY);
                        socket.send(new DatagramPacket(ack, ack.length, in.getSocketAddress()));
                    }
                    else if (type == SwmcCodec.TYPE_AUDIO && aead != null && SwmcCodec.contextIs(buffer, session)) {
                        int n = SwmcCodec.openAudio(aead, buffer, length, pcm);
                        long counter = SwmcCodec.getU32(buffer, SwmcCodec.COUNTER_AT);
                        synchronized (this) {
                            if (n < 0 || counter <= highest) {
                                refused++;
                                continue;
                            }
                            highest = counter;
                            played.add(Arrays.copyOf(pcm, n));
                            counters.add(counter);
                            notifyAll();
                        }
                    }
                    else {
                        synchronized (this) {
                            refused++;
                        }
                    }
                }
            }
            catch (Exception e) {
                if (!stopped) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers
    private static byte[] resource(String name) throws Exception {
        try (InputStream in = SecureMicDeviceTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new AssertionError(name + " isn't in the test APK");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static PrivateKey privateKey(String d) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, hex(d)), SwmcCodec.p256()));
    }

    private static byte[] point(String x, String y) {
        byte[] p = new byte[65];
        p[0] = 4;
        System.arraycopy(hex(x), 0, p, 1, 32);
        System.arraycopy(hex(y), 0, p, 33, 32);
        return p;
    }

    // As VbanSender packs it: 256 samples of a ramp, stream "Stream1"
    private static byte[] vbanFrame(int frame, int offset) {
        byte[] b = new byte[28 + 512];
        b[0] = 'V';
        b[1] = 'B';
        b[2] = 'A';
        b[3] = 'N';
        b[4] = 3;
        b[5] = (byte) 255;
        b[7] = 1;
        byte[] name = "Stream1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, b, 8, name.length);
        SwmcCodec.putU32(b, 24, frame);
        for (int i = 0; i < 256; i++) {
            short s = (short) ((i + offset) * 37 - 16000);
            b[28 + i * 2] = (byte) s;
            b[29 + i * 2] = (byte) (s >> 8);
        }
        return b;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
