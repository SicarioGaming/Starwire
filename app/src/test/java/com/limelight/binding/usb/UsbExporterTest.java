package com.limelight.binding.usb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.limelight.binding.usb.tunnel.SwutCodec;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * UsbExporter's rules, with the native exporter, the device and the socket stood in for: the
 * device rules decide before anything is exported, one device at a time, the one local connection
 * is bound to 127.0.0.1 and authorised before it connects, and the device goes back on stop or
 * unplug, in V+'s order. No device, network or native code.
 */
public class UsbExporterTest {
    private static Map<String, String> cases;

    private static Map<String, String> cases() throws IOException {
        if (cases == null) {
            try (InputStream in = UsbExporterTest.class.getClassLoader().getResourceAsStream("swut-v1-policy.txt")) {
                assertNotNull(in);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                }
                cases = new LinkedHashMap<>();
                for (String line : new String(out.toByteArray(), StandardCharsets.US_ASCII).split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        cases.put(line.substring(0, eq), line.substring(eq + 1));
                    }
                }
            }
        }
        return cases;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private static SwutCodec.Share caseShare(String name) throws IOException {
        return SwutCodec.parseShare(hex(cases().get(name + ".share")));
    }

    // ---------------------------------------------------------------- stand-ins
    static class FakeDevice implements UsbExporter.Device {
        final String name;
        final byte[] raw;
        boolean closed;

        FakeDevice(String name, SwutCodec.Share share) {
            this.name = name;
            raw = new byte[share.deviceDescriptor.length + share.configuration.length];
            System.arraycopy(share.deviceDescriptor, 0, raw, 0, share.deviceDescriptor.length);
            System.arraycopy(share.configuration, 0, raw, share.deviceDescriptor.length, share.configuration.length);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String productName() {
            return "Test pad";
        }

        @Override
        public byte[] rawDescriptors() {
            return raw.clone();
        }

        @Override
        public int fd() {
            return 42;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class FakeNatives implements UsbExporter.Natives {
        final List<String> calls = new ArrayList<>();
        final Map<Integer, byte[]> reports = new LinkedHashMap<>();
        int speed = 2;
        String busId = "1-1";
        boolean failBind;
        long nextHandle = 7;

        @Override
        public long start() {
            calls.add("start");
            return nextHandle++;
        }

        @Override
        public int localPort(long handle) {
            calls.add("localPort " + handle);
            return 40000 + (int) handle;
        }

        @Override
        public void authorize(long handle, int sourcePort) {
            calls.add("authorize " + handle + " " + sourcePort);
        }

        @Override
        public void revoke(long handle, int sourcePort) {
            calls.add("revoke " + handle + " " + sourcePort);
        }

        @Override
        public String bind(long handle, int fd) throws IOException {
            calls.add("bind " + handle + " " + fd);
            if (failBind) {
                throw new IOException("Unable to bind USB device");
            }
            return busId;
        }

        @Override
        public void unbind(long handle, String busId) {
            calls.add("unbind " + handle + " " + busId);
        }

        @Override
        public void stop(long handle) {
            calls.add("stop " + handle);
        }

        @Override
        public int speed(int fd) {
            calls.add("speed " + fd);
            return speed;
        }

        @Override
        public byte[] reportDescriptor(int fd, int iface, int length) {
            calls.add("report " + fd + " " + iface + " " + length);
            return reports.get(iface);
        }
    }

    // A socket that only records what was asked of it: it never opens anything
    static final class FakeSocket extends Socket {
        final List<String> calls;
        final boolean failConnect;
        boolean reuseAddress = true;
        boolean closed;

        FakeSocket(List<String> calls, boolean failConnect) {
            this.calls = calls;
            this.failConnect = failConnect;
        }

        @Override
        public void setReuseAddress(boolean on) {
            reuseAddress = on;
            calls.add("reuseAddress " + on);
        }

        @Override
        public void bind(SocketAddress local) {
            InetSocketAddress a = (InetSocketAddress) local;
            calls.add("bind " + a.getAddress().getHostAddress() + ":" + a.getPort());
        }

        @Override
        public int getLocalPort() {
            return 50123;
        }

        @Override
        public void connect(SocketAddress remote, int timeout) throws IOException {
            InetSocketAddress a = (InetSocketAddress) remote;
            calls.add("connect " + a.getAddress().getHostAddress() + ":" + a.getPort());
            if (failConnect) {
                throw new java.net.ConnectException("refused");
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            calls.add("socket close");
        }
    }

    // Android's unplug broadcast: whether it's heard now, each start and stop, and any that
    // happened without the exporter's lock, the one that guards which device is shared
    final class FakeWatch implements UsbExporter.UnplugWatch {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        volatile boolean on;
        volatile Runnable onStop;

        @Override
        public void start() {
            events.add(Thread.holdsLock(exporter) ? "start" : "start without the lock");
            on = true;
        }

        @Override
        public void stop() {
            events.add(Thread.holdsLock(exporter) ? "stop" : "stop without the lock");
            Runnable r = onStop;
            onStop = null;
            if (r != null) {
                r.run();
            }
            on = false;
        }
    }

    private final FakeNatives natives = new FakeNatives();
    private final List<FakeSocket> sockets = new ArrayList<>();
    private final List<UsbExporter.Export> gone = new ArrayList<>();
    private final FakeWatch watch = new FakeWatch();
    private boolean failConnect;
    private final UsbExporter exporter = new UsbExporter(natives, () -> {
        FakeSocket s = new FakeSocket(natives.calls, failConnect);
        sockets.add(s);
        return s;
    }, Runnable::run, gone::add, watch);

    private static final Object BLOCKED = new Object();

    // Runs task on another thread and returns what it returned, or BLOCKED once that thread waits
    // for a lock the calling thread holds. In these tests the exporter's is the only lock that
    // thread can meet. No clock: it watches the thread until it has finished or is blocked.
    private static Object onAnotherThread(List<Thread> started, Callable<Object> task) {
        Object[] result = new Object[1];
        Thread t = new Thread(() -> {
            try {
                result[0] = task.call();
            }
            catch (Exception e) {
                result[0] = e;
            }
        });
        started.add(t);
        t.start();
        while (true) {
            Thread.State state = t.getState();
            if (state == Thread.State.TERMINATED) {
                try {
                    t.join();
                }
                catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return result[0];
            }
            if (state == Thread.State.BLOCKED) {
                return BLOCKED;
            }
            Thread.yield();
        }
    }

    private FakeDevice device(String caseName) throws IOException {
        SwutCodec.Share share = caseShare(caseName);
        for (int i = 0; i < share.reportInterfaces.length; i++) {
            natives.reports.put(share.reportInterfaces[i], share.reports[i]);
        }
        return new FakeDevice("/dev/bus/usb/001/00" + (natives.reports.size() + 2), share);
    }

    private UsbDeviceRules.Decision refusal(FakeDevice d, boolean confirmed) throws IOException {
        try {
            exporter.start(d, confirmed);
            fail("exported");
            return null;
        }
        catch (UsbExporter.Refused r) {
            return r.decision;
        }
    }

    // ---------------------------------------------------------------- the tests
    // The export describes exactly what the rules checked: for the shared pad, the vectors' SHARE
    @Test
    public void anAllowedPadIsExportedAndItsShareIsTheContractsOwn() throws Exception {
        FakeDevice pad = device("pad");
        UsbExporter.Export export = exporter.start(pad, false);
        assertEquals("1-1", export.busId);
        assertEquals(40007, export.port);
        assertEquals(2, export.speed);
        assertEquals(pad.name, export.deviceName);
        assertArrayEquals(hex(cases().get("pad.share")), export.shareFrame());
        assertFalse(pad.closed);
        assertSame(export, exporter.current());
        // The report was read before the exporter started, and only that one
        assertEquals(List.of("speed 42", "report 42 0 40", "start", "localPort 7", "bind 7 42"), natives.calls);
    }

    @Test
    public void aRefusedDeviceIsNeverExportedAndGoesBack() throws Exception {
        FakeDevice storage = device("interface_storage");
        assertEquals("refuse 2", refusal(storage, true).toString());
        assertTrue(storage.closed);
        assertNull(exporter.current());
        assertTrue(natives.calls.toString(), natives.calls.isEmpty());
    }

    // Refused by its class codes, so no interface is taken from Android to read its report
    @Test
    public void aDeviceRefusedBeforeItsReportsNeverHasThemRead() throws Exception {
        FakeDevice hub = device("device_class_hub");
        assertEquals("refuse 1", refusal(hub, false).toString());
        assertTrue(natives.calls.toString(), natives.calls.isEmpty());
    }

    @Test
    public void aKeyboardNeedsYourYesAndThenCarriesTheFlag() throws Exception {
        FakeDevice keyboard = device("report_keyboard");
        assertEquals("ask", refusal(keyboard, false).toString());
        assertTrue(keyboard.closed);
        assertFalse("the exporter never started", natives.calls.contains("start"));

        FakeDevice again = new FakeDevice("/dev/bus/usb/001/009", caseShare("report_keyboard"));
        UsbExporter.Export export = exporter.start(again, true);
        assertEquals(SwutCodec.FLAG_CONFIRMED_INPUT, export.share.flags);
        assertEquals(SwutCodec.FLAG_CONFIRMED_INPUT, export.shareFrame()[SwutCodec.FRAME_HEADER]);
    }

    // A yes for an allowed device doesn't set the flag: it's for keyboards and mice
    @Test
    public void aYesForAPadSetsNoFlag() throws Exception {
        assertEquals(0, exporter.start(device("pad"), true).share.flags);
    }

    @Test
    public void usb3AndUnknownSpeedsAreRefusedWithRule10BeforeAnyReportIsRead() throws Exception {
        natives.speed = 4;
        assertEquals("refuse 10", refusal(device("pad"), false).toString());
        natives.speed = 0;
        assertEquals("refuse 10", refusal(device("pad"), false).toString());
        assertEquals(List.of("speed 42", "speed 42"), natives.calls);
    }

    // The device's own report decides, not the one SHARE's case was made with
    @Test
    public void aReportThatDoesntMatchItsHidDescriptorIsRefused() throws Exception {
        FakeDevice pad = device("pad");
        natives.reports.put(0, java.util.Arrays.copyOf(natives.reports.get(0), 39));
        assertEquals("refuse 7", refusal(pad, false).toString());
        assertFalse(natives.calls.contains("start"));
    }

    @Test
    public void oneDeviceAtATime() throws Exception {
        UsbExporter.Export first = exporter.start(device("pad"), false);
        FakeDevice second = new FakeDevice("/dev/bus/usb/001/007", caseShare("pad"));
        try {
            exporter.start(second, false);
            fail("a second device was shared");
        }
        catch (IllegalStateException expected) {
        }
        assertTrue("the second device goes back", second.closed);
        assertSame(first, exporter.current());
    }

    @Test
    public void theLocalConnectionIsBoundToLoopbackAndAuthorisedBeforeItConnects() throws Exception {
        UsbExporter.Export export = exporter.start(device("pad"), false);
        natives.calls.clear();
        Socket socket = exporter.connect(export, 1000);
        assertSame(sockets.get(0), socket);
        assertFalse(sockets.get(0).reuseAddress);
        assertEquals(List.of("reuseAddress false", "bind 127.0.0.1:0", "authorize 7 50123", "connect 127.0.0.1:40007"),
                natives.calls);
    }

    @Test
    public void theExporterTakesOneConnection() throws Exception {
        UsbExporter.Export export = exporter.start(device("pad"), false);
        exporter.connect(export, 1000);
        try {
            exporter.connect(export, 1000);
            fail("a second connection");
        }
        catch (IllegalStateException expected) {
        }
        assertEquals("one socket made", 1, sockets.size());
        assertEquals(1, natives.calls.stream().filter(c -> c.startsWith("authorize")).count());
    }

    // A connection that didn't happen leaves no authorisation behind, and can be tried again
    @Test
    public void aFailedConnectionTakesItsAuthorisationBack() throws Exception {
        UsbExporter.Export export = exporter.start(device("pad"), false);
        failConnect = true;
        try {
            exporter.connect(export, 1000);
            fail("connected");
        }
        catch (IOException expected) {
        }
        assertTrue(natives.calls.contains("revoke 7 50123"));
        assertTrue(sockets.get(0).closed);
        failConnect = false;
        assertNotNull(exporter.connect(export, 1000));
    }

    // The tunnel's end first, so the session drains, then unbind, stop, and the device goes back
    @Test
    public void stopGivesTheDeviceBackInV_PlusOrder() throws Exception {
        FakeDevice pad = device("pad");
        UsbExporter.Export export = exporter.start(pad, false);
        exporter.connect(export, 1000);
        natives.calls.clear();
        exporter.stop();
        assertEquals(List.of("socket close", "unbind 7 1-1", "stop 7"), natives.calls);
        assertTrue(pad.closed);
        assertNull(exporter.current());
        assertTrue("stop isn't an unplug", gone.isEmpty());
        exporter.stop();
        assertEquals("a second stop does nothing", 3, natives.calls.size());
        try {
            exporter.connect(export, 1000);
            fail("connected after stop");
        }
        catch (IllegalStateException expected) {
        }
    }

    @Test
    public void unpluggingTheSharedDeviceReleasesItAndSaysSo() throws Exception {
        FakeDevice pad = device("pad");
        UsbExporter.Export export = exporter.start(pad, false);
        exporter.deviceDetached("/dev/bus/usb/009/009");
        assertSame("another device's unplug changes nothing", export, exporter.current());
        assertTrue(gone.isEmpty());
        exporter.deviceDetached(pad.name);
        assertNull(exporter.current());
        assertTrue(pad.closed);
        assertEquals(List.of(export), gone);
        assertTrue(natives.calls.contains("unbind 7 1-1"));
        assertTrue(natives.calls.contains("stop 7"));
        // And the next device can be shared, with its own exporter
        assertEquals(8, exporter.start(device("pad"), false).handle);
    }

    @Test
    public void aFailedBindStopsTheExporterItStarted() throws Exception {
        FakeDevice pad = device("pad");
        natives.failBind = true;
        try {
            exporter.start(pad, false);
            fail("exported");
        }
        catch (IOException expected) {
        }
        assertTrue(natives.calls.contains("stop 7"));
        assertFalse("nothing was bound, so nothing to unbind", natives.calls.contains("unbind 7 1-1"));
        assertTrue(pad.closed);
        assertNull(exporter.current());
    }

    // A bus id SHARE can't carry (the helper may hand it to a command) is never exported
    @Test
    public void aBusIdShareCantCarryStopsTheExport() throws Exception {
        FakeDevice pad = device("pad");
        natives.busId = "1-1&calc";
        try {
            exporter.start(pad, false);
            fail("exported");
        }
        catch (IOException expected) {
        }
        assertTrue(natives.calls.contains("unbind 7 1-1&calc"));
        assertTrue(natives.calls.contains("stop 7"));
        assertTrue(pad.closed);
        assertNull(exporter.current());
    }

    @Test
    public void androidsRawDescriptorsSplitIntoDeviceAndConfiguration() throws Exception {
        SwutCodec.Share pad = caseShare("pad");
        byte[] raw = new FakeDevice("x", pad).raw;
        byte[][] parts = UsbExporter.split(raw);
        assertArrayEquals(pad.deviceDescriptor, parts[0]);
        assertArrayEquals(pad.configuration, parts[1]);
        assertNull("bytes after the only configuration", UsbExporter.split(java.util.Arrays.copyOf(raw, raw.length + 1)));
        assertNull("cut short", UsbExporter.split(java.util.Arrays.copyOf(raw, raw.length - 1)));
        assertNull(UsbExporter.split(new byte[20]));
        assertNull(UsbExporter.split(null));
        // Two configurations: the first is kept, and the rules refuse the device by rule 6
        byte[] two = java.util.Arrays.copyOf(raw, raw.length + pad.configuration.length);
        System.arraycopy(pad.configuration, 0, two, raw.length, pad.configuration.length);
        two[17] = 2;
        assertArrayEquals(pad.configuration, UsbExporter.split(two)[1]);
    }

    @Test
    public void descriptorsThatDontSplitAreRefusedWithRule7() throws Exception {
        FakeDevice pad = device("pad");
        FakeDevice cut = new FakeDevice(pad.name, caseShare("pad")) {
            @Override
            public byte[] rawDescriptors() {
                return java.util.Arrays.copyOf(super.rawDescriptors(), 30);
            }
        };
        assertEquals("refuse 7", refusal(cut, false).toString());
        assertTrue(cut.closed);
    }

    // ---------------------------------------------------------------- the review's fixes
    // The unplug watch belongs to the export: it starts with it and stops with it, always under
    // the lock that guards which device is shared
    @Test
    public void theUnplugWatchStartsAndStopsWithItsExport() throws Exception {
        FakeDevice pad = device("pad");
        exporter.start(pad, false);
        assertTrue(watch.on);
        exporter.stop();
        assertFalse(watch.on);
        exporter.start(device("pad"), false);
        exporter.deviceDetached(exporter.current().deviceName);
        assertFalse(watch.on);
        assertEquals("refuse 2", refusal(device("interface_storage"), true).toString());
        natives.speed = 4;
        assertEquals("refuse 10", refusal(device("pad"), false).toString());
        assertFalse(watch.on);
        assertEquals("a refused device is never watched", List.of("start", "stop", "start", "stop"), watch.events);
    }

    // A second start fails without touching the first device's watch
    @Test
    public void aSecondStartLeavesTheFirstExportsWatchAlone() throws Exception {
        exporter.start(device("pad"), false);
        try {
            exporter.start(new FakeDevice("/dev/bus/usb/001/007", caseShare("pad")), false);
            fail("a second device was shared");
        }
        catch (IllegalStateException expected) {
        }
        assertTrue("the first device's unplug is no longer heard", watch.on);
        assertEquals(List.of("start"), watch.events);
    }

    // A start that comes while a stop is giving the last device back gets a watch of its own.
    // Here it comes while the stop is ending the old device's watch: it waits for the lock, so
    // the stop can't end the new device's watch instead
    @Test
    public void aStartDuringAStopKeepsItsOwnWatch() throws Exception {
        exporter.start(device("pad"), false);
        FakeDevice next = new FakeDevice("/dev/bus/usb/001/008", caseShare("pad"));
        List<Thread> started = new ArrayList<>();
        Object[] during = new Object[1];
        watch.onStop = () -> during[0] = onAnotherThread(started, () -> exporter.start(next, false));
        exporter.stop();
        for (Thread t : started) {
            t.join();
        }
        assertSame("the start went ahead without waiting for the stop", BLOCKED, during[0]);
        assertNotNull(exporter.current());
        assertEquals(next.name, exporter.current().deviceName);
        assertTrue("the new device's unplug isn't heard", watch.on);
        assertEquals(List.of("start", "stop", "start"), watch.events);
    }
}
