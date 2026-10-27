package com.limelight.binding.usb.share;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import org.robolectric.shadow.api.Shadow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Fakes for the share flow's parts: the phone's USB devices, the device rules, the exporter and
 * the tunnel, each driven by the test one step at a time. No device, network or clock.
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

    static final class Rules implements UsbDeviceRules {
        UsbVerdict verdict = UsbVerdict.ALLOW;
        IOException failure;
        int checks;

        @Override
        public UsbVerdict check(UsbDevice device) throws IOException {
            checks++;
            if (failure != null) {
                throw failure;
            }
            return verdict;
        }
    }

    static final class Exporter implements UsbExporter {
        int starts;
        int stops;
        boolean running;
        IOException failure;
        RuntimeException stopFailure;
        Listener listener;

        @Override
        public Export start(UsbDevice device, Listener listener) throws IOException {
            starts++;
            if (failure != null) {
                throw failure;
            }
            this.listener = listener;
            running = true;
            return () -> "1-1";
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

    static final class Tunnel implements UsbTunnel {
        Target target;
        boolean confirmed;
        Listener listener;
        final List<Integer> closes = new ArrayList<>();
        UsbExporter.Export export;

        @Override
        public void connect(Target target, boolean confirmedInput, Listener listener) {
            this.target = target;
            this.confirmed = confirmedInput;
            this.listener = listener;
        }

        @Override
        public void close(int endReason) {
            closes.add(endReason);
        }

        /** A BIND_ACK that checks out: the share flow starts the exporter */
        void bindAck() throws IOException {
            export = listener.onBound();
        }

        /** SHARE_ACK with status 0 */
        void shareAck() {
            listener.onSharing();
        }

        void closed(int kind, int code, int detail) {
            listener.onClosed(new Closed(kind, code, detail));
        }
    }

    static final class Backends implements UsbShareBackends.Factory {
        boolean supported = true;
        final Rules rules = new Rules();
        final List<Exporter> exporters = new ArrayList<>();
        final List<Tunnel> tunnels = new ArrayList<>();

        @Override
        public boolean isSupported() {
            return supported;
        }

        @Override
        public UsbDeviceRules rules(Context context) {
            return rules;
        }

        @Override
        public UsbExporter exporter(Context context) {
            Exporter e = new Exporter();
            exporters.add(e);
            return e;
        }

        @Override
        public UsbTunnel tunnel(Context context) {
            Tunnel t = new Tunnel();
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
        String confirming;
        UsbShareController.Answer answer;

        @Override
        public void onChanged() {
            changes++;
        }

        @Override
        public void confirmInput(String name, UsbShareController.Answer answer) {
            confirming = name;
            this.answer = answer;
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
