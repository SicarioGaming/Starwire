package com.limelight.binding.input.dualsense;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android's HID host for the JVM tests: a fixed device list, and forms that answer as told. As a
 * HidHostLink it also stands in for opening Android's HID service, so DualSenseBluetooth runs with
 * it too, from this package and from ControllerHandler's and Settings' tests.
 */
public final class FakeHidHost implements HidHostLink {
    /** One call that reached the fake. */
    public static final class Call {
        public final SendForm form;
        public final String address;
        public final byte[] report;

        Call(SendForm form, String address, byte[] report) {
            this.form = form;
            this.address = address;
            this.report = report;
        }
    }

    public enum Answer { TRUE, FALSE, THROW }

    public final List<Device> devices = new ArrayList<>();
    public final Map<SendForm, Answer> answers = new EnumMap<>(SendForm.class);
    public final List<Call> calls = new ArrayList<>();

    /** Bluetooth is on. */
    public boolean bluetoothOn = true;
    /** Android takes the request for its HID service. */
    public boolean openTaken = true;
    /** The service answers at once; if false, never. */
    public boolean serviceAnswers = true;
    public int opens;
    public int closes;

    /** A host where every form is there and takes every report. */
    public static FakeHidHost working(String address, String name) {
        FakeHidHost host = new FakeHidHost();
        host.devices.add(new Device(address, name));
        for (SendForm form : SendForm.values()) {
            host.answers.put(form, Answer.TRUE);
        }
        return host;
    }

    public FakeHidHost answer(Answer answer, SendForm... forms) {
        for (SendForm form : forms) {
            answers.put(form, answer);
        }
        return this;
    }

    /** Reports that Android took, in order. */
    public List<byte[]> taken() {
        List<byte[]> out = new ArrayList<>();
        for (Call call : calls) {
            if (answers.get(call.form) == Answer.TRUE) {
                out.add(call.report);
            }
        }
        return out;
    }

    /** The last report Android took. */
    public byte[] lastTaken() {
        List<byte[]> all = taken();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** A Bluetooth report's right trigger block (R2), 11 bytes as lower-case hex. */
    public static String rightTrigger(byte[] report) {
        return block(report, DualSenseOutput.RIGHT_TRIGGER);
    }

    /** A Bluetooth report's left trigger block (L2), 11 bytes as lower-case hex. */
    public static String leftTrigger(byte[] report) {
        return block(report, DualSenseOutput.LEFT_TRIGGER);
    }

    private static String block(byte[] report, int at) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < TriggerEffect.SIZE; i++) {
            s.append(String.format("%02x", report[DualSenseOutput.BLUETOOTH_COMMON_OFFSET + at + i] & 0xFF));
        }
        return s.toString();
    }

    /** An effect's 11 bytes as lower-case hex, to compare with rightTrigger and leftTrigger. */
    public static String hex(TriggerEffect effect) {
        StringBuilder s = new StringBuilder();
        for (byte b : effect.toBytes()) {
            s.append(String.format("%02x", b & 0xFF));
        }
        return s.toString();
    }

    @Override
    public List<Device> connectedDevices() {
        return new ArrayList<>(devices);
    }

    @Override
    public Set<SendForm> forms() {
        return answers.isEmpty() ? EnumSet.noneOf(SendForm.class) : EnumSet.copyOf(answers.keySet());
    }

    @Override
    public boolean call(SendForm form, String address, byte[] report) {
        calls.add(new Call(form, address, report.clone()));
        Answer answer = answers.get(form);
        if (answer == Answer.THROW) {
            throw new IllegalStateException("hidden call blocked");
        }
        return answer == Answer.TRUE;
    }

    @Override
    public boolean bluetoothOn() {
        return bluetoothOn;
    }

    @Override
    public boolean open(Listener listener) {
        opens++;
        if (!openTaken) {
            return false;
        }
        if (serviceAnswers) {
            listener.onLinkReady();
        }
        return true;
    }

    @Override
    public void close() {
        closes++;
    }
}
