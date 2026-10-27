package com.limelight.binding.usb.share;

import android.hardware.usb.UsbDevice;

import java.io.IOException;

/**
 * What the share flow needs from the USB/IP exporter inside the app (usbipdcpp, lane U-B; see
 * docs/dev/usb-protocol.md, "How it fits together"). The share flow starts it only after the
 * tunnel's BIND_ACK checks out, and stops it however the share ends.
 */
public interface UsbExporter {
    /** What the exporter gives the tunnel for SHARE. Lanes U-A and U-B add what the relay needs. */
    interface Export {
        /** The bus id the exporter gives the device, like "1-1.2" */
        String busId();
    }

    interface Listener {
        /** The device went away under the exporter: unplugged, or Android took it back. Any thread. */
        void onDeviceGone();
    }

    /**
     * Claims the device, which Android has let Starwire open, and exports it on 127.0.0.1. Blocks,
     * so it's never called on the main thread. Throws if it can't, and then holds nothing.
     */
    Export start(UsbDevice device, Listener listener) throws IOException;

    /** Stops exporting and gives the device back to Android. Blocks; safe to call more than once. */
    void stop();
}
