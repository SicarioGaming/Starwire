package com.limelight.binding.usb.share;

import android.hardware.usb.UsbDevice;

import java.io.IOException;

/**
 * The device rules applied to all of a device's descriptors, HID report descriptors included,
 * once Android lets Starwire open it (lane U-B; docs/dev/usb-protocol.md, "The device rules,
 * version 1"). Its decision is the one the share flow acts on. UsbSharePreview only says what to
 * expect.
 */
public interface UsbDeviceRules {
    /** Opens the device, reads its descriptors and decides. Blocks, so never on the main thread. */
    UsbVerdict check(UsbDevice device) throws IOException;
}
