package com.limelight.binding.input.dualsense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Bluetooth HID device that is the DualSense Android shows as a controller, so a report
 * never goes to some other Bluetooth device.
 *
 * The pad's Bluetooth address comes first: Android sometimes puts it in an InputDevice's text or
 * descriptor. A pad found by its address is never swapped for another device. Without an address,
 * the one connected HID device named like a DualSense is the pad. Both ideas are ported from
 * Moonlight V+ by qiin2333 (GPL-3.0), AndroidBluetoothHidHostTransport.kt:155-179 and
 * DirectDualSenseBluetoothOutput.kt:99-131 and :143-144 at 5840bab3. Unlike V+, a lone HID device
 * with another name is never used, and two DualSenses are refused: 0.3.0 handles one at a time.
 *
 * Plain Java, so the JVM tests run it as the app does.
 */
public final class PadMatcher {
    public static final int SONY_VENDOR_ID = 0x054C;
    public static final int DUALSENSE_PRODUCT_ID = 0x0CE6;
    /** The DualSense Edge. Same reports in Linux's driver; not tried for Starwire. */
    public static final int DUALSENSE_EDGE_PRODUCT_ID = 0x0DF2;

    private static final String ADDRESS = "([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})";
    private static final Pattern IN_DESCRIPTION = Pattern.compile("bluetoothAddress=" + ADDRESS);
    private static final Pattern IN_DESCRIPTOR = Pattern.compile("(?i)bluetooth:" + ADDRESS);

    /** What find decided: the device, or why there's none. */
    public static final class Result {
        public final HidHost.Device device;
        public final DualSenseStatus status;

        private Result(HidHost.Device device, DualSenseStatus status) {
            this.device = device;
            this.status = status;
        }
    }

    private PadMatcher() {
    }

    /** A DualSense or DualSense Edge by its USB IDs, which Android reports over Bluetooth too. */
    public static boolean isDualSense(int vendorId, int productId) {
        return vendorId == SONY_VENDOR_ID
                && (productId == DUALSENSE_PRODUCT_ID || productId == DUALSENSE_EDGE_PRODUCT_ID);
    }

    /**
     * The Bluetooth address in an InputDevice's toString() or descriptor, upper case, or null.
     * Many phones show neither (guess), and then find goes by name.
     */
    public static String addressFrom(String description, String descriptor) {
        String address = first(IN_DESCRIPTION, description);
        if (address == null) {
            address = first(IN_DESCRIPTOR, descriptor);
        }
        return address == null ? null : address.toUpperCase(Locale.ROOT);
    }

    /** A connected HID device whose name Android gives a DualSense over Bluetooth. */
    public static boolean looksLikeDualSense(HidHost.Device device) {
        String name = device.name == null ? "" : device.name.trim();
        return name.toLowerCase(Locale.ROOT).contains("dualsense")
                || name.equalsIgnoreCase("Wireless Controller");
    }

    /**
     * The pad among the connected HID devices.
     *
     * @param address the pad's address from addressFrom, or null
     */
    public static Result find(List<HidHost.Device> connected, String address) {
        List<HidHost.Device> pads = new ArrayList<>();
        HidHost.Device addressed = null;
        for (HidHost.Device device : connected) {
            boolean match = address != null && address.equalsIgnoreCase(device.address);
            if (match) {
                addressed = device;
            }
            if (match || looksLikeDualSense(device)) {
                pads.add(device);
            }
        }
        if (pads.size() > 1) {
            return new Result(null, DualSenseStatus.TWO_PADS);
        }
        if (address != null) {
            // Never another device in place of the one Android named
            return addressed != null ? new Result(addressed, DualSenseStatus.READY)
                    : new Result(null, DualSenseStatus.NO_PAD);
        }
        return pads.isEmpty() ? new Result(null, DualSenseStatus.NO_PAD)
                : new Result(pads.get(0), DualSenseStatus.READY);
    }

    private static String first(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
