package com.limelight.binding.usb.share;

import java.util.HashSet;
import java.util.Set;

/**
 * The device rules of docs/dev/usb-protocol.md ("The device rules, version 1"), applied to what
 * Android tells any app about a device before it may open it, so the USB devices list can say
 * what will happen before you choose one.
 *
 * Android shows the class codes, configurations, interfaces and endpoints, but not interface
 * association descriptors, descriptor lengths or HID report descriptors. So this runs the checks
 * that only need what it shows, in the contract's order, and a device with a HID interface gets
 * an incomplete verdict: the exporter decides it again from all its descriptors when you share
 * it (UsbShareExporter), and that decision is the one that counts.
 */
public final class UsbSharePreview {
    // USB class codes (usb.org "Defined Class Codes")
    static final int CLASS_PER_INTERFACE = 0x00;
    static final int CLASS_AUDIO = 0x01;
    static final int CLASS_COMMUNICATIONS = 0x02;
    static final int CLASS_HID = 0x03;
    static final int CLASS_STILL_IMAGE = 0x06;
    static final int CLASS_MASS_STORAGE = 0x08;
    static final int CLASS_HUB = 0x09;
    static final int CLASS_CDC_DATA = 0x0A;
    static final int CLASS_WIRELESS = 0xE0;
    static final int CLASS_MISC = 0xEF;
    static final int CLASS_VENDOR = 0xFF;

    private static final int AUDIO_CONTROL = 1;
    private static final int AUDIO_STREAMING = 2;
    private static final int AUDIO_MIDI = 3;
    private static final int MISC_RNDIS = 0x04;

    private static final int TYPE_ISOCHRONOUS = 1;
    private static final int TYPE_BULK = 2;
    private static final int TYPE_INTERRUPT = 3;

    private UsbSharePreview() {
    }

    public static UsbVerdict judge(UsbDeviceSummary d) {
        // 1. The descriptors read, as far as Android shows them
        if (d.configurationCount < 1 || !readable(d)) {
            return UsbVerdict.refused(UsbVerdict.RULE_UNREADABLE);
        }
        // 2. One configuration
        if (d.configurationCount != 1) {
            return UsbVerdict.refused(UsbVerdict.RULE_CONFIGURATIONS);
        }
        // 3. The device's own class codes: each interface gives its class, or IADs group them
        boolean perInterface = d.deviceClass == CLASS_PER_INTERFACE && d.deviceSubclass == 0 && d.deviceProtocol == 0;
        boolean grouped = d.deviceClass == CLASS_MISC && d.deviceSubclass == 0x02 && d.deviceProtocol == 0x01;
        if (!perInterface && !grouped) {
            int rule = refusalFor(d.deviceClass, d.deviceSubclass);
            return UsbVerdict.refused(rule != 0 ? rule : UsbVerdict.RULE_OTHER_CLASS);
        }
        // 4. Android doesn't show IADs, so their checks wait for the full descriptors
        // 5. Each interface and alternate setting, in order
        boolean hasHid = false;
        boolean asks = false;
        for (UsbDeviceSummary.Interface i : d.interfaces) {
            int rule = refusalFor(i.interfaceClass, i.subclass);
            if (i.interfaceClass == CLASS_AUDIO) {
                if (i.subclass != AUDIO_CONTROL && i.subclass != AUDIO_STREAMING && i.subclass != AUDIO_MIDI) {
                    return UsbVerdict.refused(UsbVerdict.RULE_OTHER_CLASS);
                }
            }
            else if (i.interfaceClass == CLASS_HID) {
                hasHid = true;
                // A boot keyboard or mouse asks, whatever its report descriptor says
                asks |= i.subclass == 1 && (i.protocol == 1 || i.protocol == 2);
            }
            else {
                return UsbVerdict.refused(rule != 0 ? rule : UsbVerdict.RULE_OTHER_CLASS);
            }
            if (!endpointsFit(i)) {
                return UsbVerdict.refused(UsbVerdict.RULE_UNREADABLE);
            }
        }
        // 6. The report descriptors decide a HID interface, when the device is opened
        if (asks) {
            return UsbVerdict.ASK_UNCHECKED;
        }
        return hasHid ? UsbVerdict.ALLOW_UNCHECKED : UsbVerdict.ALLOW;
    }

    // The rule a class refuses by, or 0 for a class the rules don't refuse outright
    private static int refusalFor(int cls, int subclass) {
        switch (cls) {
            case CLASS_HUB:
                return UsbVerdict.RULE_HUB;
            case CLASS_MASS_STORAGE:
            case CLASS_STILL_IMAGE:
                return UsbVerdict.RULE_STORAGE;
            case CLASS_COMMUNICATIONS:
            case CLASS_CDC_DATA:
            case CLASS_WIRELESS:
                return UsbVerdict.RULE_NETWORK;
            case CLASS_MISC:
                return subclass == MISC_RNDIS ? UsbVerdict.RULE_NETWORK : 0;
            case CLASS_VENDOR:
                // The vendor list is empty in version 1
                return UsbVerdict.RULE_UNLISTED_VENDOR;
            default:
                return 0;
        }
    }

    // Each interface number and alternate setting once, and no endpoint 0 or the same endpoint
    // twice in one alternate setting
    private static boolean readable(UsbDeviceSummary d) {
        Set<Integer> settings = new HashSet<>();
        for (UsbDeviceSummary.Interface i : d.interfaces) {
            if (!settings.add(i.number << 8 | i.alternateSetting)) {
                return false;
            }
            Set<Integer> addresses = new HashSet<>();
            for (int[] e : i.endpoints) {
                if ((e[0] & 0x0F) == 0 || !addresses.add(e[0] & 0xFF)) {
                    return false;
                }
            }
        }
        return true;
    }

    // The endpoints each allowed class may have
    private static boolean endpointsFit(UsbDeviceSummary.Interface i) {
        int in = 0, out = 0;
        for (int[] e : i.endpoints) {
            int type = e[1] & 0x03;
            boolean isIn = (e[0] & 0x80) != 0;
            if (i.interfaceClass == CLASS_AUDIO) {
                if (i.subclass == AUDIO_CONTROL && (type != TYPE_INTERRUPT || !isIn || i.endpoints.size() > 1)) {
                    return false;
                }
                if (i.subclass == AUDIO_STREAMING && type != TYPE_ISOCHRONOUS) {
                    return false;
                }
                if (i.subclass == AUDIO_MIDI && type != TYPE_BULK) {
                    return false;
                }
            }
            else if (i.interfaceClass == CLASS_HID) {
                if (type != TYPE_INTERRUPT) {
                    return false;
                }
                if (isIn) {
                    in++;
                }
                else {
                    out++;
                }
            }
        }
        return in <= 1 && out <= 1;
    }
}
