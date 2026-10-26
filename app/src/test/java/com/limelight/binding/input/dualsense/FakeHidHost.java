package com.limelight.binding.input.dualsense;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Android's HID host for the JVM tests: a fixed device list, and forms that answer as told. */
final class FakeHidHost implements HidHost {
    /** One call that reached the fake. */
    static final class Call {
        final SendForm form;
        final String address;
        final byte[] report;

        Call(SendForm form, String address, byte[] report) {
            this.form = form;
            this.address = address;
            this.report = report;
        }
    }

    enum Answer { TRUE, FALSE, THROW }

    final List<Device> devices = new ArrayList<>();
    final Map<SendForm, Answer> answers = new EnumMap<>(SendForm.class);
    final List<Call> calls = new ArrayList<>();

    /** A host where every form is there and takes every report. */
    static FakeHidHost working(String address, String name) {
        FakeHidHost host = new FakeHidHost();
        host.devices.add(new Device(address, name));
        for (SendForm form : SendForm.values()) {
            host.answers.put(form, Answer.TRUE);
        }
        return host;
    }

    FakeHidHost answer(Answer answer, SendForm... forms) {
        for (SendForm form : forms) {
            answers.put(form, answer);
        }
        return this;
    }

    /** Reports that Android took, in order. */
    List<byte[]> taken() {
        List<byte[]> out = new ArrayList<>();
        for (Call call : calls) {
            if (answers.get(call.form) == Answer.TRUE) {
                out.add(call.report);
            }
        }
        return out;
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
}
