package com.limelight.binding.usb.tunnel;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECPrivateKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * A stand-in for the PC's USB helper on 127.0.0.1: a TLS server that asks for the phone's
 * certificate and shows the one it's given, whatever its key, which the test then plays the
 * helper's side of the protocol through, one frame at a time.
 */
final class FakeHelper implements Closeable {
    // Only so a broken test fails instead of hanging; no test waits for it to pass. A wait here,
    // in FakeExporter or in TunnelEvents that runs out says what the tunnel's threads were doing
    // (StuckThreads)
    static final int SAFETY_MS = 10_000;

    final SSLServerSocket server;
    final SwutTls.Identity me;
    SSLSocket socket;
    InputStream in;
    OutputStream out;
    X509Certificate phoneCertificate;
    // What it shows instead of its own certificate alone, if set before accept()
    volatile X509Certificate[] chain;

    FakeHelper(SwutTls.Identity me) throws Exception {
        this(me, null, null);
    }

    /** protocols and suites null for the platform's defaults. */
    FakeHelper(SwutTls.Identity me, String[] protocols, String[] suites) throws Exception {
        this.me = me;
        // Whichever TLS provider comes first (TlsProvider)
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {new ServerKey(this)}, new TrustManager[] {new AnyClient()}, null);
        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        server.setNeedClientAuth(true);
        server.setSoTimeout(SAFETY_MS);
        if (protocols != null) {
            server.setEnabledProtocols(protocols);
        }
        if (suites != null) {
            server.setEnabledCipherSuites(suites);
        }
    }

    int port() {
        return server.getLocalPort();
    }

    /** Takes the phone's connection and finishes TLS with it. */
    FakeHelper accept() throws IOException {
        try {
            socket = (SSLSocket) server.accept();
        }
        catch (SocketTimeoutException e) {
            throw StuckThreads.timedOut("the phone never connected in " + server.getSoTimeout() + " ms", e);
        }
        socket.setSoTimeout(SAFETY_MS);
        try {
            socket.startHandshake();
        }
        catch (SocketTimeoutException e) {
            throw StuckThreads.timedOut("the phone never finished TLS in " + socket.getSoTimeout() + " ms", e);
        }
        phoneCertificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];
        in = socket.getInputStream();
        out = socket.getOutputStream();
        return this;
    }

    byte[] phoneFp() throws GeneralSecurityException {
        return SwutCodec.fingerprint(phoneCertificate.getEncoded());
    }

    byte[] helperFp() throws GeneralSecurityException {
        return SwutCodec.fingerprint(me.certificate.getEncoded());
    }

    void send(byte[] frame) throws IOException {
        out.write(frame);
        out.flush();
    }

    byte[] read() throws IOException {
        try {
            return UsbTunnel.readFrame(in);
        }
        catch (SocketTimeoutException e) {
            throw StuckThreads.timedOut("nothing from the phone in " + socket.getSoTimeout() + " ms", e);
        }
    }

    /** Every frame until the phone closes the connection, however it closes it. */
    List<byte[]> readUntilClosed() {
        List<byte[]> frames = new ArrayList<>();
        try {
            while (true) {
                frames.add(read());
            }
        }
        catch (SocketTimeoutException e) {
            throw new AssertionError("the phone never closed the connection: " + e.getMessage(), e);
        }
        catch (IOException e) {
            return frames;
        }
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
            server.close();
        }
        catch (IOException e) {
            // Closed either way
        }
    }

    // ---------------------------------------------------------------- identities
    /** A certificate and its key, from the certificate's DER and the key's private scalar. */
    static SwutTls.Identity identity(byte[] certificate, byte[] d) throws Exception {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate));
        PrivateKey key = KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), com.limelight.binding.audio.securemic.SwmcCodec.p256()));
        return new SwutTls.Identity(cert, key);
    }

    // Shows this one certificate, whatever key type the client wants
    private static final class ServerKey implements X509KeyManager {
        private final FakeHelper helper;

        ServerKey(FakeHelper helper) {
            this.helper = helper;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[] {"helper"};
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return "helper";
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            X509Certificate[] c = helper.chain;
            return c != null ? c : new X509Certificate[] {helper.me.certificate};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return helper.me.key;
        }
    }

    private static final class AnyClient implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
