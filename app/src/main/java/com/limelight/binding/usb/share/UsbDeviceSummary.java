package com.limelight.binding.usb.share;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A USB device plugged into the phone, as Android describes it to any app before it has
 * permission: its IDs, class codes, and each interface and alternate setting of its first
 * configuration with their endpoints. AndroidUsbDevices makes these from UsbDevice; tests make
 * them by hand.
 */
public final class UsbDeviceSummary {
    /** One interface's alternate setting */
    public static final class Interface {
        public final int number;
        public final int alternateSetting;
        public final int interfaceClass;
        public final int subclass;
        public final int protocol;
        /** Each endpoint as {bEndpointAddress, bmAttributes} */
        public final List<int[]> endpoints;

        public Interface(int number, int alternateSetting, int interfaceClass, int subclass, int protocol,
                         List<int[]> endpoints) {
            this.number = number;
            this.alternateSetting = alternateSetting;
            this.interfaceClass = interfaceClass;
            this.subclass = subclass;
            this.protocol = protocol;
            this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
        }
    }

    // Longest name shown, in characters; the contract allows 64 bytes in SHARE
    static final int MAX_NAME = 64;

    /** Android's name for the device (UsbDevice.getDeviceName), new for each time it's plugged in */
    public final String key;
    /** What to call it on screen: the product name, cleaned, or null if it gave none */
    public final String name;
    public final int vendorId;
    public final int productId;
    public final int deviceClass;
    public final int deviceSubclass;
    public final int deviceProtocol;
    public final int configurationCount;
    public final List<Interface> interfaces;

    public UsbDeviceSummary(String key, String name, int vendorId, int productId, int deviceClass,
                            int deviceSubclass, int deviceProtocol, int configurationCount,
                            List<Interface> interfaces) {
        this.key = key;
        this.name = cleanName(name);
        this.vendorId = vendorId;
        this.productId = productId;
        this.deviceClass = deviceClass;
        this.deviceSubclass = deviceSubclass;
        this.deviceProtocol = deviceProtocol;
        this.configurationCount = configurationCount;
        this.interfaces = Collections.unmodifiableList(new ArrayList<>(interfaces));
    }

    /**
     * The device's own name without control or formatting characters, which a device could use
     * to make its name read as another's (a right-to-left override, say), and at most MAX_NAME
     * characters. Null if nothing is left.
     */
    static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < name.length() && clean.length() < MAX_NAME; ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.UNASSIGNED || type == Character.PRIVATE_USE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                continue;
            }
            if (clean.length() + Character.charCount(c) > MAX_NAME) {
                break;
            }
            clean.appendCodePoint(c);
        }
        String trimmed = clean.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
