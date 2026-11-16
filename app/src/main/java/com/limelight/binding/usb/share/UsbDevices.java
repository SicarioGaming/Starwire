package com.limelight.binding.usb.share;

import android.hardware.usb.UsbDevice;

import java.util.List;

/**
 * The phone's side of USB: which devices are plugged in, and Android's permission to open one.
 * AndroidUsbDevices is the real one. Everything here is called and answers on the main thread.
 */
public interface UsbDevices {
    int GRANTED = 0;
    int DENIED = 1;
    // A device policy (Knox, an MDM) stopped Android from asking
    int BLOCKED = 2;

    interface Listener {
        void onAttached(UsbDeviceSummary device);

        void onDetached(String key);
    }

    interface PermissionCallback {
        /** GRANTED, DENIED or BLOCKED */
        void onAnswer(int result);
    }

    /** Starts telling the listener when devices come and go. */
    void start(Listener listener);

    void stop();

    /** The devices plugged in now */
    List<UsbDeviceSummary> list();

    /** Android's handle for a device, or null once it's unplugged */
    UsbDevice device(String key);

    /** Asks Android for permission to open the device, or answers at once if Starwire has it. */
    void requestPermission(String key, PermissionCallback callback);
}
