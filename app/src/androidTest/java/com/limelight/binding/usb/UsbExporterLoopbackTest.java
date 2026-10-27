package com.limelight.binding.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.binding.usb.tunnel.SwutCodec;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;

/**
 * The real exporter (usbipdcpp, through NativeUsbIp) behind UsbExporter's one local connection,
 * with no USB device: emulators have none, so binding and the device checks are stood in for and
 * the export serves an empty device list. It listens on 127.0.0.1 only, closes a connection it
 * didn't authorise, takes the one connect() makes, and stops listening when the device goes back.
 * It runs where the APK has the exporter's library: 64-bit ARM, and x86_64 in a debug build.
 */
@RunWith(AndroidJUnit4.class)
public class UsbExporterLoopbackTest {
    private UsbExporter exporter;
    private UsbExporter.Export export;

    // The real native exporter, with bind and the device checks stood in for
    private static final class NoDevice implements UsbExporter.Natives {
        final UsbExporter.Natives real = new UsbExporter.RealNatives();

        @Override public long start() throws IOException { return real.start(); }
        @Override public int localPort(long handle) throws IOException { return real.localPort(handle); }
        @Override public void authorize(long handle, int port) throws IOException { real.authorize(handle, port); }
        @Override public void revoke(long handle, int port) { real.revoke(handle, port); }
        @Override public String bind(long handle, int fd) { return "1-1"; }
        @Override public void unbind(long handle, String busId) { }
        @Override public void stop(long handle) throws IOException { real.stop(handle); }
        @Override public int speed(int fd) { return 2; }
        @Override public byte[] reportDescriptor(int fd, int iface, int length) { return PAD_REPORT; }
    }

    // The shared vectors' test pad (docs/dev/usb-protocol.md, "Test vectors")
    static final byte[] DEVICE = hex("120100020000004009120100000101020001");
    static final byte[] CONFIGURATION = hex("090222000101008032090400000103000000092111010001222800"
            + "07058103400004");
    static final byte[] PAD_REPORT = hex("05010905a101150025017501951005091901291081020501150026ff007508"
            + "9502093009318102c0");

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private static final class FakePad implements UsbExporter.Device {
        @Override public String name() { return "/dev/bus/usb/001/002"; }
        @Override public String productName() { return "Test pad"; }
        @Override public byte[] rawDescriptors() {
            byte[] raw = new byte[DEVICE.length + CONFIGURATION.length];
            System.arraycopy(DEVICE, 0, raw, 0, DEVICE.length);
            System.arraycopy(CONFIGURATION, 0, raw, DEVICE.length, CONFIGURATION.length);
            return raw;
        }
        @Override public int fd() { return -1; }
        @Override public void close() { }
    }

    @Before
    public void shareTheStandIn() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File library = new File(target.getApplicationInfo().nativeLibraryDir, "libmoonlight_usbip.so");
        Assume.assumeTrue("this ABI has no exporter library", library.isFile());
        exporter = new UsbExporter(new NoDevice(), Socket::new, Runnable::run, null);
        export = exporter.start(new FakePad(), false);
    }

    @After
    public void giveItBack() {
        if (exporter != null) {
            exporter.stop();
        }
    }

    // USB/IP's OP_REQ_DEVLIST; the exporter answers with its devices, none here, then closes
    private static void assertEmptyDeviceList(Socket socket) throws IOException {
        socket.setSoTimeout(3000);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeShort(0x0111);
        out.writeShort(0x8005);
        out.writeInt(0);
        out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        assertEquals(0x0111, in.readUnsignedShort());
        assertEquals(0x0005, in.readUnsignedShort());
        assertEquals(0, in.readInt());
        assertEquals(0, in.readInt());
    }

    private static void assertClosedByTheExporter(Socket socket) throws IOException {
        socket.setSoTimeout(3000);
        try {
            socket.getOutputStream().write(new byte[] {0x01, 0x11, (byte) 0x80, 0x05, 0, 0, 0, 0});
            assertEquals(-1, socket.getInputStream().read());
        }
        catch (java.net.SocketException reset) {
            // a reset closes it too
        }
    }

    @Test
    public void theConnectionStarwireAuthorisedIsServed() throws Exception {
        try (Socket socket = exporter.connect(export, 3000)) {
            assertEquals("127.0.0.1", socket.getLocalAddress().getHostAddress());
            assertEmptyDeviceList(socket);
        }
    }

    // Any app on the phone can reach 127.0.0.1, so a connection nobody authorised is closed
    @Test
    public void aConnectionNobodyAuthorisedIsClosed() throws Exception {
        try (Socket stranger = new Socket("127.0.0.1", export.port)) {
            assertClosedByTheExporter(stranger);
        }
        // and it didn't use up Starwire's own
        try (Socket socket = exporter.connect(export, 3000)) {
            assertEmptyDeviceList(socket);
        }
    }

    @Test
    public void afterItsOneConnectionTheExporterTakesNoOther() throws Exception {
        try (Socket socket = exporter.connect(export, 3000)) {
            try {
                exporter.connect(export, 3000);
                fail("a second connection");
            }
            catch (IllegalStateException expected) {
            }
            try (Socket stranger = new Socket("127.0.0.1", export.port)) {
                assertClosedByTheExporter(stranger);
            }
            assertEmptyDeviceList(socket);
        }
    }

    // Every 127.x.y.z is the phone itself: while Starwire's socket holds its port on 127.0.0.1,
    // another app's socket on 127.0.0.2 can have the same port number, and mustn't get in with it
    @Test
    public void theAuthorisedPortCountsFrom127001Only() throws Exception {
        try (Socket other = new Socket()) {
            try {
                other.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0));
            }
            catch (IOException e) {
                Assume.assumeNoException("this kernel won't bind 127.0.0.2", e);
            }
            UsbExporter.Natives natives = new UsbExporter.RealNatives();
            natives.authorize(export.handle, other.getLocalPort());
            try {
                other.connect(new InetSocketAddress("127.0.0.1", export.port), 3000);
                assertClosedByTheExporter(other);
            }
            finally {
                natives.revoke(export.handle, other.getLocalPort());
            }
        }
    }

    // Nothing on the phone's other addresses (Wi-Fi, the emulator's network) reaches it
    @Test
    public void itListensOnLoopbackOnly() throws Exception {
        InetAddress other = null;
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    other = a;
                }
            }
        }
        Assume.assumeTrue("no address besides loopback", other != null);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(other, export.port), 3000);
            fail("the exporter answered on " + other.getHostAddress());
        }
        catch (IOException expected) {
            // refused: nothing listens there
        }
    }

    @Test
    public void nothingListensOnceTheDeviceGoesBack() throws Exception {
        int port = export.port;
        exporter.stop();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            // Another app may have taken the freed port since; then it isn't the exporter's
            assertClosedByTheExporter(socket);
        }
        catch (IOException expected) {
            // refused: nothing listens there any more
        }
        assertTrue(exporter.current() == null);
    }

    @Test
    public void theShareFrameIsTheVectorsPad() throws Exception {
        assertEquals(SwutCodec.TYPE_SHARE, SwutCodec.typeOf(export.shareFrame()));
        assertEquals("1-1", SwutCodec.parseShare(export.shareFrame()).busId);
    }
}
