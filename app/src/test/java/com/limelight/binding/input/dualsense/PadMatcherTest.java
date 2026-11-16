package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finding the pad among Android's Bluetooth HID devices, address first (an idea from Moonlight V+
 * by qiin2333, AndroidBluetoothHidHostTransport.kt:155-179 at 5840bab3), and never another device.
 */
public class PadMatcherTest {
    private static final HidHost.Device PAD = new HidHost.Device("A0:5A:5C:00:00:01", "DualSense Wireless Controller");
    private static final HidHost.Device KEYBOARD = new HidHost.Device("11:22:33:44:55:66", "Keyboard K380");

    @Test
    public void dualSenseAndEdgeByTheirIds() {
        assertTrue(PadMatcher.isDualSense(0x054C, 0x0CE6));
        assertTrue(PadMatcher.isDualSense(0x054C, 0x0DF2));
        assertFalse("a DualShock 4", PadMatcher.isDualSense(0x054C, 0x09CC));
        assertFalse("another maker", PadMatcher.isDualSense(0x045E, 0x0CE6));
    }

    @Test
    public void addressFromTheDeviceTextOrDescriptor() {
        // V+'s two patterns (DirectDualSenseBluetoothOutput.kt:99, :130)
        assertEquals("A0:5A:5C:00:00:01", PadMatcher.addressFrom(
                "Input Device 7: DualSense Wireless Controller\n  bluetoothAddress=a0:5a:5c:00:00:01\n", null));
        assertEquals("A0:5A:5C:00:00:01", PadMatcher.addressFrom("", "bluetooth:A0:5A:5C:00:00:01"));
        // Android's usual descriptor is a hash, with no address in it
        assertNull(PadMatcher.addressFrom("Input Device 7: DualSense Wireless Controller",
                "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d"));
        // 17 characters of colons and hex digits aren't an address
        assertNull(PadMatcher.addressFrom("bluetoothAddress=:::::::::::::::::", null));
        assertNull(PadMatcher.addressFrom(null, null));
    }

    @Test
    public void byAddressItIsThatDevice() {
        PadMatcher.Result r = PadMatcher.find(Arrays.asList(KEYBOARD, PAD), "a0:5a:5c:00:00:01");
        assertSame(PAD, r.device);
        assertEquals(DualSenseStatus.READY, r.status);
    }

    @Test
    public void byAddressItIsNeverAnotherDevice() {
        // The addressed pad isn't connected over Bluetooth (a cable, say), but another DualSense is
        HidHost.Device other = new HidHost.Device("A0:5A:5C:00:00:02", "DualSense Wireless Controller");
        PadMatcher.Result r = PadMatcher.find(Collections.singletonList(other), "A0:5A:5C:00:00:01");
        assertNull(r.device);
        assertEquals(DualSenseStatus.NO_PAD, r.status);
    }

    @Test
    public void withoutAnAddressTheOneDualSenseByName() {
        // "DualSense Wireless Controller" is how the maintainer's tablet lists the pad (26 Oct)
        assertSame(PAD, PadMatcher.find(Arrays.asList(KEYBOARD, PAD), null).device);
    }

    @Test
    public void aDualShock4IsNeverThePad() {
        // A DualShock 4 calls itself "Wireless Controller" over Bluetooth. Its reports aren't the
        // DualSense's, so it must never get them
        HidHost.Device ds4 = new HidHost.Device("11:22:33:44:55:77", "Wireless Controller");
        PadMatcher.Result alone = PadMatcher.find(Collections.singletonList(ds4), null);
        assertNull(alone.device);
        assertEquals(DualSenseStatus.NO_PAD, alone.status);
        // A DualSense with no address beside it: the DualSense, not "two pads"
        PadMatcher.Result both = PadMatcher.find(Arrays.asList(ds4, PAD), null);
        assertSame(PAD, both.device);
        assertEquals(DualSenseStatus.READY, both.status);
    }

    @Test
    public void aLoneDeviceWithAnotherNameIsNotThePad() {
        // V+ falls back to any lone HID device; Starwire doesn't, so a keyboard never gets pad reports
        PadMatcher.Result r = PadMatcher.find(Collections.singletonList(KEYBOARD), null);
        assertNull(r.device);
        assertEquals(DualSenseStatus.NO_PAD, r.status);
        assertEquals(DualSenseStatus.NO_PAD, PadMatcher.find(Collections.emptyList(), null).status);
    }

    @Test
    public void twoDualSensesAreRefused() {
        HidHost.Device second = new HidHost.Device("A0:5A:5C:00:00:02", "DualSense Edge Wireless Controller");
        List<HidHost.Device> both = Arrays.asList(PAD, second);
        assertEquals(DualSenseStatus.TWO_PADS, PadMatcher.find(both, null).status);
        assertEquals("even with an address", DualSenseStatus.TWO_PADS, PadMatcher.find(both, PAD.address).status);
        assertNull(PadMatcher.find(both, PAD.address).device);
    }

    @Test
    public void namesAreMatchedLoosely() {
        assertTrue(PadMatcher.looksLikeDualSense(new HidHost.Device("x", "  dualsense wireless controller ")));
        assertTrue(PadMatcher.looksLikeDualSense(new HidHost.Device("x", "DualSense Edge Wireless Controller")));
        assertFalse("a DualShock 4", PadMatcher.looksLikeDualSense(new HidHost.Device("x", "WIRELESS CONTROLLER")));
        assertFalse(PadMatcher.looksLikeDualSense(new HidHost.Device("x", "Wireless Controller 2")));
        assertFalse(PadMatcher.looksLikeDualSense(new HidHost.Device("x", null)));
    }
}
