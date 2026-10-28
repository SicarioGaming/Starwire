package com.limelight.binding.usb.tunnel;

import static com.limelight.binding.usb.tunnel.SwutVectors.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.limelight.R;
import com.limelight.binding.audio.securemic.MicPairing;
import com.limelight.binding.audio.securemic.SwmcCodec;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSocket;

/**
 * Starwire's end of the USB tunnel (docs/dev/usb-protocol.md), against a fake helper and a fake
 * exporter on 127.0.0.1, with a clock that only moves when the test moves it: the vectors' bytes
 * on a real TLS connection, every answer that mustn't share anything (a wrong MAC, the mic's key,
 * another certificate, someone in the middle, a key that isn't P-256, weak TLS, no version in
 * common, REFUSE), the relay both ways, and every way a session ends. Each test runs on the JDK's
 * TLS and on Conscrypt, the BoringSSL-based TLS Android uses (TlsProvider).
 */
@RunWith(Parameterized.class)
public class UsbTunnelTest {
    private static final byte[] RECEIVER_ID = hex("b0b1b2b3b4b5b6b7");
    // The RFC 5903 private keys the vectors' certificates were made with
    private static final byte[] PHONE_D = hex("c88f01f510d9ac3f70a292daa2316de544e9aab8afe84049c62a9c57862d1433");
    private static final byte[] HELPER_D = hex("c6ef9c5d78ae012a011164acb397ce2088685d8f06bf9be0b283ab46476bee53");

    @Parameterized.Parameters(name = "{0}")
    public static Object[] providers() {
        return new Object[] {TlsProvider.JDK, TlsProvider.CONSCRYPT};
    }

    @Parameterized.Parameter
    public String provider;

    private final AtomicLong now = new AtomicLong(5_000_000);
    // Every look at the clock, so a test can wait until the watchdog has seen the time it set
    private final AtomicLong reads = new AtomicLong();
    private final UsbTunnel.Clock clock = () -> {
        reads.incrementAndGet();
        return now.get();
    };
    private final List<Closeable> closeLater = new ArrayList<>();
    private TlsProvider tls;
    private FakeHelper helper;
    private FakeExporter exporter;
    private TunnelEvents events;
    private UsbTunnel tunnel;

    @Before
    public void setUp() throws Exception {
        tls = TlsProvider.use(provider);
        helper = new FakeHelper(SwutTls.newIdentity(seeded(2)));
        exporter = new FakeExporter(v("frame.share"));
        events = new TunnelEvents();
        tunnel = tunnel(SwutTls::newIdentity, seeded(1));
    }

    @After
    public void tearDown() throws Exception {
        try {
            // A failed test mustn't leave its tunnel holding the app's one share
            if (tunnel.isConnected()) {
                tunnel.networkLost();
                events.awaitEnded();
            }
            helper.close();
            exporter.close();
            for (Closeable c : closeLater) {
                c.close();
            }
        }
        finally {
            tls.close();
        }
    }

    // ---------------------------------------------------------------- set-up
    private UsbTunnel tunnel(UsbTunnel.Identities identities, SecureRandom random) {
        return new UsbTunnel(exporter, events, clock, random, identities, 2);
    }

    private static SecureRandom seeded(long seed) throws Exception {
        return TlsProvider.seeded(seed);
    }

    // Gives this nonce whenever the tunnel asks for random bytes (only the BIND's nonce, with the vectors' certificates)
    private static SecureRandom fixed(byte[] nonce) {
        return new SecureRandom() {
            @Override
            public synchronized void nextBytes(byte[] b) {
                System.arraycopy(nonce, 0, b, 0, b.length);
            }
        };
    }

    private static byte[] v(String name) throws Exception {
        Map<String, String> vectors = SwutVectors.read();
        assertTrue(name + " isn't in the vectors file", vectors.containsKey(name));
        return hex(vectors.get(name));
    }

    private static MicPairing pairing() {
        return new MicPairing("pc-uuid", "PC", SwutVectors.DEVICE_ID, SwutVectors.PAIR_KEY, RECEIVER_ID, "Receiver", 1);
    }

    private static byte[] usbKey() throws Exception {
        return SwutCodec.usbKey(SwutVectors.PAIR_KEY, SwutVectors.DEVICE_ID);
    }

    private static byte[] hello() {
        return SwutCodec.hello(SwutVectors.HELPER_NONCE, 1, 1);
    }

    private void connect() {
        tunnel.connect("127.0.0.1", helper.port(), pairing());
    }

    // The helper's side up to BIND: TLS, HELLO, and a BIND that checks out
    private byte[] bindFor(byte[] hello) throws Exception {
        helper.accept();
        helper.send(hello);
        byte[] bind = helper.read();
        assertTrue("the BIND doesn't check out", SwutCodec.bindChecksOut(usbKey(), hello, helper.phoneFp(), helper.helperFp(), bind));
        return bind;
    }

    // ... then a BIND_ACK, and the SHARE the exporter gave
    private byte[] bound() throws Exception {
        byte[] hello = hello();
        byte[] bind = bindFor(hello);
        helper.send(SwutCodec.bindAck(usbKey(), hello, helper.phoneFp(), helper.helperFp(), bind));
        byte[] share = helper.read();
        assertArrayEquals("not the exporter's SHARE", exporter.share, share);
        return share;
    }

    // ... then SHARE_ACK 0, and the tunnel's connection to the exporter
    private Socket sharing() throws Exception {
        bound();
        helper.send(SwutCodec.shareAck(SwutCodec.SHARE_ATTACHING, 0));
        Socket local = exporter.accept();
        // Held until the test ends: a socket nothing refers to is closed when it's collected,
        // which the tunnel would rightly take as the exporter going away
        closeLater.add(local);
        events.awaitSharing();
        return local;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        for (int at = 0; at < n; ) {
            int got = in.read(b, at, n - at);
            if (got < 0) {
                throw new IOException("closed after " + at + " bytes");
            }
            at += got;
        }
        return b;
    }

    private static boolean closedByPeer(Socket s) {
        try {
            return s.getInputStream().read() < 0;
        }
        catch (IOException e) {
            return true;
        }
    }

    private static void assertNoEnd(List<byte[]> frames) {
        for (byte[] f : frames) {
            assertNotEquals("the tunnel sent an END", SwutCodec.TYPE_END, SwutCodec.typeOf(f));
        }
    }

    // The tunnel ended this way, and never asked the exporter for the device
    private UsbTunnel.Ending endedWithoutTheDevice(int kind) throws Exception {
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(e.toString(), kind, e.kind);
        assertEquals("the exporter was started", 0, exporter.starts.get());
        assertEquals(0, exporter.connects.get());
        assertEquals(0, exporter.stops.get());
        assertFalse(exporter.anyoneConnected());
        assertFalse(e.canShareAgain());
        assertFalse(tunnel.isConnected());
        return e;
    }

    private static SwutTls.Identity phoneVectors() throws Exception {
        return FakeHelper.identity(v("cert.phone"), PHONE_D);
    }

    private static SwutTls.Identity helperVectors() throws Exception {
        return FakeHelper.identity(v("cert.helper"), HELPER_D);
    }

    // A self-signed certificate with another kind of key, for the helper
    private static SwutTls.Identity otherKey(String algorithm, Object spec, String signature) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance(algorithm);
        if (spec instanceof Integer) {
            g.initialize((Integer) spec, seeded(7));
        }
        else {
            g.initialize((ECGenParameterSpec) spec, seeded(7));
        }
        KeyPair pair = g.generateKeyPair();
        X500Name name = new X500Name("CN=Starwire");
        byte[] der = new JcaX509v3CertificateBuilder(name, BigInteger.ONE, new Date(SwutTls.NOT_BEFORE_MS),
                new Date(SwutTls.NOT_AFTER_MS), name, pair.getPublic())
                .build(new JcaContentSignerBuilder(signature).build(pair.getPrivate())).getEncoded();
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return new SwutTls.Identity(cert, pair.getPrivate());
    }

    // ---------------------------------------------------------------- the vectors, over TLS
    // With the vectors' certificates and nonce, the BIND on the wire is frame.bind byte for byte,
    // the vectors' BIND_ACK is taken, and SHARE, DATA and END are the vectors' too
    @Test
    public void theVectorsHandshakeAndRelayGoThroughTls() throws Exception {
        helper.close();
        helper = new FakeHelper(helperVectors());
        SwutTls.Identity phone = phoneVectors();
        tunnel = tunnel(random -> phone, fixed(SwutVectors.PHONE_NONCE));
        connect();
        helper.accept();
        assertArrayEquals(v("fp.phone"), helper.phoneFp());
        assertArrayEquals(v("fp.helper"), helper.helperFp());
        helper.send(v("frame.hello"));
        assertArrayEquals(v("frame.bind"), helper.read());
        helper.send(v("frame.bind_ack"));
        assertArrayEquals(v("frame.share"), helper.read());
        helper.send(v("frame.share_ack"));
        Socket local = exporter.accept();
        closeLater.add(local);
        events.awaitSharing();

        // The driver's OP_REQ_IMPORT reaches the exporter as it is
        helper.send(v("frame.data_import"));
        assertArrayEquals(SwutVectors.opReqImport(SwutVectors.BUS_ID), readN(local.getInputStream(), 40));
        // And the exporter's answer reaches the helper in a DATA frame
        byte[] reply = hex("0111000300000000");
        local.getOutputStream().write(reply);
        assertArrayEquals(SwutCodec.data(reply, 0, reply.length), helper.read());

        tunnel.close();
        assertArrayEquals(v("frame.end_stopped"), helper.read());
        assertEquals(UsbTunnel.Ending.STOPPED, events.awaitEnded().kind);
    }

    // The vectors' BIND_ACK for the other BIND (the other phone nonce) doesn't count for this one
    @Test
    public void theVectorsBindAckForAnotherBindIsRefused() throws Exception {
        helper.close();
        helper = new FakeHelper(helperVectors());
        SwutTls.Identity phone = phoneVectors();
        tunnel = tunnel(random -> phone, fixed(SwutVectors.PHONE_NONCE));
        connect();
        helper.accept();
        helper.send(v("frame.hello"));
        assertArrayEquals(v("frame.bind"), helper.read());
        helper.send(v("refuse.bind_ack_other_bind"));
        endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
        assertNoEnd(helper.readUntilClosed());
    }

    // ---------------------------------------------------------------- answers that share nothing
    @Test
    public void aBindAckWithAWrongMacIsRefused() throws Exception {
        connect();
        byte[] hello = hello();
        byte[] bind = bindFor(hello);
        byte[] ack = SwutCodec.bindAck(usbKey(), hello, helper.phoneFp(), helper.helperFp(), bind);
        ack[ack.length - 1] ^= 1;
        helper.send(ack);
        endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
        assertTrue(helper.readUntilClosed().isEmpty());
    }

    // Someone with only the microphone's K_auth can't make a BIND_ACK
    @Test
    public void aBindAckUnderTheMicrophonesKeyIsRefused() throws Exception {
        connect();
        byte[] hello = hello();
        byte[] bind = bindFor(hello);
        byte[] micKey = SwmcCodec.authKey(SwutVectors.PAIR_KEY, SwutVectors.DEVICE_ID);
        helper.send(SwutCodec.bindAck(micKey, hello, helper.phoneFp(), helper.helperFp(), bind));
        endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
    }

    // A BIND_ACK made for another helper certificate than the one this TLS session showed
    @Test
    public void aBindAckForAnotherCertificateIsRefused() throws Exception {
        connect();
        byte[] hello = hello();
        byte[] bind = bindFor(hello);
        byte[] otherFp = SwutCodec.fingerprint(SwutTls.newIdentity(seeded(9)).certificate.getEncoded());
        helper.send(SwutCodec.bindAck(usbKey(), hello, helper.phoneFp(), otherFp, bind));
        endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
    }

    // Someone in the middle ends TLS on both sides with certificates of their own. The helper's
    // BIND check fails, so it refuses; and a BIND_ACK made over its own view doesn't count either.
    @Test
    public void someoneInTheMiddleGetsNoDevice() throws Exception {
        for (boolean helperChecks : new boolean[] {true, false}) {
            Middle middle = new Middle(SwutTls.newIdentity(seeded(11)), SwutTls.newIdentity(seeded(12)), helper.port());
            closeLater.add(middle);
            tunnel.connect("127.0.0.1", middle.port(), pairing());
            byte[] hello = hello();
            helper.accept();
            helper.send(hello);
            byte[] bind = helper.read();
            assertFalse(SwutCodec.bindChecksOut(usbKey(), hello, helper.phoneFp(), helper.helperFp(), bind));
            if (helperChecks) {
                helper.send(SwutCodec.refuse(SwutCodec.REFUSE_NOT_PAIRED));
                UsbTunnel.Ending e = endedWithoutTheDevice(UsbTunnel.Ending.REFUSED);
                assertEquals(SwutCodec.REFUSE_NOT_PAIRED, e.code);
            }
            else {
                helper.send(SwutCodec.bindAck(usbKey(), hello, helper.phoneFp(), helper.helperFp(), bind));
                endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
            }
            helper.readUntilClosed();
        }
    }

    // The helper's certificate must have a P-256 key, and come alone
    @Test
    public void aHelperCertificateThatIsntOneP256KeyIsRefused() throws Exception {
        SwutTls.Identity p256 = SwutTls.newIdentity(seeded(3));
        SwutTls.Identity[] wrong = {
                otherKey("RSA", 2048, "SHA256withRSA"),
                otherKey("EC", new ECGenParameterSpec("secp384r1"), "SHA384withECDSA"),
        };
        for (SwutTls.Identity id : wrong) {
            helper.close();
            helper = new FakeHelper(id);
            connect();
            try {
                helper.accept();
                helper.send(hello());
            }
            catch (IOException expected) {
                // Starwire ended the handshake
            }
            endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
        }
        // Two certificates, the first a good one
        helper.close();
        helper = new FakeHelper(p256);
        helper.chain = new X509Certificate[] {p256.certificate, SwutTls.newIdentity(seeded(4)).certificate};
        connect();
        try {
            helper.accept();
        }
        catch (IOException expected) {
            // As above
        }
        endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
    }

    // TLS 1.2 with CBC only (each such suite this provider has, all of them enabled by default):
    // Starwire offers none of them
    @Test
    public void aHelperThatOnlyOffersCbcIsRefused() throws Exception {
        helper.close();
        helper = new FakeHelper(SwutTls.newIdentity(seeded(5)), new String[] {"TLSv1.2"}, null);
        List<String> cbc = new ArrayList<>();
        for (String suite : helper.server.getEnabledCipherSuites()) {
            if (suite.startsWith("TLS_ECDHE_ECDSA_WITH_") && suite.contains("_CBC_")) {
                cbc.add(suite);
            }
        }
        assertFalse("no CBC suites to offer", cbc.isEmpty());
        helper.server.setEnabledCipherSuites(cbc.toArray(new String[0]));
        connect();
        try {
            helper.accept();
            fail("TLS with " + cbc + " went through");
        }
        catch (IOException expected) {
            // No suite in common
        }
        endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
    }

    // TLS 1.2 works too, with ECDHE_ECDSA and AES-GCM
    @Test
    public void tls12WithEcdheEcdsaAndGcmWorks() throws Exception {
        helper.close();
        helper = new FakeHelper(SwutTls.newIdentity(seeded(5)), new String[] {"TLSv1.2"}, null);
        connect();
        sharing();
        assertEquals("TLSv1.2", helper.socket.getSession().getProtocol());
        assertTrue(helper.socket.getSession().getCipherSuite().startsWith("TLS_ECDHE_ECDSA_WITH_"));
        tunnel.close();
        assertEquals(UsbTunnel.Ending.STOPPED, events.awaitEnded().kind);
    }

    // Starwire refuses a second handshake: BIND tied the session to the first one's certificates.
    // The helper has to be the JDK's TLS, which can ask for one in TLS 1.2 (BoringSSL can't), so
    // this runs on the JDK only: with Conscrypt installed first, the JDK's TLS takes its X25519
    // keys from Conscrypt and can't start at all. So Conscrypt's own refusal (BoringSSL refuses
    // renegotiation by default) isn't tested here.
    @Test
    public void aRenegotiationEndsTheSession() throws Exception {
        assumeTrue(TlsProvider.JDK.equals(provider));
        helper.close();
        helper = new FakeHelper(SwutTls.newIdentity(seeded(5)), new String[] {"TLSv1.2"}, null);
        connect();
        Socket local = sharing();
        helper.socket.startHandshake();
        // The new handshake runs while the helper reads; Starwire cuts it off
        assertNoEnd(helper.readUntilClosed());
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(UsbTunnel.Ending.PROTOCOL_ERROR, e.kind);
        assertTrue(closedByPeer(local));
        assertEquals(1, exporter.stops.get());
    }

    // ---------------------------------------------------------------- versions and REFUSE
    @Test
    public void aHelperThatOnlySpeaksNewerVersionsSaysUpdateStarwire() throws Exception {
        connect();
        helper.accept();
        helper.send(SwutCodec.hello(SwutVectors.HELPER_NONCE, 2, 3));
        UsbTunnel.Ending e = endedWithoutTheDevice(UsbTunnel.Ending.UPDATE_STARWIRE);
        assertEquals(R.string.usb_tunnel_update_starwire, e.messageId());
        assertTrue("a BIND went out", helper.readUntilClosed().isEmpty());
    }

    // It picks the highest version both speak, and the BIND covers the HELLO as sent
    @Test
    public void aHelperThatAlsoSpeaksNewerVersionsGetsVersionOne() throws Exception {
        connect();
        byte[] hello = SwutCodec.hello(SwutVectors.HELPER_NONCE, 1, 3);
        byte[] bind = bindFor(hello);
        assertEquals(1, SwutCodec.bindVersion(bind));
        assertFalse(SwutCodec.bindChecksOut(usbKey(), hello(), helper.phoneFp(), helper.helperFp(), bind));
    }

    @Test
    public void aHelloThatIsntOneIsRefused() throws Exception {
        connect();
        helper.accept();
        byte[] hello = hello();
        hello[4] = 'X';   // not the magic
        helper.send(hello);
        endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
        assertTrue(helper.readUntilClosed().isEmpty());
    }

    // REFUSE's reason is only a hint, but each one gets its own
    @Test
    public void aRefuseEndsItWithItsReasonAsAHint() throws Exception {
        int[][] cases = {
                {SwutCodec.REFUSE_NOT_PAIRED, R.string.usb_tunnel_refused_not_paired},
                {SwutCodec.REFUSE_NO_VERSION, R.string.usb_tunnel_refused_version},
                {SwutCodec.REFUSE_MALFORMED, R.string.usb_tunnel_refused},
                {SwutCodec.REFUSE_NOT_ALLOWED, R.string.usb_tunnel_refused_not_allowed},
        };
        for (int[] c : cases) {
            connect();
            bindFor(hello());
            helper.send(SwutCodec.refuse(c[0]));
            UsbTunnel.Ending e = endedWithoutTheDevice(UsbTunnel.Ending.REFUSED);
            assertEquals(c[0], e.code);
            assertEquals(c[1], e.messageId());
            assertTrue(helper.readUntilClosed().isEmpty());
        }
        // A REFUSE that isn't well formed is no answer at all
        connect();
        bindFor(hello());
        byte[] refuse = SwutCodec.refuse(SwutCodec.REFUSE_NOT_ALLOWED);
        refuse[7] = 1;
        helper.send(refuse);
        endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
    }

    // Any other frame where BIND_ACK should be ends it, without an END (there's no session yet)
    @Test
    public void anotherFrameInsteadOfBindAckIsRefused() throws Exception {
        byte[][] answers = {SwutCodec.shareAck(0, 0), SwutCodec.end(SwutCodec.END_STOPPED, 0), hello(),
                hex("0a000000"), hex("03011000") };
        for (byte[] answer : answers) {
            connect();
            bindFor(hello());
            helper.send(answer);
            endedWithoutTheDevice(UsbTunnel.Ending.NOT_VERIFIED);
            assertTrue(helper.readUntilClosed().isEmpty());
        }
    }

    // ---------------------------------------------------------------- before it connects
    @Test
    public void withoutAPairingItSaysToPairTheMicrophoneFirst() throws Exception {
        tunnel.connect("127.0.0.1", helper.port(), null);
        UsbTunnel.Ending e = endedWithoutTheDevice(UsbTunnel.Ending.NO_PAIRING);
        assertEquals(R.string.usb_tunnel_no_pairing, e.messageId());
        helper.server.setSoTimeout(1);
        assertThrows(java.net.SocketTimeoutException.class, helper.server::accept);
    }

    // A public address is refused before anything is sent (TEST-NET-3, so nothing's there anyway)
    @Test
    public void itOnlyConnectsToHomeNetworkAddresses() throws Exception {
        tunnel.connect("203.0.113.5", helper.port(), pairing());
        UsbTunnel.Ending e = endedWithoutTheDevice(UsbTunnel.Ending.NOT_HOME_NETWORK);
        assertEquals(R.string.usb_tunnel_not_home, e.messageId());
    }

    @Test
    public void nothingListeningMeansItCantReachTheHelper() throws Exception {
        int port = helper.port();
        helper.close();
        tunnel.connect("127.0.0.1", port, pairing());
        endedWithoutTheDevice(UsbTunnel.Ending.CANT_REACH);
    }

    // It works out K_usb from a copy; the pairing the caller holds isn't changed
    @Test
    public void thePairingIsLeftAsItWas() throws Exception {
        MicPairing p = pairing();
        tunnel.connect("127.0.0.1", helper.port(), p);
        bindFor(hello());
        assertArrayEquals(SwutVectors.PAIR_KEY, p.key);
        tunnel.close();
        events.awaitEnded();
    }

    // ---------------------------------------------------------------- the relay
    // The exporter makes the one connection itself, only after SHARE_ACK 0, and the tunnel uses it
    @Test
    public void theTunnelUsesTheOneConnectionTheExporterMade() throws Exception {
        connect();
        bound();
        assertEquals(0, exporter.connects.get());
        helper.send(SwutCodec.shareAck(SwutCodec.SHARE_ATTACHING, 0));
        Socket local = exporter.accept();
        closeLater.add(local);
        events.awaitSharing();
        assertEquals(exporter.authorized, local.getPort());
        assertEquals(InetAddress.getByName("127.0.0.1"), local.getInetAddress());
        assertEquals(1, exporter.starts.get());
        assertEquals(1, exporter.connects.get());
        assertEquals(Arrays.asList("sharing"), events.order());
        byte[] ping = hex("0102");
        helper.send(SwutCodec.data(ping, 0, ping.length));
        assertArrayEquals(ping, readN(local.getInputStream(), ping.length));
    }

    // No connection to the exporter after SHARE_ACK 0: END 2, and the device goes back
    @Test
    public void anExporterThatCantBeConnectedToSendsEndTwo() throws Exception {
        for (int i = 0; i < 2; i++) {
            exporter.failConnect = i == 0 ? new IOException("nothing listening") : null;
            exporter.unconnected = i == 1;
            connect();
            bound();
            helper.send(SwutCodec.shareAck(SwutCodec.SHARE_ATTACHING, 0));
            assertArrayEquals(SwutCodec.end(SwutCodec.END_UNPLUGGED, 0), helper.read());
            UsbTunnel.Ending e = events.awaitEnded();
            assertEquals(UsbTunnel.Ending.DEVICE_GONE, e.kind);
            assertEquals(R.string.usb_tunnel_device_gone, e.messageId());
            assertEquals(i + 1, exporter.connects.get());
            assertEquals(i + 1, exporter.stops.get());
            assertFalse(events.order().contains("sharing"));
            assertTrue("more after END", helper.readUntilClosed().isEmpty());
        }
    }

    // Both ways at once, more than either side's buffers hold, in frames that don't line up with
    // what was written
    @Test
    public void bytesGoBothWaysAtOnce() throws Exception {
        connect();
        Socket local = sharing();
        byte[] toDevice = new byte[300_000];
        byte[] fromDevice = new byte[300_000];
        Random r = new Random(42);
        r.nextBytes(toDevice);
        r.nextBytes(fromDevice);

        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread helperWrites = new Thread(() -> {
            try {
                for (int at = 0; at < toDevice.length; ) {
                    int n = (int) Math.min(1 + (at * 7919L) % SwutCodec.MAX_PAYLOAD, toDevice.length - at);
                    helper.send(SwutCodec.data(toDevice, at, n));
                    at += n;
                }
            }
            catch (Throwable t) {
                failed.compareAndSet(null, t);
            }
        });
        Thread exporterWrites = new Thread(() -> {
            try {
                local.getOutputStream().write(fromDevice);
            }
            catch (Throwable t) {
                failed.compareAndSet(null, t);
            }
        });
        helperWrites.start();
        exporterWrites.start();

        AtomicReference<byte[]> atDevice = new AtomicReference<>();
        Thread exporterReads = new Thread(() -> {
            try {
                atDevice.set(readN(local.getInputStream(), toDevice.length));
            }
            catch (Throwable t) {
                failed.compareAndSet(null, t);
            }
        });
        exporterReads.start();
        ByteArrayOutputStream atHelper = new ByteArrayOutputStream();
        while (atHelper.size() < fromDevice.length) {
            byte[] f = helper.read();
            assertEquals(SwutCodec.TYPE_DATA, SwutCodec.typeOf(f));
            assertTrue(f.length > SwutCodec.FRAME_HEADER);
            atHelper.write(f, SwutCodec.FRAME_HEADER, f.length - SwutCodec.FRAME_HEADER);
        }
        helperWrites.join(FakeHelper.SAFETY_MS);
        exporterWrites.join(FakeHelper.SAFETY_MS);
        exporterReads.join(FakeHelper.SAFETY_MS);
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
        assertArrayEquals(fromDevice, atHelper.toByteArray());
        assertArrayEquals(toDevice, atDevice.get());
        tunnel.close();
        events.awaitEnded();
    }

    // ---------------------------------------------------------------- how it ends
    @Test
    public void stopSendsEndOneAndGivesTheDeviceBack() throws Exception {
        connect();
        Socket local = sharing();
        tunnel.close();
        assertArrayEquals(SwutCodec.end(SwutCodec.END_STOPPED, 0), helper.read());
        assertTrue("more after END", helper.readUntilClosed().isEmpty());
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(UsbTunnel.Ending.STOPPED, e.kind);
        assertEquals(SwutCodec.END_STOPPED, e.code);
        assertEquals(R.string.usb_tunnel_stopped, e.messageId());
        assertFalse(e.canShareAgain());
        assertTrue(closedByPeer(local));
        assertEquals(1, exporter.stops.get());
        assertFalse(tunnel.isConnected());
        assertEquals(Arrays.asList("sharing", "ended " + UsbTunnel.Ending.STOPPED), events.order());
    }

    @Test
    public void theStreamEndingOrTheDeviceGoingSendsItsOwnEnd() throws Exception {
        for (int reason : new int[] {SwutCodec.END_STREAM_ENDED, SwutCodec.END_UNPLUGGED, SwutCodec.END_CLOSING}) {
            connect();
            sharing();
            tunnel.close(reason);
            assertArrayEquals(SwutCodec.end(reason, 0), helper.read());
            UsbTunnel.Ending e = events.awaitEnded();
            assertEquals(UsbTunnel.Ending.STOPPED, e.kind);
            assertEquals(reason, e.code);
        }
        assertEquals(3, exporter.stops.get());
    }

    // Stopping before BIND_ACK just closes: there's no session for an END yet
    @Test
    public void stoppingBeforeBindAckJustCloses() throws Exception {
        connect();
        bindFor(hello());
        tunnel.close();
        endedWithoutTheDevice(UsbTunnel.Ending.STOPPED);
        assertTrue(helper.readUntilClosed().isEmpty());
    }

    // Wi-Fi gone: no END (it couldn't arrive), the device back, and "Share again" offered
    @Test
    public void losingTheNetworkCutsItAndOffersToShareAgain() throws Exception {
        connect();
        Socket local = sharing();
        tunnel.networkLost();
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(UsbTunnel.Ending.LOST, e.kind);
        assertTrue(e.canShareAgain());
        assertEquals(R.string.usb_tunnel_lost, e.messageId());
        assertNoEnd(helper.readUntilClosed());
        assertTrue(closedByPeer(local));
        assertEquals(1, exporter.stops.get());
    }

    // After a drop the same tunnel shares again, with a new certificate, and carries bytes again
    @Test
    public void itSharesAgainAfterADrop() throws Exception {
        connect();
        sharing();
        byte[] firstFp = helper.phoneFp();
        tunnel.networkLost();
        assertTrue(events.awaitEnded().canShareAgain());
        helper.readUntilClosed();

        connect();
        Socket local = sharing();
        assertFalse("the same certificate twice", Arrays.equals(firstFp, helper.phoneFp()));
        byte[] ping = hex("0102030405");
        helper.send(SwutCodec.data(ping, 0, ping.length));
        assertArrayEquals(ping, readN(local.getInputStream(), ping.length));
        assertEquals(2, exporter.starts.get());
        assertEquals(1, exporter.stops.get());
        tunnel.close();
        events.awaitEnded();
        assertEquals(2, exporter.stops.get());
    }

    @Test
    public void anEndFromThePcIsPassedOnAndAnsweredByClosing() throws Exception {
        int[][] cases = {
                {SwutCodec.END_ATTACH_FAILED, 0, R.string.usb_tunnel_attach_failed, 0},
                {SwutCodec.END_UNPAIRED, 0, R.string.usb_tunnel_unpaired, 0},
                {SwutCodec.END_USB_OFF, 0, R.string.usb_tunnel_usb_off, 0},
                {SwutCodec.END_RULES, SwutCodec.RULE_MISMATCH, R.string.usb_tunnel_rules, R.string.usb_rule_mismatch},
        };
        for (int[] c : cases) {
            connect();
            Socket local = sharing();
            helper.send(SwutCodec.end(c[0], c[1]));
            UsbTunnel.Ending e = events.awaitEnded();
            assertEquals(UsbTunnel.Ending.ENDED_BY_PC, e.kind);
            assertEquals(c[0], e.code);
            assertEquals(c[1], e.rule);
            assertEquals(c[2], e.messageId());
            assertEquals(c[3], e.ruleId());
            assertFalse(e.canShareAgain());
            assertTrue("Starwire answered an END", helper.readUntilClosed().isEmpty());
            assertTrue(closedByPeer(local));
        }
        assertEquals(cases.length, exporter.stops.get());
    }

    @Test
    public void theExporterClosingSendsEndTwo() throws Exception {
        connect();
        Socket local = sharing();
        local.close();
        assertArrayEquals(SwutCodec.end(SwutCodec.END_UNPLUGGED, 0), helper.read());
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(UsbTunnel.Ending.DEVICE_GONE, e.kind);
        assertEquals(1, exporter.stops.get());
    }

    // An exporter that fails, or gives a SHARE that doesn't read, never gets a SHARE sent
    @Test
    public void anExporterThatCantShareTheDeviceSendsEndTwoAndNoShare() throws Exception {
        for (int i = 0; i < 3; i++) {
            exporter.failStart = i == 0 ? new IOException("no device") : null;
            exporter.share = i == 1 ? null : hex("05000100ff");
            connect();
            byte[] hello = hello();
            byte[] bind = bindFor(hello);
            helper.send(SwutCodec.bindAck(usbKey(), hello, helper.phoneFp(), helper.helperFp(), bind));
            assertArrayEquals(SwutCodec.end(SwutCodec.END_UNPLUGGED, 0), helper.read());
            assertEquals(UsbTunnel.Ending.DEVICE_GONE, events.awaitEnded().kind);
            assertEquals(i + 1, exporter.stops.get());
            assertEquals(0, exporter.connects.get());
            assertFalse(exporter.anyoneConnected());
        }
    }

    @Test
    public void aShareAckOtherThanZeroEndsItWithoutTheExporter() throws Exception {
        int[][] cases = {
                {SwutCodec.SHARE_BUSY, 0, R.string.usb_tunnel_busy, 0},
                {SwutCodec.SHARE_NO_DRIVER, 0, R.string.usb_tunnel_no_driver, 0},
                {SwutCodec.SHARE_REFUSED, SwutCodec.RULE_UNCONFIRMED_INPUT, R.string.usb_tunnel_device_refused,
                        R.string.usb_rule_unconfirmed_input},
        };
        for (int[] c : cases) {
            connect();
            bound();
            helper.send(SwutCodec.shareAck(c[0], c[1]));
            UsbTunnel.Ending e = events.awaitEnded();
            assertEquals(UsbTunnel.Ending.NOT_SHARED, e.kind);
            assertEquals(c[0], e.code);
            assertEquals(c[2], e.messageId());
            assertEquals(c[3], e.ruleId());
            assertTrue("Starwire sent something after a refused SHARE", helper.readUntilClosed().isEmpty());
            assertFalse(exporter.anyoneConnected());
        }
        assertEquals(0, exporter.connects.get());
        assertEquals(cases.length, exporter.stops.get());
        // The vectors' SHARE_ACK for a keyboard nobody confirmed reads the same way
        connect();
        bound();
        helper.send(v("frame.share_ack_unconfirmed_keyboard"));
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(SwutCodec.RULE_UNCONFIRMED_INPUT, e.rule);
    }

    // After BIND_ACK, a frame out of order, of an unknown type or that doesn't read ends it with END 9
    @Test
    public void aFrameThatBreaksTheProtocolEndsItWithEndNine() throws Exception {
        byte[][] whileSharing = {hello(), SwutCodec.shareAck(0, 0), hex("03001000" + "00000000000000000000000000000000"),
                hex("07000000"), hex("0a000000"), hex("0701010000"), hex("0900040001000001"), hex("0800010000"),
                SwutCodec.refuse(1)};
        for (byte[] f : whileSharing) {
            connect();
            sharing();
            helper.send(f);
            assertArrayEquals(SwutVectors.hex(f), SwutCodec.end(SwutCodec.END_PROTOCOL, 0), helper.read());
            assertEquals(UsbTunnel.Ending.PROTOCOL_ERROR, events.awaitEnded().kind);
            helper.readUntilClosed();
        }
        // And where SHARE_ACK should come
        byte[][] insteadOfShareAck = {SwutCodec.keepalive(), SwutCodec.data(new byte[] {1}, 0, 1), hex("0600040000000001")};
        for (byte[] f : insteadOfShareAck) {
            connect();
            bound();
            helper.send(f);
            assertArrayEquals(SwutCodec.end(SwutCodec.END_PROTOCOL, 0), helper.read());
            assertEquals(UsbTunnel.Ending.PROTOCOL_ERROR, events.awaitEnded().kind);
            assertFalse(exporter.anyoneConnected());
            helper.readUntilClosed();
        }
    }

    // ---------------------------------------------------------------- time
    @Test
    public void noHelloInTenSecondsGivesUp() throws Exception {
        connect();
        helper.accept();
        now.addAndGet(UsbTunnel.ANSWER_MS);
        endedWithoutTheDevice(UsbTunnel.Ending.NO_ANSWER);
        assertTrue(helper.readUntilClosed().isEmpty());
    }

    @Test
    public void noBindAckInTenSecondsGivesUp() throws Exception {
        connect();
        bindFor(hello());
        now.addAndGet(UsbTunnel.ANSWER_MS);
        endedWithoutTheDevice(UsbTunnel.Ending.NO_ANSWER);
        assertTrue(helper.readUntilClosed().isEmpty());
    }

    @Test
    public void noShareAckInTenSecondsEndsWithEndNine() throws Exception {
        connect();
        bound();
        now.addAndGet(UsbTunnel.ANSWER_MS);
        assertArrayEquals(SwutCodec.end(SwutCodec.END_PROTOCOL, 0), helper.read());
        assertEquals(UsbTunnel.Ending.NO_ANSWER, events.awaitEnded().kind);
        assertEquals(1, exporter.stops.get());
    }

    // From SHARE_ACK 0 there's no answer left to wait for: a SHARE_ACK late in its 10 s and a slow
    // connection to the exporter still share, and KEEPALIVE goes out meanwhile
    @Test
    public void aLateShareAckAndASlowExporterStillShare() throws Exception {
        connect();
        bound();
        now.addAndGet(UsbTunnel.ANSWER_MS - 1);
        CountDownLatch heard = new CountDownLatch(1);
        exporter.beforeConnect = () -> {
            now.addAndGet(2 * UsbTunnel.KEEPALIVE_MS);
            try {
                assertTrue(heard.await(FakeHelper.SAFETY_MS, TimeUnit.MILLISECONDS));
            }
            catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        };
        helper.send(SwutCodec.shareAck(SwutCodec.SHARE_ATTACHING, 0));
        // A KEEPALIVE while the exporter connects, not END 9 for an answer that has come
        assertArrayEquals(SwutCodec.keepalive(), helper.read());
        heard.countDown();
        Socket local = exporter.accept();
        closeLater.add(local);
        events.awaitSharing();
        byte[] ping = hex("aa");
        helper.send(SwutCodec.data(ping, 0, 1));
        assertArrayEquals(ping, readN(local.getInputStream(), 1));
        tunnel.close();
        assertEquals(UsbTunnel.Ending.STOPPED, events.awaitEnded().kind);
    }

    // One KEEPALIVE for each quiet second, not one each time the watchdog looks
    @Test
    public void aQuietSecondSendsAKeepalive() throws Exception {
        connect();
        Socket local = sharing();
        now.addAndGet(UsbTunnel.KEEPALIVE_MS);
        assertArrayEquals(SwutCodec.keepalive(), helper.read());
        awaitWatched();
        byte[] ping = hex("aa");
        local.getOutputStream().write(ping);
        assertArrayEquals("another KEEPALIVE, with no quiet second since", SwutCodec.data(ping, 0, 1), helper.read());
        now.addAndGet(UsbTunnel.KEEPALIVE_MS);
        assertArrayEquals(SwutCodec.keepalive(), helper.read());
        tunnel.close();
        assertArrayEquals(SwutCodec.end(SwutCodec.END_STOPPED, 0), helper.read());
        events.awaitEnded();
    }

    // 8 s without a word from the helper: it's gone, so close without an END and offer to share again
    @Test
    public void eightSilentSecondsDropTheConnection() throws Exception {
        connect();
        Socket local = sharing();
        now.addAndGet(UsbTunnel.SILENCE_MS);
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(UsbTunnel.Ending.LOST, e.kind);
        assertTrue(e.canShareAgain());
        assertNoEnd(helper.readUntilClosed());
        assertTrue(closedByPeer(local));
        assertEquals(1, exporter.stops.get());
    }

    // The helper's KEEPALIVE counts as hearing from it
    @Test
    public void theHelpersKeepaliveKeepsItGoing() throws Exception {
        connect();
        Socket local = sharing();
        now.addAndGet(UsbTunnel.SILENCE_MS - 1);
        helper.send(SwutCodec.keepalive());
        // A DATA after it, so once it's at the exporter the KEEPALIVE has been read
        byte[] ping = hex("aa");
        helper.send(SwutCodec.data(ping, 0, 1));
        assertArrayEquals(ping, readN(local.getInputStream(), 1));
        now.addAndGet(2);
        helper.send(SwutCodec.data(ping, 0, 1));
        assertArrayEquals(ping, readN(local.getInputStream(), 1));
        assertTrue(tunnel.isConnected());
        assertEquals(Arrays.asList("sharing"), events.order());
        tunnel.close();
        events.awaitEnded();
    }

    // ---------------------------------------------------------------- one at a time
    @Test
    public void oneDeviceIsSharedAtATime() throws Exception {
        connect();
        sharing();
        assertThrows(IllegalStateException.class, this::connect);
        UsbTunnel other = new UsbTunnel(exporter, new TunnelEvents(), clock, seeded(8), SwutTls::newIdentity, 2);
        assertThrows(IllegalStateException.class,
                () -> other.connect("127.0.0.1", helper.port(), pairing()));
        tunnel.close();
        events.awaitEnded();
        // Free again once it's ended
        TunnelEvents otherEvents = new TunnelEvents();
        UsbTunnel next = new UsbTunnel(exporter, otherEvents, clock, seeded(8), SwutTls::newIdentity, 2);
        next.connect("127.0.0.1", helper.port(), null);
        assertEquals(UsbTunnel.Ending.NO_PAIRING, otherEvents.awaitEnded().kind);
    }

    // A stop while the exporter is still taking the device lets no new share in until the
    // exporter has given that device back and the listener has heard, so two are never held
    @Test
    public void noNewShareUntilTheExporterHasGivenTheDeviceBack() throws Exception {
        CountDownLatch begun = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        exporter.startBegun = begun;
        exporter.startWaitsFor = letGo;
        Set<Thread> watchdogs = threadsNamed("UsbTunnelWatch");
        connect();
        Thread watchdog = newThreadNamed("UsbTunnelWatch", watchdogs);
        byte[] hello = hello();
        byte[] bind = bindFor(hello);
        helper.send(SwutCodec.bindAck(usbKey(), hello, helper.phoneFp(), helper.helperFp(), bind));
        assertTrue(begun.await(FakeHelper.SAFETY_MS, TimeUnit.MILLISECONDS));
        tunnel.close();
        assertArrayEquals(SwutCodec.end(SwutCodec.END_STOPPED, 0), helper.read());
        assertTrue("more after END", helper.readUntilClosed().isEmpty());
        // Over on the network (the watchdog has stopped), but start() still has the device
        watchdog.join(FakeHelper.SAFETY_MS);
        assertFalse(watchdog.isAlive());
        assertTrue(tunnel.isConnected());
        assertThrows(IllegalStateException.class, this::connect);
        UsbTunnel other = new UsbTunnel(exporter, new TunnelEvents(), clock, seeded(8), SwutTls::newIdentity, 2);
        assertThrows(IllegalStateException.class, () -> other.connect("127.0.0.1", helper.port(), pairing()));
        assertEquals(1, exporter.starts.get());
        assertEquals(0, exporter.stops.get());
        assertTrue(events.order().isEmpty());

        letGo.countDown();
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(UsbTunnel.Ending.STOPPED, e.kind);
        assertEquals(1, exporter.stops.get());
        assertEquals(0, exporter.held.get());
        assertFalse(tunnel.isConnected());
        // Free again, now the device is back
        exporter.startWaitsFor = null;
        connect();
        sharing();
        tunnel.close();
        assertEquals(UsbTunnel.Ending.STOPPED, events.awaitEnded().kind);
        assertEquals(1, exporter.mostHeld);
        assertEquals(0, exporter.held.get());
    }

    // The tunnel's threads with this name that are running now
    private static Set<Thread> threadsNamed(String name) {
        Set<Thread> found = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (name.equals(t.getName()) && t.isAlive()) {
                found.add(t);
            }
        }
        return found;
    }

    // The one thread with this name that wasn't running before
    private static Thread newThreadNamed(String name, Set<Thread> before) {
        Set<Thread> now = threadsNamed(name);
        now.removeAll(before);
        assertEquals("new threads named " + name, 1, now.size());
        return now.iterator().next();
    }

    @Test
    public void everyEndingHasAMessageAndOnlyADropOffersToShareAgain() {
        for (int kind = UsbTunnel.Ending.NO_PAIRING; kind <= UsbTunnel.Ending.DEVICE_GONE; kind++) {
            UsbTunnel.Ending e = new UsbTunnel.Ending(kind, 0, 0);
            assertNotEquals(0, e.messageId());
            assertEquals(kind == UsbTunnel.Ending.LOST, e.canShareAgain());
            assertEquals(0, e.ruleId());
        }
        for (int rule = 1; rule <= 10; rule++) {
            assertNotEquals(R.string.usb_rule_unknown, UsbTunnel.ruleText(rule));
        }
        assertEquals(R.string.usb_rule_unknown, UsbTunnel.ruleText(11));
    }

    // ---------------------------------------------------------------- what the review found untested
    // Each answer gets its own 10 s, counted from what it answers: HELLO from the start, BIND_ACK
    // from BIND, SHARE_ACK from SHARE
    @Test
    public void eachAnswerGetsItsOwnTenSeconds() throws Exception {
        connect();
        helper.accept();
        now.addAndGet(UsbTunnel.ANSWER_MS - 1);
        awaitWatched();
        byte[] hello = hello();
        helper.send(hello);
        byte[] bind = helper.read();
        now.addAndGet(UsbTunnel.ANSWER_MS - 1);
        awaitWatched();
        helper.send(SwutCodec.bindAck(usbKey(), hello, helper.phoneFp(), helper.helperFp(), bind));
        assertArrayEquals(exporter.share, helper.read());
        now.addAndGet(UsbTunnel.ANSWER_MS - 1);
        awaitWatched();
        helper.send(SwutCodec.shareAck(SwutCodec.SHARE_ATTACHING, 0));
        Socket local = exporter.accept();
        closeLater.add(local);
        events.awaitSharing();
        tunnel.close();
        assertEquals(UsbTunnel.Ending.STOPPED, events.awaitEnded().kind);
    }

    // An END where SHARE_ACK should be is the PC's END, passed on and not answered
    @Test
    public void anEndInsteadOfShareAckIsPassedOnAndNotAnswered() throws Exception {
        connect();
        bound();
        helper.send(SwutCodec.end(SwutCodec.END_USB_OFF, 0));
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(e.toString(), UsbTunnel.Ending.ENDED_BY_PC, e.kind);
        assertEquals(SwutCodec.END_USB_OFF, e.code);
        assertEquals(R.string.usb_tunnel_usb_off, e.messageId());
        assertTrue("Starwire answered an END", helper.readUntilClosed().isEmpty());
        assertEquals(0, exporter.connects.get());
        assertEquals(1, exporter.stops.get());
    }

    // Wi-Fi gone before BIND_ACK: cut at once, nothing sent, the exporter never asked, and
    // "Share again" offered
    @Test
    public void losingTheNetworkBeforeBindAckCutsItWithoutTheDevice() throws Exception {
        connect();
        bindFor(hello());
        tunnel.networkLost();
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(UsbTunnel.Ending.LOST, e.kind);
        assertTrue(e.canShareAgain());
        assertTrue(helper.readUntilClosed().isEmpty());
        assertEquals(0, exporter.starts.get());
        assertEquals(0, exporter.stops.get());
    }

    // After END, TLS is closed properly, with close_notify, before the connection. On TLS 1.2,
    // where an alert shows on the wire as a record type of its own (21)
    @Test
    public void aStopClosesTlsProperlyAfterItsEnd() throws Exception {
        helper.close();
        helper = new FakeHelper(SwutTls.newIdentity(seeded(5)), new String[] {"TLSv1.2"}, null);
        Tap tap = new Tap(helper.port());
        closeLater.add(tap);
        tunnel.connect("127.0.0.1", tap.port(), pairing());
        sharing();
        tunnel.close();
        assertArrayEquals(SwutCodec.end(SwutCodec.END_STOPPED, 0), helper.read());
        assertEquals(UsbTunnel.Ending.STOPPED, events.awaitEnded().kind);
        List<Integer> types = tap.recordTypesFromPhone();
        assertEquals(types.toString(), Integer.valueOf(Tap.ALERT), types.get(types.size() - 1));
        assertEquals(types.toString(), Integer.valueOf(Tap.APPLICATION_DATA), types.get(types.size() - 2));
        assertEquals(types.toString(), 1, Collections.frequency(types, Tap.ALERT));
    }

    // A stop whose END can't go out (the helper stopped reading, every buffer on the way is full)
    // is cut CLOSE_MS later, and still ends as the stop it was
    @Test
    public void aStopThatCantGoOutIsCutAfterTwoSeconds() throws Exception {
        List<HeldSocket> plain = new ArrayList<>();
        tunnel = new UsbTunnel(exporter, events, clock, seeded(1), SwutTls::newIdentity, SwutCodec::usbKey, () -> {
            HeldSocket s = new HeldSocket();
            plain.add(s);
            return s;
        }, 2);
        connect();
        Socket local = sharing();
        plain.get(0).hold = true;
        tunnel.close();
        awaitWatched();
        now.addAndGet(UsbTunnel.CLOSE_MS - 1);
        awaitWatched();
        assertTrue(tunnel.isConnected());
        assertEquals(Arrays.asList("sharing"), events.order());
        now.addAndGet(1);
        UsbTunnel.Ending e = events.awaitEnded();
        assertEquals(UsbTunnel.Ending.STOPPED, e.kind);
        assertEquals(SwutCodec.END_STOPPED, e.code);
        assertTrue(closedByPeer(local));
        assertEquals(1, exporter.stops.get());
        assertFalse(tunnel.isConnected());
    }

    // K_pair's copy is wiped as soon as K_usb is worked out, and K_usb once BIND_ACK checks out,
    // when a session ends before that, and when connect() is turned away. The caller's pairing
    // is left as it was.
    @Test
    public void theKeysAreWipedOnceTheyHaveDoneTheirWork() throws Exception {
        List<byte[]> pairKeys = new ArrayList<>();
        List<byte[]> usbKeys = new ArrayList<>();
        UsbTunnel.Keys watched = (pairKey, deviceId) -> {
            byte[] k = SwutCodec.usbKey(pairKey, deviceId);
            synchronized (pairKeys) {
                pairKeys.add(pairKey);
                usbKeys.add(k);
            }
            return k;
        };
        tunnel = new UsbTunnel(exporter, events, clock, seeded(1), SwutTls::newIdentity, watched, Socket::new, 2);
        MicPairing p = pairing();
        tunnel.connect("127.0.0.1", helper.port(), p);
        assertWiped(pairKeys.get(0));
        assertArrayEquals(SwutVectors.PAIR_KEY, p.key);
        bound();
        assertWiped(usbKeys.get(0));
        tunnel.close();
        events.awaitEnded();
        helper.readUntilClosed();

        // Refused before BIND_ACK: wiped by the time the session's thread is done
        Set<Thread> sessions = threadsNamed("UsbTunnel");
        connect();
        Thread session = newThreadNamed("UsbTunnel", sessions);
        bindFor(hello());
        helper.send(SwutCodec.refuse(SwutCodec.REFUSE_NOT_PAIRED));
        events.awaitEnded();
        session.join(FakeHelper.SAFETY_MS);
        assertFalse(session.isAlive());
        assertWiped(pairKeys.get(1));
        assertWiped(usbKeys.get(1));
        helper.readUntilClosed();

        // Turned away while another share is going
        connect();
        bindFor(hello());
        UsbTunnel other = new UsbTunnel(exporter, new TunnelEvents(), clock, seeded(8), SwutTls::newIdentity, watched,
                Socket::new, 2);
        assertThrows(IllegalStateException.class, () -> other.connect("127.0.0.1", helper.port(), pairing()));
        assertEquals(4, usbKeys.size());
        assertWiped(pairKeys.get(3));
        assertWiped(usbKeys.get(3));
        tunnel.close();
        events.awaitEnded();
    }

    private static void assertWiped(byte[] key) {
        assertArrayEquals("not wiped", new byte[key.length], key);
    }

    // Waits until the watchdog has looked at the clock since the test last moved it, and done
    // whatever that called for. Only the watchdog looks while nothing is sent or heard.
    private void awaitWatched() throws InterruptedException {
        long enough = reads.get() + 2;
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FakeHelper.SAFETY_MS);
        while (reads.get() < enough) {
            if (System.nanoTime() > until) {
                fail("the watchdog never looked at the clock");
            }
            Thread.sleep(1);
        }
    }

    // A plain socket whose writes can be held, as if the helper had stopped reading and every
    // buffer on the way were full. Closing it makes a held write fail, as closing a real one does.
    private static final class HeldSocket extends Socket {
        volatile boolean hold;
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public OutputStream getOutputStream() throws IOException {
            OutputStream out = super.getOutputStream();
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int at, int length) throws IOException {
                    if (hold) {
                        // Held until it's closed: longer than any wait in the test, so a tunnel
                        // that never cuts it fails the test instead of being let go
                        try {
                            closed.await(6L * FakeHelper.SAFETY_MS, TimeUnit.MILLISECONDS);
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new java.net.SocketException("Socket closed");
                    }
                    out.write(b, at, length);
                }

                @Override
                public void flush() throws IOException {
                    out.flush();
                }

                @Override
                public void close() throws IOException {
                    HeldSocket.this.close();
                }
            };
        }

        @Override
        public synchronized void close() throws IOException {
            super.close();
            closed.countDown();
        }
    }

    // Passes TCP through to the helper unchanged, and keeps what the phone sent, to read its TLS
    // records' types
    private static final class Tap implements Closeable {
        static final int ALERT = 21;
        static final int APPLICATION_DATA = 23;

        private final ServerSocket server;
        private final ByteArrayOutputStream fromPhone = new ByteArrayOutputStream();
        private final Thread thread;
        private volatile Thread up;
        private volatile Socket phone;
        private volatile Socket pc;

        Tap(int pcPort) throws IOException {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            server = new ServerSocket(0, 1, loopback);
            server.setSoTimeout(FakeHelper.SAFETY_MS);
            thread = new Thread(() -> {
                try {
                    phone = server.accept();
                    pc = new Socket();
                    pc.connect(new InetSocketAddress(loopback, pcPort), FakeHelper.SAFETY_MS);
                    copy(pc, phone, null);
                    up = copy(phone, pc, fromPhone);
                }
                catch (IOException e) {
                    // The test sees what came through
                }
            });
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private static Thread copy(Socket from, Socket to, ByteArrayOutputStream keep) {
            Thread t = new Thread(() -> {
                byte[] b = new byte[4096];
                try {
                    InputStream in = from.getInputStream();
                    OutputStream out = to.getOutputStream();
                    for (int n; (n = in.read(b)) > 0; ) {
                        if (keep != null) {
                            synchronized (keep) {
                                keep.write(b, 0, n);
                            }
                        }
                        out.write(b, 0, n);
                    }
                    to.shutdownOutput();
                }
                catch (IOException e) {
                    // One side closed
                }
            });
            t.start();
            return t;
        }

        /** The type of each TLS record the phone sent, once it has closed its side. */
        List<Integer> recordTypesFromPhone() throws InterruptedException {
            thread.join(FakeHelper.SAFETY_MS);
            Thread fromPhoneCopy = up;
            assertNotNull("the phone never connected", fromPhoneCopy);
            fromPhoneCopy.join(FakeHelper.SAFETY_MS);
            assertFalse("the phone never closed its side", fromPhoneCopy.isAlive());
            byte[] b;
            synchronized (fromPhone) {
                b = fromPhone.toByteArray();
            }
            List<Integer> types = new ArrayList<>();
            for (int at = 0; at + 5 <= b.length; ) {
                types.add(b[at] & 0xFF);
                at += 5 + ((b[at + 3] & 0xFF) << 8 | (b[at + 4] & 0xFF));
            }
            return types;
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : new Socket[] {phone, pc}) {
                if (s != null) {
                    s.close();
                }
            }
        }
    }

    // ---------------------------------------------------------------- someone in the middle
    // Ends TLS with the phone under one certificate, opens TLS to the helper under another, and
    // passes every byte along both ways
    private static final class Middle implements Closeable {
        final FakeHelper towardsPhone;
        final Thread thread;
        volatile SSLSocket towardsPc;

        Middle(SwutTls.Identity forPhone, SwutTls.Identity forPc, int pcPort) throws Exception {
            towardsPhone = new FakeHelper(forPhone);
            thread = new Thread(() -> {
                try {
                    towardsPhone.accept();
                    Socket plain = new Socket();
                    plain.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), pcPort));
                    SSLSocket tls = (SSLSocket) SwutTls.clientContext(forPc).getSocketFactory()
                            .createSocket(plain, "127.0.0.1", pcPort, true);
                    tls.startHandshake();
                    towardsPc = tls;
                    Thread up = pump(towardsPhone.in, tls.getOutputStream());
                    Thread down = pump(tls.getInputStream(), towardsPhone.out);
                    up.join();
                    down.join();
                }
                catch (Exception e) {
                    // The test sees what it did
                }
                finally {
                    close();
                }
            });
            thread.start();
        }

        int port() {
            return towardsPhone.port();
        }

        private static Thread pump(InputStream in, OutputStream out) {
            Thread t = new Thread(() -> {
                byte[] b = new byte[4096];
                try {
                    for (int n; (n = in.read(b)) > 0; ) {
                        out.write(b, 0, n);
                        out.flush();
                    }
                }
                catch (IOException e) {
                    // One side closed
                }
                try {
                    out.close();
                }
                catch (IOException e) {
                    // Closed either way
                }
            });
            t.start();
            return t;
        }

        @Override
        public void close() {
            towardsPhone.close();
            try {
                if (towardsPc != null) {
                    towardsPc.close();
                }
            }
            catch (IOException e) {
                // Closed either way
            }
        }
    }

    // ---------------------------------------------------------------- when a safety wait runs out
    // Each of the fakes' waits says what every UsbTunnel thread was doing when it ran out, in the
    // failure and in the test's output, so a rare stall on CI shows where it's stuck. Here the
    // phone waits for a HELLO that never comes, so nothing the test waits for can happen, however
    // short the wait: no second connection, no connection to the exporter, no sharing, no end and
    // no frame
    @Test
    public void aWaitThatRunsOutSaysWhatTheTunnelsThreadsWereDoing() throws Exception {
        Set<Thread> sessions = threadsNamed("UsbTunnel");
        Set<Thread> watchdogs = threadsNamed("UsbTunnelWatch");
        connect();
        Thread session = newThreadNamed("UsbTunnel", sessions);
        Thread watchdog = newThreadNamed("UsbTunnelWatch", watchdogs);
        helper.accept();
        helper.server.setSoTimeout(1);
        helper.socket.setSoTimeout(1);
        exporter.server.setSoTimeout(1);

        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        PrintStream err = System.err;
        List<String> reports = new ArrayList<>();
        String neverClosed;
        System.setErr(new PrintStream(printed, true, "UTF-8"));
        try {
            reports.add(assertThrows(SocketTimeoutException.class, helper::accept).getMessage());
            reports.add(assertThrows(SocketTimeoutException.class, exporter::accept).getMessage());
            reports.add(assertThrows(AssertionError.class, () -> events.awaitSharing(1)).getMessage());
            reports.add(assertThrows(AssertionError.class, () -> events.awaitEnded(1)).getMessage());
            reports.add(assertThrows(SocketTimeoutException.class, helper::read).getMessage());
            neverClosed = assertThrows(AssertionError.class, helper::readUntilClosed).getMessage();
        }
        finally {
            System.setErr(err);
        }

        String[] what = {
                "the phone never connected in 1 ms",
                "the tunnel never connected to the exporter in 1 ms",
                "the tunnel never said it was sharing in 1 ms",
                "the tunnel never said it had ended in 1 ms",
                "nothing from the phone in 1 ms",
        };
        String output = printed.toString("UTF-8");
        for (int i = 0; i < what.length; i++) {
            String report = reports.get(i);
            assertTrue(report, report.startsWith(what[i] + ". What the UsbTunnel threads were doing:\n"));
            // The session's thread with its stack, which is in UsbTunnel's code whatever it's doing
            assertTrue(report, threadIn(report, session).contains("com.limelight.binding.usb.tunnel.UsbTunnel."));
            threadIn(report, watchdog);
            assertTrue("not in the test's output: " + report, output.contains(report));
        }
        assertTrue(neverClosed, neverClosed.startsWith("the phone never closed the connection: nothing from the phone in 1 ms. "
                + "What the UsbTunnel threads were doing:\n"));
        threadIn(neverClosed, session);
    }

    // The report's lines for this thread: its name, id and state, then its stack
    private static String threadIn(String report, Thread t) {
        int at = report.indexOf("\n\"" + t.getName() + "\" id=" + t.threadId() + " ");
        assertTrue(t.getName() + " (id " + t.threadId() + ") isn't in: " + report, at >= 0);
        int next = report.indexOf("\n\"", at + 1);
        return report.substring(at + 1, next < 0 ? report.length() : next + 1);
    }
}
