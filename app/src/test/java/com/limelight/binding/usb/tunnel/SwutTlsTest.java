package com.limelight.binding.usb.tunnel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * The tunnel's TLS on Starwire's side (docs/dev/usb-protocol.md, "TLS"): the certificate it makes
 * for each connection, and the versions and suites it allows, on the JDK's TLS and on Conscrypt.
 * UsbTunnelTest has the protocol's handshakes.
 */
@RunWith(Parameterized.class)
public class SwutTlsTest {

    @Parameterized.Parameters(name = "{0}")
    public static Object[] providers() {
        return new Object[] {TlsProvider.JDK, TlsProvider.CONSCRYPT};
    }

    @Parameterized.Parameter
    public String provider;

    private TlsProvider tls;

    @Before
    public void setUp() throws Exception {
        tls = TlsProvider.use(provider);
    }

    @After
    public void tearDown() {
        tls.close();
    }

    private static SecureRandom seeded(long seed) throws Exception {
        return TlsProvider.seeded(seed);
    }

    @Test
    public void eachConnectionsCertificateIsNewSelfSignedP256AndNamesNobody() throws Exception {
        SwutTls.Identity a = SwutTls.newIdentity(seeded(1));
        SwutTls.Identity b = SwutTls.newIdentity(seeded(2));
        for (SwutTls.Identity id : new SwutTls.Identity[] {a, b}) {
            X509Certificate c = id.certificate;
            assertEquals(3, c.getVersion());
            assertEquals("CN=Starwire", c.getSubjectX500Principal().getName());
            assertEquals("CN=Starwire", c.getIssuerX500Principal().getName());
            // ecdsa-with-SHA256, verified with its own key
            assertEquals("1.2.840.10045.4.3.2", c.getSigAlgOID());
            c.verify(c.getPublicKey());
            assertTrue(SwutTls.isP256(c.getPublicKey()));
            assertNull("no extensions", c.getCriticalExtensionOIDs());
            assertNull("no extensions", c.getNonCriticalExtensionOIDs());
            assertTrue(c.getSerialNumber().signum() > 0);
            // Fixed dates, so they say nothing about the phone's clock
            assertEquals(SwutTls.NOT_BEFORE_MS, c.getNotBefore().getTime());
            assertEquals(SwutTls.NOT_AFTER_MS, c.getNotAfter().getTime());
            assertEquals("EC", id.key.getAlgorithm());
        }
        assertNotEquals(a.certificate.getSerialNumber(), b.certificate.getSerialNumber());
        assertFalse(Arrays.equals(a.certificate.getPublicKey().getEncoded(), b.certificate.getPublicKey().getEncoded()));
    }

    @Test
    public void onlyP256KeysCount() throws Exception {
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp384r1"), seeded(3));
        assertFalse(SwutTls.isP256(ec.generateKeyPair().getPublic()));
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048, seeded(4));
        assertFalse(SwutTls.isP256(rsa.generateKeyPair().getPublic()));
        assertTrue(SwutTls.isP256(SwutTls.newIdentity(seeded(5)).certificate.getPublicKey()));
    }

    // TLS 1.3 or 1.2 only, and in 1.2 only ECDHE_ECDSA with AES-GCM or ChaCha20-Poly1305
    @Test
    public void onlyTheContractsVersionsAndSuitesAreEnabled() throws Exception {
        SSLSocket socket = (SSLSocket) SwutTls.clientContext(SwutTls.newIdentity(seeded(6))).getSocketFactory().createSocket();
        try {
            SwutTls.restrict(socket);
            assertEquals(new HashSet<>(Arrays.asList("TLSv1.3", "TLSv1.2")),
                    new HashSet<>(Arrays.asList(socket.getEnabledProtocols())));
            List<String> allowed = Arrays.asList(SwutTls.CIPHER_SUITES);
            for (String suite : socket.getEnabledCipherSuites()) {
                assertTrue(suite, allowed.contains(suite));
                assertFalse(suite, suite.contains("CBC") || suite.contains("_RSA_") || suite.contains("PSK"));
                assertTrue(suite, suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_")
                        || suite.startsWith("TLS_ECDHE_ECDSA_WITH_"));
            }
            assertTrue(Arrays.asList(socket.getEnabledCipherSuites()).contains("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"));
        }
        finally {
            socket.close();
        }
    }

    // Whatever the platform lets through, the finished handshake is checked against the same list
    @Test
    public void aSessionOnAnotherVersionOrSuiteIsRefused() throws Exception {
        SwutTls.checkSession(session("TLSv1.3", "TLS_AES_128_GCM_SHA256"));
        SwutTls.checkSession(session("TLSv1.2", "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"));
        assertThrows(SSLException.class, () -> SwutTls.checkSession(session("TLSv1.1", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256")));
        assertThrows(SSLException.class, () -> SwutTls.checkSession(session("TLSv1.2", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256")));
        assertThrows(SSLException.class, () -> SwutTls.checkSession(session("TLSv1.2", "TLS_RSA_WITH_AES_128_GCM_SHA256")));
        assertThrows(SSLException.class, () -> SwutTls.checkSession(null));
    }

    // handshake() checks what was agreed whatever the platform let through: here a socket that
    // ignores the versions and suites it's given, so a helper with only CBC gets through TLS
    @Test
    public void theHandshakeIsCheckedWhateverThePlatformLetThrough() throws Exception {
        FakeHelper helper = new FakeHelper(SwutTls.newIdentity(seeded(7)), new String[] {"TLSv1.2"}, null);
        try {
            List<String> cbc = new ArrayList<>();
            for (String suite : helper.server.getEnabledCipherSuites()) {
                if (suite.startsWith("TLS_ECDHE_ECDSA_WITH_") && suite.contains("_CBC_")) {
                    cbc.add(suite);
                }
            }
            assertFalse("no CBC suites to offer", cbc.isEmpty());
            helper.server.setEnabledCipherSuites(cbc.toArray(new String[0]));
            Thread accept = accept(helper);
            SSLSocket tls = connectTo(helper);
            try {
                SSLException e = assertThrows(SSLException.class, () -> SwutTls.handshake(new IgnoresRestrictions(tls, null)));
                assertTrue(e.getMessage(), e.getMessage().contains("_CBC_"));
                accept.join(FakeHelper.SAFETY_MS);
                assertNotNull("TLS itself didn't go through", helper.socket);
            }
            finally {
                tls.close();
            }
        }
        finally {
            helper.close();
        }
    }

    // One certificate each in the finished session, even if the platform's own check let more by;
    // and the fingerprints come back as each was seen, the phone's first
    @Test
    public void theHandshakeWantsOneCertificateEachAndGivesBothFingerprints() throws Exception {
        for (int i = 0; i < 3; i++) {
            FakeHelper helper = new FakeHelper(SwutTls.newIdentity(seeded(8)));
            try {
                Thread accept = accept(helper);
                SSLSocket tls = connectTo(helper);
                try {
                    if (i == 2) {
                        byte[][] fps = SwutTls.handshake(new IgnoresRestrictions(tls, null));
                        accept.join(FakeHelper.SAFETY_MS);
                        assertArrayEquals(helper.phoneFp(), fps[0]);
                        assertArrayEquals(helper.helperFp(), fps[1]);
                        continue;
                    }
                    boolean twoFromHelper = i == 0;
                    SSLException e = assertThrows(SSLException.class, () -> SwutTls.handshake(new IgnoresRestrictions(tls,
                            real -> certificates(real, twoFromHelper))));
                    assertTrue(e.getMessage(), e.getMessage().contains("exactly one certificate"));
                }
                finally {
                    tls.close();
                }
            }
            finally {
                helper.close();
            }
        }
    }

    // TLS itself, before handshake()'s own count, takes only one helper certificate
    @Test
    public void tlsItselfRefusesAHelperWithTwoCertificates() throws Exception {
        SwutTls.Identity good = SwutTls.newIdentity(seeded(10));
        FakeHelper helper = new FakeHelper(good);
        try {
            helper.chain = new X509Certificate[] {good.certificate, SwutTls.newIdentity(seeded(11)).certificate};
            accept(helper);
            SSLSocket tls = connectTo(helper);
            try {
                SwutTls.restrict(tls);
                assertThrows(SSLException.class, tls::startHandshake);
            }
            finally {
                tls.close();
            }
        }
        finally {
            helper.close();
        }
    }

    private static Thread accept(FakeHelper helper) {
        Thread t = new Thread(() -> {
            try {
                helper.accept();
            }
            catch (Exception e) {
                // The test sees it as no socket
            }
        });
        t.start();
        return t;
    }

    private SSLSocket connectTo(FakeHelper helper) throws Exception {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), helper.port()), FakeHelper.SAFETY_MS);
        plain.setSoTimeout(FakeHelper.SAFETY_MS);
        return (SSLSocket) SwutTls.clientContext(SwutTls.newIdentity(seeded(9))).getSocketFactory()
                .createSocket(plain, "127.0.0.1", helper.port(), true);
    }

    // The real session, but with two helper certificates, or none of the phone's
    private static SSLSession certificates(SSLSession real, boolean twoFromHelper) {
        return (SSLSession) Proxy.newProxyInstance(SSLSession.class.getClassLoader(), new Class<?>[] {SSLSession.class},
                (proxy, method, args) -> {
                    if (twoFromHelper && method.getName().equals("getPeerCertificates")) {
                        Certificate[] one = real.getPeerCertificates();
                        return new Certificate[] {one[0], one[0]};
                    }
                    if (!twoFromHelper && method.getName().equals("getLocalCertificates")) {
                        return null;
                    }
                    try {
                        return method.invoke(real, args);
                    }
                    catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // A TLS socket on a platform that ignores the versions and suites it's told to allow, and
    // whose session can be swapped for another view of it
    private static final class IgnoresRestrictions extends SSLSocket {
        private final SSLSocket real;
        private final Function<SSLSession, SSLSession> session;

        IgnoresRestrictions(SSLSocket real, Function<SSLSession, SSLSession> session) {
            this.real = real;
            this.session = session;
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
        }

        @Override
        public SSLSession getSession() {
            return session != null ? session.apply(real.getSession()) : real.getSession();
        }

        @Override
        public void startHandshake() throws IOException {
            real.startHandshake();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return real.getSupportedCipherSuites();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return real.getEnabledCipherSuites();
        }

        @Override
        public String[] getSupportedProtocols() {
            return real.getSupportedProtocols();
        }

        @Override
        public String[] getEnabledProtocols() {
            return real.getEnabledProtocols();
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
            real.addHandshakeCompletedListener(listener);
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
            real.removeHandshakeCompletedListener(listener);
        }

        @Override
        public void setUseClientMode(boolean mode) {
            real.setUseClientMode(mode);
        }

        @Override
        public boolean getUseClientMode() {
            return real.getUseClientMode();
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            real.setNeedClientAuth(need);
        }

        @Override
        public boolean getNeedClientAuth() {
            return real.getNeedClientAuth();
        }

        @Override
        public void setWantClientAuth(boolean want) {
            real.setWantClientAuth(want);
        }

        @Override
        public boolean getWantClientAuth() {
            return real.getWantClientAuth();
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            real.setEnableSessionCreation(flag);
        }

        @Override
        public boolean getEnableSessionCreation() {
            return real.getEnableSessionCreation();
        }
    }

    private static SSLSession session(String protocol, String suite) {
        return (SSLSession) Proxy.newProxyInstance(SSLSession.class.getClassLoader(), new Class<?>[] {SSLSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getProtocol":
                            return protocol;
                        case "getCipherSuite":
                            return suite;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
