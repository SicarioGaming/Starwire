package com.limelight.binding.usb.tunnel;

import static org.junit.Assert.assertTrue;

import com.limelight.binding.audio.securemic.MicPairing;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The tunnel's loopback stand-ins for tests outside this package: the fake helper (FakeHelper) on
 * 127.0.0.1 with the helper's side of the handshake done as UsbTunnelTest does it, the vectors'
 * pairing, and a real UsbTunnel whose clock only moves when the test moves it. TLS is the JDK's
 * while it's open (TlsProvider), so the result doesn't depend on which tests ran before.
 */
public final class HelperOnLoopback implements Closeable {
    // Only so a broken test fails instead of hanging; no test waits for it to pass
    public static final int SAFETY_MS = FakeHelper.SAFETY_MS;
    private static final byte[] RECEIVER_ID = SwutVectors.hex("b0b1b2b3b4b5b6b7");

    private final TlsProvider tls;
    private final FakeHelper helper;

    public HelperOnLoopback() throws Exception {
        tls = TlsProvider.use(TlsProvider.JDK);
        try {
            helper = new FakeHelper(SwutTls.newIdentity(TlsProvider.seeded(2)));
        }
        catch (Exception e) {
            tls.close();
            throw e;
        }
    }

    /** The port it listens on, on 127.0.0.1 */
    public int port() {
        return helper.server.getLocalPort();
    }

    /** The vectors' SHARE for their test pad (frame.share), which the pad case shares too */
    public static byte[] vectorShare() throws IOException {
        return SwutVectors.hex(SwutVectors.read().get("frame.share"));
    }

    /** One of the device rules' cases (swut-v1-policy.txt): its SHARE frame */
    public static byte[] caseShare(String name) throws IOException {
        String share = SwutVectors.read(SwutPolicyCasesTest.FILE).get(name + ".share");
        if (share == null) {
            throw new IOException("no case " + name);
        }
        return SwutVectors.hex(share);
    }

    /** USB/IP's OP_REQ_IMPORT for this bus id: 40 bytes, as the PC's driver sends it first */
    public static byte[] opReqImport(String busId) {
        return SwutVectors.opReqImport(busId);
    }

    /** The microphone's pairing the vectors use, which this helper holds too: a new copy each time */
    public static MicPairing pairing(String computerUuid) {
        return new MicPairing(computerUuid, "PC", SwutVectors.DEVICE_ID, SwutVectors.PAIR_KEY, RECEIVER_ID,
                "Receiver", 1);
    }

    /** A real tunnel, with a clock that reads `now` and random numbers from a fixed seed */
    public static UsbTunnel tunnel(UsbTunnel.Exporter exporter, UsbTunnel.Listener listener, AtomicLong now)
            throws Exception {
        return new UsbTunnel(exporter, listener, now::get, TlsProvider.seeded(1), SwutTls::newIdentity, 2);
    }

    /**
     * Takes the phone's next connection and plays the helper up to BIND_ACK: TLS, HELLO for
     * version 1, a BIND that must check out against the vectors' pairing and both certificates,
     * then a BIND_ACK for it.
     */
    public void bind() throws Exception {
        helper.accept();
        byte[] hello = SwutCodec.hello(SwutVectors.HELPER_NONCE, 1, 1);
        helper.send(hello);
        byte[] bind = helper.read();
        byte[] usbKey = SwutCodec.usbKey(SwutVectors.PAIR_KEY, SwutVectors.DEVICE_ID);
        assertTrue("the BIND doesn't check out",
                SwutCodec.bindChecksOut(usbKey, hello, helper.phoneFp(), helper.helperFp(), bind));
        helper.send(SwutCodec.bindAck(usbKey, hello, helper.phoneFp(), helper.helperFp(), bind));
    }

    /** The phone's next frame */
    public byte[] read() throws IOException {
        return helper.read();
    }

    public void send(byte[] frame) throws IOException {
        helper.send(frame);
    }

    /** Every frame until the phone closes the connection, however it closes it */
    public List<byte[]> readUntilClosed() {
        return helper.readUntilClosed();
    }

    @Override
    public void close() {
        try {
            helper.close();
        }
        finally {
            tls.close();
        }
    }
}
