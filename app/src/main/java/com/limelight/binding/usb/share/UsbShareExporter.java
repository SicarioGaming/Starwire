package com.limelight.binding.usb.share;

import android.hardware.usb.UsbDevice;

import java.io.IOException;
import java.net.Socket;

/**
 * What the share flow needs from the USB/IP exporter inside the app (usbipdcpp; lane U-B,
 * com.limelight.binding.usb's UsbExporter): the device rules applied to all of a device's
 * descriptors, HID report descriptors included, then the export, both from one read of them, so
 * what the rules decided is exactly what SHARE describes (docs/dev/usb-protocol.md, "The device
 * rules, version 1").
 *
 * The share flow starts it only from the tunnel's Exporter.start(), after BIND_ACK checks out.
 * Each share gets a new one, made with its listener (UsbShareBackends.Factory.exporter).
 */
public interface UsbShareExporter {
    interface Listener {
        /** The device went away under the exporter: unplugged, or Android took it back. Any thread. */
        void onDeviceGone();
    }

    /** What the exporter made of the device: SHARE's bytes for the tunnel. */
    interface Export {
        /** The SHARE frame, describing exactly the descriptors the rules checked */
        byte[] shareFrame();
    }

    /** The rules didn't let it be shared: refused for a rule, or it asks and had no yes. */
    final class Refused extends Exception {
        public final UsbVerdict verdict;

        public Refused(UsbVerdict verdict) {
            super("USB device " + verdict);
            this.verdict = verdict;
        }
    }

    /**
     * Opens the device (Android has let Starwire), applies the device rules and, if they allow
     * it, exports it on 127.0.0.1. A keyboard or mouse needs inputConfirmed, your yes for this
     * share: without it, Refused with an ASK verdict. With it, SHARE says so only if the rules
     * asked. Blocks, so never on the main thread. When it throws, it holds nothing.
     */
    Export start(UsbDevice device, boolean inputConfirmed) throws Refused, IOException;

    /**
     * The one connection to the export on 127.0.0.1, for the tunnel's relay, connected within
     * timeoutMs. Every app on the phone can reach 127.0.0.1, so the exporter lets in only this
     * one (U-B's UsbExporter.connect binds the socket first and authorises its source port). At
     * most once, after start(). Blocks.
     */
    Socket connect(int timeoutMs) throws IOException;

    /** Stops exporting and gives the device back to Android. Blocks; safe to call more than once. */
    void stop();
}
