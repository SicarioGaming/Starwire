package com.limelight.binding.usb.share;

import android.content.Context;

/**
 * The parts of USB sharing below the share flow: the exporter, with the device rules
 * (UsbExporter), and the tunnel (UsbTunnel), through RealUsbShareBackends. On a phone that can't
 * run the exporter, isSupported() is false, and the setting and the stream menu's USB devices item
 * stay hidden, so nothing offers a share that can't happen.
 */
public final class UsbShareBackends {
    public interface Factory {
        /** Whether this phone can share at all: Android 9 or later, and an ABI the exporter has. */
        boolean isSupported();

        /** A new exporter for one share, which tells listener if the device goes away under it */
        UsbShareExporter exporter(Context context, UsbShareExporter.Listener listener);

        /** A new tunnel for one connection, which starts and stops exporter and tells listener */
        UsbShareTunnel tunnel(Context context, UsbShareTunnel.Exporter exporter, UsbShareTunnel.Listener listener);
    }

    private static Factory forTests;

    private UsbShareBackends() {
    }

    /** The app's backends, or the tests' stand-ins. */
    public static Factory get() {
        if (forTests != null) {
            return forTests;
        }
        return RealUsbShareBackends.app();
    }

    /** Whether this build and this phone can share USB devices. */
    public static boolean available() {
        Factory factory = get();
        return factory != null && factory.isSupported();
    }

    /** Stands in fakes for the real backends; null puts the build's own back. Tests only. */
    public static void setForTests(Factory factory) {
        forTests = factory;
    }
}
