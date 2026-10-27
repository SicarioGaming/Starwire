package com.limelight.binding.usb;

import android.hardware.usb.UsbDevice;

import com.limelight.binding.usb.tunnel.SwutCodec;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real UsbExporter for tests outside this package, on loopback. The native exporter is stood in
 * for by a USB/IP server on 127.0.0.1 that, like usbipdcpp with V+'s change, lets in only the one
 * connection whose source port was authorised. Android's device is stood in for by one made from a
 * SHARE frame's descriptors, plugged in by Android's name for it. Its unplug watch is the test's
 * to fire (unplug()). Every native call and device close is written down, in order.
 */
public final class ExporterOnLoopback implements Closeable {
    // Only so a broken test fails instead of hanging; no test waits for it to pass
    private static final int SAFETY_MS = 10_000;

    private final ServerSocket server;
    private final Map<String, SwutCodec.Share> plugged = new ConcurrentHashMap<>();
    private final Map<Integer, SwutCodec.Share> open = new ConcurrentHashMap<>();
    private final AtomicInteger nextFd = new AtomicInteger(40);
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile int authorized = -1;
    private volatile UsbExporter exporter;

    public ExporterOnLoopback() throws IOException {
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(SAFETY_MS);
    }

    /** A device, by Android's name for it, whose descriptors are this SHARE frame's */
    public void plug(String deviceName, byte[] shareFrame) {
        SwutCodec.Share share = SwutCodec.parseShare(shareFrame);
        if (share == null) {
            throw new IllegalArgumentException("not a SHARE frame");
        }
        plugged.put(deviceName, share);
    }

    /** The real exporter over these stand-ins. unplug() goes to the last one made. */
    public UsbExporter create(UsbExporter.Listener listener) {
        UsbExporter.UnplugWatch watch = new UsbExporter.UnplugWatch() {
            @Override
            public void start() {
                calls.add("watch on");
            }

            @Override
            public void stop() {
                calls.add("watch off");
            }
        };
        // Android's unplug broadcast comes on the test's thread, through unplug()
        UsbExporter x = new UsbExporter(new Natives(), Socket::new, Runnable::run, listener, watch);
        exporter = x;
        return x;
    }

    /**
     * UsbExporter.start(device, inputConfirmed), with the device opened here, as if Android had
     * given Starwire permission, instead of through UsbManager
     */
    public UsbExporter.Export start(UsbExporter x, UsbDevice device, boolean inputConfirmed)
            throws UsbExporter.Refused, IOException {
        SwutCodec.Share share = plugged.get(device.getDeviceName());
        if (share == null) {
            throw new IOException("Android couldn't open the USB device");
        }
        return x.start(new Device(device.getDeviceName(), device.getProductName(), share), inputConfirmed);
    }

    /** Android says the device was unplugged, to the exporter's own unplug watch */
    public void unplug(String deviceName) {
        exporter.deviceDetached(deviceName);
    }

    /** The exporter's one connection, which must come from the port it authorised */
    public Socket acceptAuthorised() throws IOException {
        Socket s = server.accept();
        s.setSoTimeout(SAFETY_MS);
        int allowed = authorized;
        // The authorisation is used once
        authorized = -1;
        if (s.getPort() != allowed) {
            s.close();
            throw new AssertionError("a connection from port " + s.getPort() + ", but " + allowed + " was authorised");
        }
        return s;
    }

    /** Every native call and device close so far, in order */
    public List<String> calls() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private final class Natives implements UsbExporter.Natives {
        @Override
        public long start() {
            calls.add("start");
            return 1;
        }

        @Override
        public int localPort(long handle) {
            calls.add("localPort");
            return server.getLocalPort();
        }

        @Override
        public void authorize(long handle, int sourcePort) {
            calls.add("authorize");
            authorized = sourcePort;
        }

        @Override
        public void revoke(long handle, int sourcePort) {
            calls.add("revoke");
            if (authorized == sourcePort) {
                authorized = -1;
            }
        }

        @Override
        public String bind(long handle, int fd) {
            calls.add("bind");
            return open.get(fd).busId;
        }

        @Override
        public void unbind(long handle, String busId) {
            calls.add("unbind " + busId);
        }

        @Override
        public void stop(long handle) {
            calls.add("stop");
        }

        @Override
        public int speed(int fd) {
            calls.add("speed");
            // Full speed, USB 1.1
            return 2;
        }

        @Override
        public byte[] reportDescriptor(int fd, int iface, int length) throws IOException {
            calls.add("report " + iface);
            SwutCodec.Share share = open.get(fd);
            for (int i = 0; i < share.reportInterfaces.length; i++) {
                if (share.reportInterfaces[i] == iface) {
                    return share.reports[i].clone();
                }
            }
            throw new IOException("no report descriptor for interface " + iface);
        }
    }

    private final class Device implements UsbExporter.Device {
        private final String name;
        private final String productName;
        private final SwutCodec.Share share;
        private final int fd = nextFd.getAndIncrement();

        Device(String name, String productName, SwutCodec.Share share) {
            this.name = name;
            this.productName = productName;
            this.share = share;
            open.put(fd, share);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String productName() {
            return productName;
        }

        @Override
        public byte[] rawDescriptors() {
            byte[] raw = new byte[share.deviceDescriptor.length + share.configuration.length];
            System.arraycopy(share.deviceDescriptor, 0, raw, 0, share.deviceDescriptor.length);
            System.arraycopy(share.configuration, 0, raw, share.deviceDescriptor.length, share.configuration.length);
            return raw;
        }

        @Override
        public int fd() {
            return fd;
        }

        @Override
        public void close() {
            calls.add("close " + name);
        }
    }
}
