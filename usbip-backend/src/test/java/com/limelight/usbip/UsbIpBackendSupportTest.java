package com.limelight.usbip;

import org.junit.Test;
import static org.junit.Assert.*;

public class UsbIpBackendSupportTest {
    @Test public void supportsArm64OnAndroid9() {
        assertTrue(UsbIpBackend.isSupported(28, true, "aarch64"));
        assertTrue(UsbIpBackend.isSupported(36, true, "aarch64"));
    }

    // Starwire builds no ARMv7 exporter, so a 32-bit process has nothing to load, even on a
    // 64-bit phone, whose kernel calls a 32-bit process armv8l
    @Test public void refusesArmv7() {
        assertFalse(UsbIpBackend.isSupported(28, false, "armv7l"));
        assertFalse(UsbIpBackend.isSupported(28, false, "armv8l"));
        assertFalse(UsbIpBackend.isSupported(28, false, "aarch64"));
    }

    @Test public void requiresAndroid9() {
        assertFalse(UsbIpBackend.isSupported(27, true, "aarch64"));
        assertFalse(UsbIpBackend.isSupported(27, false, "armv7l"));
    }

    // An x86_64 emulator or Chromebook lists arm64-v8a among its ABIs, for the ARM apps it
    // translates, but Starwire runs there as an x86_64 process, and a release has no x86_64
    // exporter library: loading it would fail
    @Test public void refusesX86_64EvenWhereItTranslatesArm() {
        assertFalse(UsbIpBackend.isSupported(30, true, "x86_64"));
        assertFalse(UsbIpBackend.isSupported(34, true, "x86_64"));
    }

    // x86_64 is built into debug builds for the device tests only
    @Test public void rejectsNonArmAndMissingArchitectures() {
        assertFalse(UsbIpBackend.isSupported(28, false, "i686"));
        assertFalse(UsbIpBackend.isSupported(28, true, ""));
        assertFalse(UsbIpBackend.isSupported(28, true, null));
    }
}
