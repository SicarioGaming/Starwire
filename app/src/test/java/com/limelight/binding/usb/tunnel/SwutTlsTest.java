package com.limelight.binding.usb.tunnel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * The tunnel's TLS on Starwire's side (docs/dev/usb-protocol.md, "TLS"): the certificate it makes
 * for each connection, and the versions and suites it allows, on the JDK's TLS and on Conscrypt.
 * UsbTunnelTest has the handshakes.
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
