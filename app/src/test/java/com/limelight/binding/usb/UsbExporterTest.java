package com.limelight.binding.usb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.hardware.usb.UsbDevice;

import com.limelight.RepoFiles;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

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

        Runnable onUnbind;

        @Override
        public void unbind(long handle, String busId) {
            calls.add("unbind " + handle + " " + busId);
            if (onUnbind != null) {
                onUnbind.run();
            }
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
    // for a lock the calling thread holds, which in these tests is the exporter's. No clock: it
    // watches the thread until it has finished or is blocked by the caller.
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
            if (t.getState() == Thread.State.TERMINATED) {
                try {
                    t.join();
                }
                catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return result[0];
            }
            if (blockedBy(Thread.currentThread(), t)) {
                return BLOCKED;
            }
            Thread.yield();
        }
    }

    // Whether thread is blocked on a lock that owner holds. A BLOCKED state alone doesn't say
    // whose lock it is: a thread whose task has returned is BLOCKED for a moment as it ends, while
    // it takes its own Thread object's monitor, which Thread.start() holds as the thread starts.
    // A short task can get there before its starter has left start(). ThreadMXBean names the
    // lock's owner, and leaves out a thread that is ending. While the caller watches here it
    // can't let go of its own locks, so once this is true of the caller it stays true. These
    // tests compile against android.jar, which has no java.lang.management, so it's reached by
    // reflection: they run on a full JDK.
    private static boolean blockedBy(Thread owner, Thread thread) {
        try {
            Object threads = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null);
            Object info = Class.forName("java.lang.management.ThreadMXBean")
                    .getMethod("getThreadInfo", long.class).invoke(threads, thread.threadId());
            if (info == null) {
                return false;
            }
            Class<?> infoClass = Class.forName("java.lang.management.ThreadInfo");
            return infoClass.getMethod("getThreadState").invoke(info) == Thread.State.BLOCKED
                    && (long) infoClass.getMethod("getLockOwnerId").invoke(info) == owner.threadId();
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            throw new AssertionError(e);
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
    // Where the APK has no exporter library for this ABI (x86_64 in a release), loading it is an
    // UnsatisfiedLinkError. It comes back as an IOException, so start() gives the device back and
    // stops listening for its unplug instead of crashing. The JVM has no such library either.
    @Test
    public void aMissingExporterLibraryIsAnIOExceptionAndTheDeviceGoesBack() throws Exception {
        UsbExporter.RealNatives real = new UsbExporter.RealNatives();
        try {
            real.speed(42);
            fail("loaded an exporter library");
        }
        catch (IOException expected) {
            assertTrue(String.valueOf(expected.getCause()), expected.getCause() instanceof UnsatisfiedLinkError);
        }
        real.revoke(1, 50123);

        FakeWatch heard = new FakeWatch();
        UsbExporter withoutLibrary = new UsbExporter(real, Socket::new, Runnable::run, gone::add, heard);
        FakeDevice pad = device("pad");
        try {
            withoutLibrary.start(pad, false);
            fail("exported");
        }
        catch (IOException expected) {
        }
        assertTrue(pad.closed);
        assertNull(withoutLibrary.current());
        assertFalse("still listening for its unplug", heard.on);
    }

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

    // current() may be called from the main thread, so it never waits for the lock that stop()
    // holds while the native exporter lets the device go, which can take seconds
    @Test
    public void currentDoesntWaitForAStopUnderWay() throws Exception {
        exporter.start(device("pad"), false);
        List<Thread> started = new ArrayList<>();
        Object[] during = new Object[] {"not asked"};
        natives.onUnbind = () -> during[0] = onAnotherThread(started, exporter::current);
        exporter.stop();
        for (Thread t : started) {
            t.join();
        }
        assertNull("current() waited for the stop, or still gave the device", during[0]);
    }

    // The two tests above see a wait through onAnotherThread, so its rule has tests of its own.
    // A thread whose task has returned takes its own Thread object's monitor as it ends, and is
    // BLOCKED while another thread holds it. That isn't waiting for the caller.
    // currentDoesntWaitForAStopUnderWay failed that way now and then (once on CI, 27 Oct 2026):
    // current() returned at once, and its thread got there before its starter had let go of that
    // monitor. Here a third thread holds it, so the thread stays BLOCKED until the test lets go
    @Test
    public void aThreadThatIsEndingIsntWaitingForTheCaller() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean();
        Thread ending = new Thread(() -> {
            await(go);
            returned.set(true);
        });
        ending.start();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (ending) {
                held.countDown();
                await(letGo);
            }
        });
        holder.start();
        held.await();
        go.countDown();
        Thread.State state;
        while (true) {
            boolean taskReturned = returned.get();
            state = ending.getState();
            if (taskReturned && (state == Thread.State.BLOCKED || state == Thread.State.TERMINATED)) {
                break;
            }
            Thread.yield();
        }
        boolean counted = blockedBy(Thread.currentThread(), ending);
        letGo.countDown();
        holder.join();
        ending.join();
        assumeTrue("this JVM ends a thread without taking its Thread object's monitor, so the moment "
                + "never comes", state == Thread.State.BLOCKED);
        assertFalse("a thread that had finished its task counted as waiting for the caller", counted);
    }

    // A lock another thread holds doesn't count either
    @Test
    public void anotherThreadsLockIsntTheCallers() throws Exception {
        Object lock = new Object();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                held.countDown();
                await(letGo);
            }
        });
        holder.start();
        held.await();
        AtomicBoolean entered = new AtomicBoolean();
        Thread waiting = new Thread(() -> {
            synchronized (lock) {
                entered.set(true);
            }
        });
        waiting.start();
        while (!blockedBy(holder, waiting)) {
            Thread.yield();
        }
        boolean counted = blockedBy(Thread.currentThread(), waiting);
        letGo.countDown();
        holder.join();
        waiting.join();
        assertTrue(entered.get());
        assertFalse("a lock another thread holds counted as the caller's", counted);
    }

    // A lock the caller holds does count
    @Test
    public void theCallersLockCounts() throws Exception {
        Object lock = new Object();
        List<Thread> started = new ArrayList<>();
        Object during;
        synchronized (lock) {
            during = onAnotherThread(started, () -> {
                synchronized (lock) {
                    return "entered";
                }
            });
        }
        for (Thread t : started) {
            t.join();
        }
        assertSame(BLOCKED, during);
    }

    // An Export from an earlier share never reaches the exporter of the device shared now
    @Test
    public void anOldExportCantConnectToTheNextDevice() throws Exception {
        UsbExporter.Export old = exporter.start(device("pad"), false);
        exporter.stop();
        UsbExporter.Export now = exporter.start(device("pad"), false);
        natives.calls.clear();
        try {
            exporter.connect(old, 1000);
            fail("connected with an old export");
        }
        catch (IllegalStateException expected) {
        }
        assertTrue(natives.calls.toString(), natives.calls.isEmpty());
        assertTrue(sockets.isEmpty());
        assertNotNull(exporter.connect(now, 1000));
    }

    // ---------------------------------------------------------------- start(UsbDevice), the app's call
    // An exporter whose Android checks and device opening are the opener's
    private UsbExporter withOpener(UsbExporter.Opener opener) {
        return new UsbExporter(natives, () -> {
            FakeSocket s = new FakeSocket(natives.calls, false);
            sockets.add(s);
            return s;
        }, Runnable::run, gone::add, watch, opener);
    }

    // Android's checks come first, the device is opened only while nothing is shared, and your
    // yes reaches the device rules unchanged
    @Test
    public void theAppsStartChecksThenOpensAndPassesYourYesOn() throws Exception {
        List<String> steps = new ArrayList<>();
        List<FakeDevice> opened = new ArrayList<>();
        UsbExporter app = withOpener(new UsbExporter.Opener() {
            @Override
            public void check(UsbDevice device) {
                steps.add("check");
            }

            @Override
            public UsbExporter.Device open(UsbDevice device) throws IOException {
                steps.add("open");
                FakeDevice d = new FakeDevice("/dev/bus/usb/001/00" + (opened.size() + 2), caseShare("report_keyboard"));
                opened.add(d);
                return d;
            }
        });
        // Only for the keyboard's report, which the stand-in natives read back
        device("report_keyboard");
        try {
            app.start((UsbDevice) null, false);
            fail("a keyboard was shared without your yes");
        }
        catch (UsbExporter.Refused r) {
            assertEquals("ask", r.decision.toString());
        }
        assertTrue(opened.get(0).closed);
        UsbExporter.Export export = app.start((UsbDevice) null, true);
        assertEquals(SwutCodec.FLAG_CONFIRMED_INPUT, export.share.flags);
        assertEquals(List.of("check", "open", "check", "open"), steps);

        // While it's shared, a second start is checked but opens nothing
        try {
            app.start((UsbDevice) null, true);
            fail("a second device was shared");
        }
        catch (IllegalStateException expected) {
        }
        assertEquals(List.of("check", "open", "check", "open", "check"), steps);
        assertEquals(2, opened.size());
        assertSame(export, app.current());
    }

    // A failed check opens nothing. On a computer, as on a phone that can't share, the app's own
    // check fails.
    @Test
    public void theAppsStartOpensNothingWhenTheCheckFails() throws Exception {
        List<String> steps = new ArrayList<>();
        UsbExporter app = withOpener(new UsbExporter.Opener() {
            @Override
            public void check(UsbDevice device) {
                steps.add("check");
                throw new SecurityException("No USB permission for this device");
            }

            @Override
            public UsbExporter.Device open(UsbDevice device) {
                steps.add("open");
                throw new AssertionError("opened");
            }
        });
        try {
            app.start((UsbDevice) null, true);
            fail("started");
        }
        catch (SecurityException expected) {
        }
        assertEquals(List.of("check"), steps);
        try {
            exporter.start((UsbDevice) null, true);
            fail("started on a computer");
        }
        catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("64-bit ARM"));
        }
        assertNull(app.current());
        assertNull(exporter.current());
        assertTrue(natives.calls.toString(), natives.calls.isEmpty());
        assertFalse(watch.on);
    }

    // V+'s UsbIpBackend exports devices without the device rules, so nothing in the app shares
    // through it, or reaches the native exporter, except UsbExporter
    @Test
    public void onlyUsbExporterReachesTheNativeExporter() throws Exception {
        List<String> wrong = new ArrayList<>();
        java.nio.file.Path main = RepoFiles.file("app/src/main/java").toPath();
        List<java.nio.file.Path> sources;
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(main)) {
            sources = files.filter(f -> f.toString().endsWith(".java")).collect(java.util.stream.Collectors.toList());
        }
        for (java.nio.file.Path p : sources) {
            String code = new String(java.nio.file.Files.readAllBytes(p), StandardCharsets.UTF_8);
            boolean exporterItself = p.getFileName().toString().equals("UsbExporter.java");
            if (!exporterItself && (code.contains("UsbIpBackend") || code.contains("NativeUsbIp"))) {
                wrong.add(main.relativize(p).toString());
            }
            if (exporterItself && code.replace("UsbIpBackend.isSupported(", "").contains("UsbIpBackend.")) {
                wrong.add("UsbExporter uses more of UsbIpBackend than isSupported()");
            }
        }
        assertTrue(wrong.toString(), wrong.isEmpty());
    }
}
