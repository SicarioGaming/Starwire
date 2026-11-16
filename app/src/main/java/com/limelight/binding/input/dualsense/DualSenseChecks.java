package com.limelight.binding.input.dualsense;

/**
 * The checks before Starwire goes near Android's hidden Bluetooth calls, in order. The rest
 * (the HID service, the call forms, the pad and the first send) are DualSenseSession's.
 */
public final class DualSenseChecks {
    /** Android 12, the first with the Nearby devices permission and the HID host calls tried here. */
    public static final int MIN_SDK = 31;

    private DualSenseChecks() {
    }

    /**
     * READY if Starwire may try the pad, otherwise why not.
     *
     * @param switchedOn  the DualSense switch in Settings
     * @param sdkInt      Build.VERSION.SDK_INT
     * @param permission  Nearby devices (BLUETOOTH_CONNECT) is allowed
     * @param bluetoothOn the device has Bluetooth and it's on
     */
    public static DualSenseStatus beforeConnect(boolean switchedOn, int sdkInt, boolean permission,
                                                boolean bluetoothOn) {
        if (!switchedOn) {
            return DualSenseStatus.OFF;
        }
        if (sdkInt < MIN_SDK) {
            return DualSenseStatus.NEEDS_ANDROID_12;
        }
        if (!permission) {
            return DualSenseStatus.NO_PERMISSION;
        }
        if (!bluetoothOn) {
            return DualSenseStatus.BLUETOOTH_OFF;
        }
        return DualSenseStatus.READY;
    }
}
