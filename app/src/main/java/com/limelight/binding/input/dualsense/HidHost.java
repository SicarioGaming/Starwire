package com.limelight.binding.input.dualsense;

import java.util.List;
import java.util.Set;

/**
 * Android's Bluetooth HID host as the DualSense code needs it: the connected HID devices, and the
 * hidden calls that hand a report to one of them. BluetoothHidHostLink is the real one; the JVM
 * tests use fakes, so everything that decides what to send is tested without a device.
 */
public interface HidHost {
    /** A Bluetooth HID device Android has connected: its address and the name it gave. */
    final class Device {
        public final String address;
        public final String name;

        public Device(String address, String name) {
            this.address = address;
            this.name = name;
        }
    }

    /** The HID devices connected now. Empty, never null, if Android won't say. */
    List<Device> connectedDevices();

    /** Which of the hidden call forms this device has. */
    Set<SendForm> forms();

    /**
     * Hands one output report, report ID first, to the device with this address in one form.
     *
     * @return what Android returned: true if it took the report. That doesn't prove the pad got it.
     * @throws Exception whatever the hidden call throws
     */
    boolean call(SendForm form, String address, byte[] report) throws Exception;
}
