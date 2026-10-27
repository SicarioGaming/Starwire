package com.limelight.binding.usb.tunnel;

import android.annotation.SuppressLint;

import com.limelight.binding.audio.securemic.SwmcCodec;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * The TLS the USB tunnel runs in, Starwire's side (docs/dev/usb-protocol.md, "TLS"): TLS 1.3, or
 * TLS 1.2 with ECDHE_ECDSA and AES-GCM or ChaCha20-Poly1305, and nothing else. Starwire shows a
 * self-signed P-256 certificate it makes for each connection, and takes any helper certificate
 * with a P-256 key. Neither certificate means anything on its own: BIND ties both to the pairing.
 */
final class SwutTls {
    static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    // TLS 1.3's suites first (all AEAD, and the key exchange is always ECDHE here, as no
    // pre-shared key is ever offered), then TLS 1.2's ECDHE_ECDSA with an AEAD
    static final String[] CIPHER_SUITES = {
            "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
    };
    static final String SUBJECT = "CN=Starwire";
    private static final String ALIAS = "starwire";
    // Fixed dates, so the certificate says nothing about the phone's clock. Nobody checks them.
    static final long NOT_BEFORE_MS = 946684800000L;   // 1 Jan 2000, 00:00 UTC
    static final long NOT_AFTER_MS = 2524607999000L;   // 31 Dec 2049, 23:59:59 UTC, UTCTime's last year
    private static final int SERIAL_BYTES = 16;

    private SwutTls() {
    }

    /** One side's certificate and its private key. */
    static final class Identity {
        final X509Certificate certificate;
        final PrivateKey key;

        Identity(X509Certificate certificate, PrivateKey key) {
            this.certificate = certificate;
            this.key = key;
        }
    }

    /**
     * A new certificate for one connection: a new P-256 key, self-signed with ECDSA and SHA-256,
     * subject and issuer CN=Starwire and nothing else, a random serial number, no extensions.
     */
    static Identity newIdentity(SecureRandom random) throws GeneralSecurityException {
        KeyPair pair = SwmcCodec.newKeyPair(random);
        byte[] serial = new byte[SERIAL_BYTES];
        random.nextBytes(serial);
        serial[0] &= 0x7F;   // positive, as X.509 wants
        BigInteger number = new BigInteger(1, serial);
        if (number.signum() == 0) {
            number = BigInteger.ONE;
        }
        X500Name name = new X500Name(SUBJECT);
        try {
            byte[] der = new JcaX509v3CertificateBuilder(name, number, new Date(NOT_BEFORE_MS), new Date(NOT_AFTER_MS),
                    name, pair.getPublic())
                    .build(new JcaContentSignerBuilder("SHA256withECDSA").setSecureRandom(random).build(pair.getPrivate()))
                    .getEncoded();
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            return new Identity(certificate, pair.getPrivate());
        }
        catch (OperatorCreationException | java.io.IOException e) {
            throw new GeneralSecurityException("couldn't make the tunnel's certificate", e);
        }
    }

    /**
     * A TLS context for one connection, so no session is ever offered again: it shows this
     * identity and takes a helper certificate only if it has a P-256 key.
     */
    static SSLContext clientContext(Identity me) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {new OneKey(me)}, new TrustManager[] {new AnyP256Key()}, null);
        return context;
    }

    /** Only the versions and suites the contract allows, of those this platform has. */
    static void restrict(SSLSocket socket) throws SSLException {
        String[] protocols = both(PROTOCOLS, socket.getSupportedProtocols());
        String[] suites = both(CIPHER_SUITES, socket.getSupportedCipherSuites());
        if (protocols.length == 0 || suites.length == 0) {
            throw new SSLException("this phone has neither TLS 1.3 nor TLS 1.2 with ECDHE_ECDSA");
        }
        socket.setEnabledProtocols(protocols);
        socket.setEnabledCipherSuites(suites);
    }

    /**
     * Restricts the socket to the contract's TLS, does the handshake and checks what it agreed,
     * whatever the platform let through: the version, the suite, and one certificate each. Returns
     * each certificate's fingerprint as this side saw it in the finished handshake, the phone's
     * first.
     */
    static byte[][] handshake(SSLSocket socket) throws IOException, GeneralSecurityException {
        restrict(socket);
        socket.startHandshake();
        SSLSession session = socket.getSession();
        checkSession(session);
        Certificate[] mine = session.getLocalCertificates();
        Certificate[] theirs = session.getPeerCertificates();
        if (mine == null || mine.length != 1 || theirs == null || theirs.length != 1) {
            throw new SSLException("each side must show exactly one certificate");
        }
        return new byte[][] {SwutCodec.fingerprint(mine[0].getEncoded()), SwutCodec.fingerprint(theirs[0].getEncoded())};
    }

    /** Checks what the finished handshake agreed, whatever the platform let through. */
    static void checkSession(SSLSession session) throws SSLException {
        if (session == null || !Arrays.asList(PROTOCOLS).contains(session.getProtocol())
                || !Arrays.asList(CIPHER_SUITES).contains(session.getCipherSuite())) {
            throw new SSLException("the helper agreed to "
                    + (session != null ? session.getProtocol() + " " + session.getCipherSuite() : "nothing"));
        }
    }

    /** Whether a key is on P-256 (secp256r1), as this platform's provider has the curve. */
    static boolean isP256(PublicKey key) throws GeneralSecurityException {
        if (!(key instanceof ECPublicKey)) {
            return false;
        }
        ECParameterSpec have = ((ECPublicKey) key).getParams();
        ECParameterSpec want = SwmcCodec.p256();
        return have != null && have.getCurve().equals(want.getCurve()) && have.getGenerator().equals(want.getGenerator())
                && have.getOrder().equals(want.getOrder()) && have.getCofactor() == want.getCofactor();
    }

    private static String[] both(String[] wanted, String[] supported) {
        List<String> have = Arrays.asList(supported);
        List<String> out = new ArrayList<>();
        for (String s : wanted) {
            if (have.contains(s)) {
                out.add(s);
            }
        }
        return out.toArray(new String[0]);
    }

    // Starwire's own certificate, for whatever EC certificate the helper asks for
    private static final class OneKey implements X509KeyManager {
        private final Identity me;

        OneKey(Identity me) {
            this.me = me;
        }

        private static boolean isEc(String keyType) {
            return keyType != null && keyType.startsWith("EC");
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return isEc(keyType) ? new String[] {ALIAS} : null;
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            if (keyTypes != null) {
                for (String keyType : keyTypes) {
                    if (isEc(keyType)) {
                        return ALIAS;
                    }
                }
            }
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? new X509Certificate[] {me.certificate} : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? me.key : null;
        }
    }

    // The contract has no chain, name or dates to check: the helper's certificate is self-signed,
    // and BIND_ACK, not this, proves it belongs to someone with the pairing. This only holds the
    // helper to the one certificate with a P-256 key the contract allows.
    @SuppressLint("CustomX509TrustManager")
    private static final class AnyP256Key implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Starwire is never the TLS server here");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length != 1) {
                throw new CertificateException("the helper must send exactly one certificate");
            }
            try {
                if (!isP256(chain[0].getPublicKey())) {
                    throw new CertificateException("the helper's certificate doesn't have a P-256 key");
                }
            }
            catch (GeneralSecurityException e) {
                throw new CertificateException(e);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
