package com.limelight.binding.input.dualsense;

/**
 * A HidHost that has to be opened first and closed after: Android's Bluetooth HID service as
 * DualSenseBluetooth uses it. BluetoothHidHostLink is the real one; the JVM tests use a fake, so
 * DualSenseBluetooth's checks, threads and fallbacks run without a device.
 */
public interface HidHostLink extends HidHost {
    /** Hears once that the service answered. No answer means no call: callers time out. */
    interface Listener {
        void onLinkReady();
    }

    /** The device has Bluetooth and it's on. */
    boolean bluetoothOn();

    /** Asks for the service. False if Android said no at once; the listener isn't called then. */
    boolean open(Listener listener);

    /** Lets go of the service. Safe to call more than once. */
    void close();
}
