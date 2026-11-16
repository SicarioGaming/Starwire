package com.limelight.binding.usb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.binding.audio.securemic.SwmcCodec;
import com.limelight.binding.usb.tunnel.SwutCodec;
import com.limelight.usbip.NativeUsbIp;
import com.limelight.usbip.UsbIpBackend;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Shares one wired USB device through the USB/IP exporter inside the app (usbipdcpp, from Moonlight
 * V+'s usbip-backend). The device rules (UsbDeviceRules) decide before anything is exported, from
 * the descriptors of the same open connection that is then exported, and the export describes
 * exactly what they checked, for the tunnel's SHARE. One device at a time.
 *
 * The exporter listens on 127.0.0.1 only, on a port of its own for each export, and only while a
 * device is shared. Every app on the phone can reach 127.0.0.1, so it takes only the one
 * connection Starwire authorised: connect() binds its socket to a free port first, tells the
 * exporter that exact source port, then connects, and the exporter uses the authorisation once and
 * closes any other connection. This is how V+'s USB backend does it.
 *
 * The device goes back to the phone on stop() (Stop, or the stream ending) or when it's unplugged,
 * which also tells the listener. start(), connect() and stop() block, so call them off the main
 * thread.
 */
public final class UsbExporter {
    private static final String TAG = "UsbExporter";

    // libusb's speeds, which USB/IP uses too: low, full and high speed (USB 1 and 2) only
    static final int SPEED_LOW = 1;
    static final int SPEED_HIGH = 3;

    /** The shared device went back to the phone without stop(): it was unplugged. */
    public interface Listener {
        /** Called once, on a background thread, after the device was released. */
        void onDeviceGone(Export export);
    }

    /** One shared device. */
    public static final class Export {
        /** Android's name for the device (its path), as UsbDevice.getDeviceName() gives it */
        public final String deviceName;
        /** The exporter's port on 127.0.0.1. Reach it with connect(), never directly. */
        public final int port;
        /** The bus id the exporter gives the device, which SHARE and USB/IP's import name */
        public final String busId;
        /** low, full or high speed, as USB/IP numbers them (1 to 3) */
        public final int speed;
        /** What SHARE says: the descriptors the rules checked, the name and the flags */
        public final SwutCodec.Share share;
        final long handle;

        Export(String deviceName, int port, String busId, int speed, SwutCodec.Share share, long handle) {
            this.deviceName = deviceName;
            this.port = port;
            this.busId = busId;
            this.speed = speed;
            this.share = share;
            this.handle = handle;
        }

        /** The SHARE frame for the tunnel. */
        public byte[] shareFrame() {
            return SwutCodec.share(share.flags, share.busId, share.nameText(), share.deviceDescriptor,
                    share.configuration, share.reportInterfaces, share.reports);
        }
    }

    /** Not shared: the device rules refused it, or it asks for your yes and didn't have it. */
    public static final class Refused extends Exception {
        public final UsbDeviceRules.Decision decision;

        Refused(UsbDeviceRules.Decision decision) {
            super("USB device " + decision);
            this.decision = decision;
        }
    }

    // ---------------------------------------------------------------- what it needs from Android
    /** One USB device opened through Android, with permission. */
    interface Device {
        String name();

        /** The product name Android reports, or null */
        String productName();

        /** The device descriptor, then each configuration descriptor, as the kernel read them */
        byte[] rawDescriptors();

        int fd();

        void close();
    }

    /** The exporter's native calls (NativeUsbIp), so the tests can stand in for them. */
    interface Natives {
        long start() throws IOException;

        int localPort(long handle) throws IOException;

        void authorize(long handle, int sourcePort) throws IOException;

        void revoke(long handle, int sourcePort);

        String bind(long handle, int fd) throws IOException;

        void unbind(long handle, String busId) throws IOException;

        void stop(long handle) throws IOException;

        int speed(int fd) throws IOException;

        byte[] reportDescriptor(int fd, int iface, int length) throws IOException;
    }

    interface Sockets {
        Socket create();
    }

    /** Android's unplug broadcast, heard while a device is shared, so the tests can stand in for it. */
    interface UnplugWatch {
        void start();

        void stop();
    }

    /**
     * Android's part of start(UsbDevice): whether this phone may share the device, then the
     * device opened. So the tests can stand in for it and still start through start(UsbDevice).
     */
    interface Opener {
        /** Throws unless this phone can share USB devices and Starwire has this one's permission */
        void check(UsbDevice device) throws IOException;

        /** The device, opened. Called under the exporter's lock, with nothing shared. */
        Device open(UsbDevice device) throws IOException;
    }

    private static final class Active {
        final Export export;
        final Device device;
        Socket socket;

        Active(Export export, Device device) {
            this.export = export;
            this.device = device;
        }
    }

    private final Natives natives;
    private final Sockets sockets;
    private final Executor background;
    private final Listener listener;
    private final Opener opener;
    private final UnplugWatch unplugWatch;
    // active and watching change only under this object's lock. The watch starts and stops with
    // the export it belongs to, so a stop() or a failed start() never ends another export's.
    private Active active;
    private boolean watching;
    // What current() returns, readable without the lock: start(), connect() and stop() hold the
    // lock while the native exporter works, which can take seconds, and current() may be called
    // from the main thread
    private volatile Export current;

    public UsbExporter(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        this.opener = new AndroidOpener((UsbManager) app.getSystemService(Context.USB_SERVICE));
        this.natives = new RealNatives();
        this.sockets = Socket::new;
        this.background = Executors.newSingleThreadExecutor(r -> new Thread(r, "UsbExporter"));
        this.listener = listener;
        BroadcastReceiver detachReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    deviceDetached(device.getDeviceName());
                }
            }
        };
        this.unplugWatch = new UnplugWatch() {
            @Override
            public void start() {
                IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // A system broadcast, which reaches an unexported receiver too (as in UsbDriverService)
                    app.registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                }
                else {
                    app.registerReceiver(detachReceiver, filter);
                }
            }

            @Override
            public void stop() {
                try {
                    app.unregisterReceiver(detachReceiver);
                }
                catch (IllegalArgumentException ignored) {
                }
            }
        };
    }

    // For the JVM tests: no Android, and the background work runs where the test says
    UsbExporter(Natives natives, Sockets sockets, Executor background, Listener listener, UnplugWatch unplugWatch,
            Opener opener) {
        this.opener = opener;
        this.natives = natives;
        this.sockets = sockets;
        this.background = background;
        this.listener = listener;
        this.unplugWatch = unplugWatch;
    }

    // No UsbManager, so start(UsbDevice) refuses, as on a phone that can't share
    UsbExporter(Natives natives, Sockets sockets, Executor background, Listener listener, UnplugWatch unplugWatch) {
        this(natives, sockets, background, listener, unplugWatch, new AndroidOpener(null));
    }

    UsbExporter(Natives natives, Sockets sockets, Executor background, Listener listener) {
        this(natives, sockets, background, listener, new UnplugWatch() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }
        });
    }

    /**
     * Android 9 or later, in a 64-bit ARM process: the only builds with the exporter's library.
     * Not on an x86_64 emulator or Chromebook, even one that runs ARM apps.
     */
    public static boolean isSupported() {
        return UsbIpBackend.isSupported();
    }

    /** start(device, false): a keyboard or mouse is refused until you say yes. */
    public Export start(UsbDevice device) throws Refused, IOException {
        return start(device, false);
    }

    /**
     * Checks the device against the device rules and, if they allow it, exports it. A device the
     * rules ask about needs inputConfirmed, your yes just now; without it, Refused says "ask".
     * Needs the USB permission first (UsbPermission).
     */
    public Export start(UsbDevice device, boolean inputConfirmed) throws Refused, IOException {
        opener.check(device);
        // The lock for the whole start, so a second one can't open the device meanwhile
        synchronized (this) {
            if (active != null) {
                throw new IllegalStateException("A USB device is already shared");
            }
            return start(opener.open(device), inputConfirmed);
        }
    }

    synchronized Export start(Device device, boolean inputConfirmed) throws Refused, IOException {
        if (active != null) {
            device.close();
            throw new IllegalStateException("A USB device is already shared");
        }
        boolean exported = false;
        try {
            byte[][] parts = split(device.rawDescriptors());
            if (parts == null) {
                throw new Refused(UsbDeviceRules.refusal(SwutCodec.RULE_UNREADABLE));
            }
            byte[] deviceDescriptor = parts[0], configuration = parts[1];
            // The checks that need no interface taken from Android come first
            UsbDeviceRules.Plan plan = UsbDeviceRules.plan(deviceDescriptor, configuration);
            if (plan.refusal != null) {
                throw new Refused(plan.refusal);
            }
            int speed = natives.speed(device.fd());
            if (speed < SPEED_LOW || speed > SPEED_HIGH) {
                throw new Refused(UsbDeviceRules.refusal(SwutCodec.RULE_TOO_FAST));
            }
            byte[][] reports = new byte[plan.interfaces.length][];
            for (int i = 0; i < reports.length; i++) {
                reports[i] = natives.reportDescriptor(device.fd(), plan.interfaces[i], plan.lengths[i]);
            }
            UsbDeviceRules.Decision decision = UsbDeviceRules.decide(deviceDescriptor, configuration,
                    plan.interfaces, reports);
            boolean asks = decision.kind == UsbDeviceRules.Decision.ASK;
            if (decision.refused() || (asks && !inputConfirmed)) {
                throw new Refused(decision);
            }
            int flags = asks ? SwutCodec.FLAG_CONFIRMED_INPUT : 0;
            byte[] name = SwmcCodec.nameBytes(device.productName() == null ? "" : device.productName());

            // Watching before the export, so an unplug straight after it isn't missed
            watch(true);
            long handle = natives.start();
            String busId = null;
            try {
                int port = natives.localPort(handle);
                busId = natives.bind(handle, device.fd());
                SwutCodec.Share share = new SwutCodec.Share(flags, busId, name, deviceDescriptor, configuration,
                        plan.interfaces, reports);
                Export export = new Export(device.name(), port, busId, speed, share, handle);
                // A bus id or descriptors SHARE can't carry never get as far as the tunnel
                export.shareFrame();
                active = new Active(export, device);
                current = export;
                exported = true;
                return export;
            }
            catch (IllegalArgumentException e) {
                throw new IOException("The exporter's bus id or the descriptors don't fit SHARE", e);
            }
            finally {
                if (!exported) {
                    teardown(handle, busId);
                }
            }
        }
        finally {
            if (!exported) {
                device.close();
                watch(false);
            }
        }
    }

    /**
     * The one connection to the exporter, for the tunnel: a socket bound to 127.0.0.1 first, its
     * source port authorised, then connected. A second call for the same export fails, and so does
     * a call after the device went back.
     */
    public synchronized Socket connect(Export export, int timeoutMillis) throws IOException {
        if (active == null || active.export != export) {
            throw new IllegalStateException("That USB device isn't shared any more");
        }
        if (active.socket != null) {
            throw new IllegalStateException("The exporter takes one connection");
        }
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        Socket socket = sockets.create();
        int source = 0;
        try {
            // No other socket may share the port while it's authorised: with SO_REUSEADDR on
            // both, another app's socket could bind it too and connect first
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(loopback, 0));
            source = socket.getLocalPort();
            natives.authorize(export.handle, source);
            socket.connect(new InetSocketAddress(loopback, export.port), timeoutMillis);
            active.socket = socket;
            return socket;
        }
        catch (IOException | RuntimeException e) {
            if (source > 0) {
                natives.revoke(export.handle, source);
            }
            closeQuietly(socket);
            throw e;
        }
    }

    /** Gives the shared device back to the phone: Stop, or the stream ending. */
    public synchronized void stop() {
        release(null);
    }

    /** The shared device, or null. It never waits for a start() or stop() under way. */
    public Export current() {
        return current;
    }

    // Android said this device was unplugged: release it off the main thread, then say so
    void deviceDetached(String deviceName) {
        background.execute(() -> {
            Active released;
            synchronized (this) {
                released = release(deviceName);
            }
            if (released != null && listener != null) {
                listener.onDeviceGone(released.export);
            }
        });
    }

    // Starts or stops the unplug watch. Called with the lock held, as active changes.
    private void watch(boolean on) {
        if (watching == on) {
            return;
        }
        if (on) {
            unplugWatch.start();
        }
        else {
            unplugWatch.stop();
        }
        watching = on;
    }

    // The tunnel's end of the connection goes first, so the exporter's session drains by itself,
    // then the device leaves the exporter, the exporter stops, and Android gets the device back.
    // This is V+'s order (UsbIpBackend). Called with the lock held.
    private Active release(String onlyDeviceName) {
        Active a = active;
        if (a == null || (onlyDeviceName != null && !onlyDeviceName.equals(a.export.deviceName))) {
            return null;
        }
        active = null;
        current = null;
        try {
            closeQuietly(a.socket);
            teardown(a.export.handle, a.export.busId);
            a.device.close();
        }
        finally {
            watch(false);
        }
        return a;
    }

    private void teardown(long handle, String busId) {
        if (busId != null) {
            try {
                natives.unbind(handle, busId);
            }
            catch (IOException e) {
                // Stopping the exporter below closes what the unbind couldn't
                LimeLog.warning(TAG + ": the USB device didn't unbind: " + e.getMessage());
            }
        }
        try {
            natives.stop(handle);
        }
        catch (IOException e) {
            LimeLog.warning(TAG + ": the USB exporter didn't stop: " + e.getMessage());
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            }
            catch (IOException ignored) {
            }
        }
    }

    /**
     * Android's raw descriptors split for SHARE: the device descriptor and the one configuration
     * descriptor, or null when they don't read. With more than one configuration the rules refuse
     * the device by its device descriptor (rule 6), so only the first is kept.
     */
    static byte[][] split(byte[] raw) {
        if (raw == null || raw.length < SwutCodec.DEVICE_DESCRIPTOR + SwutCodec.MIN_CONFIGURATION) {
            return null;
        }
        int at = SwutCodec.DEVICE_DESCRIPTOR;
        int total = (raw[at + 2] & 0xFF) | (raw[at + 3] & 0xFF) << 8;
        if (total < SwutCodec.MIN_CONFIGURATION || at + total > raw.length) {
            return null;
        }
        boolean oneConfiguration = (raw[17] & 0xFF) == 1;
        if (oneConfiguration && at + total != raw.length) {
            return null;
        }
        return new byte[][] {Arrays.copyOfRange(raw, 0, at), Arrays.copyOfRange(raw, at, at + total)};
    }

    private static final class AndroidOpener implements Opener {
        // null in the JVM tests
        private final UsbManager usbManager;

        AndroidOpener(UsbManager usbManager) {
            this.usbManager = usbManager;
        }

        @Override
        public void check(UsbDevice device) throws IOException {
            if (!isSupported()) {
                throw new IOException("USB sharing needs Android 9 or later on 64-bit ARM");
            }
            if (usbManager == null || !usbManager.hasPermission(device)) {
                throw new SecurityException("No USB permission for this device");
            }
        }

        @Override
        public Device open(UsbDevice device) throws IOException {
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                throw new IOException("Android couldn't open the USB device");
            }
            return new AndroidDevice(device, connection);
        }
    }

    private static final class AndroidDevice implements Device {
        private final UsbDevice device;
        private final UsbDeviceConnection connection;

        AndroidDevice(UsbDevice device, UsbDeviceConnection connection) {
            this.device = device;
            this.connection = connection;
        }

        @Override
        public String name() {
            return device.getDeviceName();
        }

        @Override
        public String productName() {
            return device.getProductName();
        }

        @Override
        public byte[] rawDescriptors() {
            return connection.getRawDescriptors();
        }

        @Override
        public int fd() {
            return connection.getFileDescriptor();
        }

        @Override
        public void close() {
            connection.close();
        }
    }

    // The native layer reports failures as IOExceptions it doesn't declare. A build without the
    // exporter's library for this ABI throws UnsatisfiedLinkError, an Error, which comes back as
    // an IOException too, so start() still gives the device back.
    static final class RealNatives implements Natives {
        private static IOException wrap(Throwable e) {
            return e instanceof IOException ? (IOException) e : new IOException(e);
        }

        @Override
        public long start() throws IOException {
            try {
                // Loaded only here, after isSupported(): other ABIs have no exporter library
                NativeUsbIp.load();
                return NativeUsbIp.start();
            }
            catch (Exception | LinkageError e) {
                throw wrap(e);
            }
        }

        @Override
        public int localPort(long handle) throws IOException {
            try {
                return NativeUsbIp.localPort(handle);
            }
            catch (Exception | LinkageError e) {
                throw wrap(e);
            }
        }

        @Override
        public void authorize(long handle, int sourcePort) throws IOException {
            try {
                NativeUsbIp.authorizeLocalConnection(handle, sourcePort);
            }
            catch (Exception | LinkageError e) {
                throw wrap(e);
            }
        }

        @Override
        public void revoke(long handle, int sourcePort) {
            try {
                NativeUsbIp.revokeLocalConnection(handle, sourcePort);
            }
            catch (LinkageError e) {
                // Without the library nothing was authorised
            }
        }

        @Override
        public String bind(long handle, int fd) throws IOException {
            try {
                return NativeUsbIp.bind(handle, fd);
            }
            catch (Exception | LinkageError e) {
                throw wrap(e);
            }
        }

        @Override
        public void unbind(long handle, String busId) throws IOException {
            try {
                NativeUsbIp.unbind(handle, busId);
            }
            catch (Exception | LinkageError e) {
                throw wrap(e);
            }
        }

        @Override
        public void stop(long handle) throws IOException {
            try {
                NativeUsbIp.stop(handle);
            }
            catch (Exception | LinkageError e) {
                throw wrap(e);
            }
        }

        @Override
        public int speed(int fd) throws IOException {
            try {
                NativeUsbIp.load();
                return NativeUsbIp.speed(fd);
            }
            catch (Exception | LinkageError e) {
                throw wrap(e);
            }
        }

        @Override
        public byte[] reportDescriptor(int fd, int iface, int length) throws IOException {
            try {
                return NativeUsbIp.reportDescriptor(fd, iface, length);
            }
            catch (Exception | LinkageError e) {
                throw wrap(e);
            }
        }
    }
}
