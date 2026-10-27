package com.limelight.binding.usb.share;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.limelight.R;

import org.robolectric.shadow.api.Shadow;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Fakes for the share flow's parts: the phone's USB devices, the exporter with its device rules,
 * and the tunnel, each driven by the test one step at a time. No device, network or clock.
 */
final class FakeUsb {
    private FakeUsb() {
    }

    // Endpoint attributes
    static final int ISOCHRONOUS = 1;
    static final int BULK = 2;
    static final int INTERRUPT = 3;

    static int[] ep(int address, int attributes) {
        return new int[] {address, attributes};
    }

    static UsbDeviceSummary.Interface iface(int number, int alternate, int cls, int subclass, int protocol,
                                            int[]... endpoints) {
        return new UsbDeviceSummary.Interface(number, alternate, cls, subclass, protocol, Arrays.asList(endpoints));
    }

    static UsbDeviceSummary device(String key, String name, int cls, int subclass, int protocol, int configurations,
                                   UsbDeviceSummary.Interface... interfaces) {
        return new UsbDeviceSummary(key, name, 0x1209, 0x0001, cls, subclass, protocol, configurations,
                Arrays.asList(interfaces));
    }

    /** One HID interface that isn't a boot device, with an interrupt endpoint each way */
    static UsbDeviceSummary pad(String key) {
        return device(key, "Test pad", 0, 0, 0, 1, iface(0, 0, 0x03, 0, 0, ep(0x81, INTERRUPT), ep(0x02, INTERRUPT)));
    }

    static UsbDeviceSummary keyboard(String key) {
        return device(key, "Test keyboard", 0, 0, 0, 1, iface(0, 0, 0x03, 1, 1, ep(0x81, INTERRUPT)));
    }

    static UsbDeviceSummary storage(String key) {
        return device(key, "Test stick", 0, 0, 0, 1, iface(0, 0, 0x08, 6, 0x50, ep(0x81, BULK), ep(0x02, BULK)));
    }

    /** Audio control, then streaming with no endpoint and with one */
    static UsbDeviceSummary headset(String key) {
        return device(key, "Test headset", 0, 0, 0, 1,
                iface(0, 0, 0x01, 1, 0),
                iface(1, 0, 0x01, 2, 0),
                iface(1, 1, 0x01, 2, 0, ep(0x01, ISOCHRONOUS)));
    }

    /** Runs what it's given only when the test says so, in order */
    static final class Queue implements Executor {
        private final List<Runnable> jobs = new ArrayList<>();

        @Override
        public void execute(Runnable job) {
            jobs.add(job);
        }

        int size() {
            return jobs.size();
        }

        /** Runs every job, and any they queue */
        void runAll() {
            while (!jobs.isEmpty()) {
                jobs.remove(0).run();
            }
        }
    }

    static final class Devices implements UsbDevices {
        final List<UsbDeviceSummary> plugged = new ArrayList<>();
        private final Map<String, UsbDevice> handles = new HashMap<>();
        Listener listener;
        boolean stopped;
        final List<String> asked = new ArrayList<>();
        PermissionCallback pending;
        // Answered at once with this, unless it's -1: then the test answers with answer()
        int autoAnswer = GRANTED;

        @Override
        public void start(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() {
            stopped = true;
            listener = null;
        }

        @Override
        public List<UsbDeviceSummary> list() {
            return new ArrayList<>(plugged);
        }

        @Override
        public UsbDevice device(String key) {
            for (UsbDeviceSummary d : plugged) {
                if (d.key.equals(key)) {
                    // A real UsbDevice stands in: the share flow only passes it on
                    return handles.computeIfAbsent(key, k -> Shadow.newInstanceOf(UsbDevice.class));
                }
            }
            return null;
        }

        @Override
        public void requestPermission(String key, PermissionCallback callback) {
            asked.add(key);
            if (autoAnswer >= 0) {
                callback.onAnswer(autoAnswer);
            }
            else {
                pending = callback;
            }
        }

        void answer(int result) {
            PermissionCallback callback = pending;
            pending = null;
            callback.onAnswer(result);
        }

        void plugIn(UsbDeviceSummary d) {
            plugged.add(d);
            if (listener != null) {
                listener.onAttached(d);
            }
        }

        void unplug(String key) {
            plugged.removeIf(d -> d.key.equals(key));
            if (listener != null) {
                listener.onDetached(key);
            }
        }
    }

    /**
     * The exporter, with the device rules it applies to all of a device's descriptors: what its
     * own check says is `verdict`, as U-B's UsbExporter.start() decides it.
     */
    static final class Exporter implements UsbShareExporter {
        final Listener listener;
        UsbVerdict verdict = UsbVerdict.ALLOW;
        IOException failure;
        RuntimeException stopFailure;
        int starts;
        int connects;
        int stops;
        boolean running;
        // The inputConfirmed of the last start()
        boolean confirmed;

        Exporter(Listener listener) {
            this.listener = listener;
        }

        @Override
        public Export start(UsbDevice device, boolean inputConfirmed) throws Refused, IOException {
            starts++;
            confirmed = inputConfirmed;
            if (failure != null) {
                throw failure;
            }
            if (verdict.isRefused() || (verdict.asks() && !inputConfirmed)) {
                throw new Refused(verdict);
            }
            running = true;
            return () -> SHARE.clone();
        }

        @Override
        public Socket connect(int timeoutMs) throws IOException {
            connects++;
            if (!running) {
                throw new IOException("not exported");
            }
            // Never connected: the share flow only passes it on
            return new Socket();
        }

        @Override
        public void stop() {
            stops++;
            running = false;
            if (stopFailure != null) {
                throw stopFailure;
            }
        }
    }

    // What the fake exporter's SHARE says; the share flow only passes it on
    static final byte[] SHARE = {0, 0, 0, 5, 1};

    /**
     * The tunnel, which behaves as U-A's UsbTunnel does around the share flow: it starts the
     * exporter on BIND_ACK, and however it ends it stops the exporter (if it started it) and then
     * says so, once. close() only asks: the test ends it.
     */
    static final class Tunnel implements UsbShareTunnel {
        final UsbShareTunnel.Exporter exporter;
        final Listener listener;
        Target target;
        int connects;
        RuntimeException connectFailure;
        // Run inside connect(), as if the main thread did it while connect() ran
        Runnable duringConnect;
        final List<Integer> closes = new ArrayList<>();
        int networkLosses;
        byte[] share;
        Socket exporterConnection;
        boolean started;
        boolean ended;
        // What its endings say, from strings.xml
        int messageId = R.string.usb_share_ended;
        int ruleId;

        Tunnel(UsbShareTunnel.Exporter exporter, Listener listener) {
            this.exporter = exporter;
            this.listener = listener;
        }

        @Override
        public void connect(Target target) {
            connects++;
            if (duringConnect != null) {
                duringConnect.run();
            }
            if (connectFailure != null) {
                throw connectFailure;
            }
            this.target = target;
        }

        @Override
        public void close(int endReason) {
            closes.add(endReason);
        }

        @Override
        public void networkLost() {
            networkLosses++;
        }

        /**
         * A BIND_ACK that checks out: the tunnel starts the exporter. If that throws, the tunnel
         * sends END 2 and ends, as DEVICE_GONE, and this is false.
         */
        boolean bindAck() {
            started = true;
            try {
                share = exporter.start();
                return true;
            }
            catch (IOException e) {
                end(Ending.DEVICE_GONE, UsbShareTunnel.END_UNPLUGGED, 0);
                return false;
            }
        }

        /**
         * SHARE_ACK with status 0: the tunnel has the exporter make its connection, then says it's
         * shared. If the connection fails, it sends END 2 and ends as DEVICE_GONE, and this is false.
         */
        boolean shareAck() {
            try {
                exporterConnection = exporter.connect(5_000);
            }
            catch (IOException e) {
                end(Ending.DEVICE_GONE, UsbShareTunnel.END_UNPLUGGED, 0);
                return false;
            }
            listener.onSharing();
            return true;
        }

        /** The connection ends: the exporter is stopped if it was started, then the listener told */
        void end(int kind, int code, int rule) {
            if (ended) {
                throw new AssertionError("a tunnel ends once");
            }
            ended = true;
            if (started) {
                exporter.stop();
            }
            listener.onEnded(new Ending(kind, code, rule, messageId, ruleId));
        }

        /** The end close() asked for: END with its reason, then STOPPED */
        void endAsAsked() {
            end(Ending.STOPPED, closes.get(closes.size() - 1), 0);
        }
    }

    static final class Backends implements UsbShareBackends.Factory {
        boolean supported = true;
        // For each new exporter: what its check says, and whether it can't start at all
        UsbVerdict exporterVerdict = UsbVerdict.ALLOW;
        IOException exporterFailure;
        RuntimeException connectFailure;
        final List<Exporter> exporters = new ArrayList<>();
        final List<Tunnel> tunnels = new ArrayList<>();

        @Override
        public boolean isSupported() {
            return supported;
        }

        @Override
        public UsbShareExporter exporter(Context context, UsbShareExporter.Listener listener) {
            Exporter e = new Exporter(listener);
            e.verdict = exporterVerdict;
            e.failure = exporterFailure;
            exporters.add(e);
            return e;
        }

        @Override
        public UsbShareTunnel tunnel(Context context, UsbShareTunnel.Exporter exporter,
                                     UsbShareTunnel.Listener listener) {
            Tunnel t = new Tunnel(exporter, listener);
            t.connectFailure = connectFailure;
            tunnels.add(t);
            return t;
        }

        Exporter exporter() {
            return exporters.get(exporters.size() - 1);
        }

        Tunnel tunnel() {
            return tunnels.get(tunnels.size() - 1);
        }
    }

    static final class Ui implements UsbShareController.Ui {
        int changes;
        final List<String> messages = new ArrayList<>();
        UsbShareController.Entry confirming;
        UsbShareController.Answer answer;
        int questions;
        int cancels;

        @Override
        public void onChanged() {
            changes++;
        }

        @Override
        public void confirmInput(UsbShareController.Entry device, UsbShareController.Answer answer) {
            confirming = device;
            this.answer = answer;
            questions++;
        }

        @Override
        public void cancelConfirm() {
            cancels++;
        }

        @Override
        public void showMessage(String message) {
            messages.add(message);
        }

        String lastMessage() {
            return messages.isEmpty() ? null : messages.get(messages.size() - 1);
        }
    }
}
