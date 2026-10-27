package com.limelight.binding.usb.share;

import android.content.Context;

/**
 * The parts of USB sharing that other lanes write: the exporter, with the device rules (U-B), and
 * the tunnel (U-A). Until a build has them, get() is null, and the setting and the stream menu's
 * USB devices item stay hidden, so nothing offers a share that can't happen.
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

    /** The backends this build has, or null. */
    public static Factory get() {
        if (forTests != null) {
            return forTests;
        }
        // The exporter and the tunnel aren't in this build yet. The lane that brings them in
        // returns them here.
        return null;
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
