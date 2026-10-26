package com.limelight.binding.input.dualsense;

import com.limelight.R;

/**
 * Where the DualSense's trigger effects stand, for the status line in Settings and the log. Every
 * value but WORKING and READY means Starwire is doing what it did before 0.3.0: the pad works
 * through Android as usual, with no trigger effects. Each says why in plain words.
 */
public enum DualSenseStatus {
    /** The switch is off. */
    OFF(R.string.dualsense_status_off),
    /** The checks are running (Settings). */
    CHECKING(R.string.dualsense_status_checking),
    /** Android 11 or older: the hidden call isn't there, or isn't worth the risk. */
    NEEDS_ANDROID_12(R.string.dualsense_status_needs_android_12),
    /** The Nearby devices permission isn't allowed. */
    NO_PERMISSION(R.string.dualsense_status_no_permission),
    /** Bluetooth is off, or the device has none. */
    BLUETOOTH_OFF(R.string.dualsense_status_bluetooth_off),
    /** Android's Bluetooth controller service didn't answer. */
    NO_HID_SERVICE(R.string.dualsense_status_no_hid_service),
    /** None of the four hidden ways to send a report is on this device. */
    NO_CALL_FORM(R.string.dualsense_status_no_call_form),
    /** No DualSense is connected over Bluetooth. */
    NO_PAD(R.string.dualsense_status_no_pad),
    /** More than one DualSense is connected. */
    TWO_PADS(R.string.dualsense_status_two_pads),
    /** Android refused every way of sending the report. */
    SEND_FAILED(R.string.dualsense_status_send_failed),
    /** A DualSense was found and nothing has been sent yet (Settings, before Test). */
    READY(R.string.dualsense_status_ready),
    /** Android took the last report. That doesn't prove the pad got it: only a pull shows that. */
    WORKING(R.string.dualsense_status_working);

    /** The status line's text. */
    public final int message;

    DualSenseStatus(int message) {
        this.message = message;
    }

    /** True when Starwire is on today's path, so a reason belongs in the status line. */
    public boolean isFallback() {
        return this != WORKING && this != READY && this != OFF && this != CHECKING;
    }

    /** The saved name back as a status, or null for anything unknown. */
    public static DualSenseStatus fromName(String name) {
        if (name == null) {
            return null;
        }
        for (DualSenseStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        return null;
    }
}
