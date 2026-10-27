package com.limelight.usbip;

import org.junit.Test;
import static org.junit.Assert.*;

public class UsbIpBackendSupportTest {
    @Test public void supportsArm64OnAndroid9() {
        assertTrue(UsbIpBackend.isSupported(28, true, new String[]{"arm64-v8a"}));
    }

    // Starwire builds no ARMv7 exporter, so a 32-bit process has nothing to load
    @Test public void refusesArmv7() {
        assertFalse(UsbIpBackend.isSupported(28, false, new String[]{"armeabi-v7a", "armeabi"}));
        assertFalse(UsbIpBackend.isSupported(28, false, new String[]{"arm64-v8a", "armeabi-v7a"}));
    }

    @Test public void requiresAndroid9() {
        assertFalse(UsbIpBackend.isSupported(27, true, new String[]{"arm64-v8a"}));
        assertFalse(UsbIpBackend.isSupported(27, false, new String[]{"armeabi-v7a"}));
    }

    @Test public void matchesProcessWidthRatherThanDevicePrimaryAbi() {
        assertFalse(UsbIpBackend.isSupported(28, true, new String[]{"armeabi-v7a"}));
    }

    // x86_64 is built into debug builds for the device tests only
    @Test public void rejectsNonArmAndMissingAbis() {
        assertFalse(UsbIpBackend.isSupported(28, true, new String[]{"x86_64"}));
        assertFalse(UsbIpBackend.isSupported(28, false, new String[]{"x86"}));
        assertFalse(UsbIpBackend.isSupported(28, false, new String[]{"armeabi"}));
        assertFalse(UsbIpBackend.isSupported(28, false, new String[]{}));
        assertFalse(UsbIpBackend.isSupported(28, true, null));
    }
}
