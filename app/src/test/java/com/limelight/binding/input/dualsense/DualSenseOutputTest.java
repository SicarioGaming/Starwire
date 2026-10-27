package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The USB and Bluetooth output reports, byte for byte. Sources, all read at a fixed commit:
 * <ul>
 * <li>LINUX: torvalds/linux drivers/hid/hid-playstation.c @734bef54 (GPL-2.0-or-later,
 *     copyright Sony Interactive Entertainment).</li>
 * <li>SDL: libsdl-org/SDL src/joystick/hidapi/SDL_hidapi_ps5.c and test/testcontroller.c @8adc0f98
 *     (zlib licence).</li>
 * <li>DS5D: awalol/DS5Dongle src/utils.h and src/main.cpp @daa35cca (MIT).</li>
 * <li>DS4W: ds4windowsapp/DS4Windows DS4Windows/DS4Library/InputDevices/DualSenseDevice.cs
 *     @43c4aed3 (GPL-3.0).</li>
 * <li>DESC: nondebug/dualsense report-descriptor-usb.txt @02338358, the pad's own HID descriptor.</li>
 * </ul>
 */
public class DualSenseOutputTest {
    @Test
    public void reportSizesAndIds() {
        DualSenseOutput out = new DualSenseOutput();
        byte[] usb = out.nextUsbReport();
        // DESC lines 56-59: report ID 2, 47 bytes of output. SDL_hidapi_ps5.c:1106-1109 sends 48.
        assertEquals(48, usb.length);
        assertEquals(0x02, usb[0]);
        // LINUX lines 87-88 and 301-309: report 0x31, 78 bytes. SDL_hidapi_ps5.c:1100-1104 the same.
        byte[] bt = out.nextBluetoothReport();
        assertEquals(78, bt.length);
        assertEquals(0x31, bt[0]);
    }

    @Test
    public void nothingSetMeansAnEmptyReport() {
        DualSenseOutput out = new DualSenseOutput();
        assertFalse(out.hasChanges());
        assertEquals("02" + "00".repeat(47), Hex.of(out.nextUsbReport()));
    }

    @Test
    public void bluetoothHeaderAndSequence() {
        // LINUX lines 1257-1270: byte 1 has the sequence number in its high four bits and goes up by
        // one each report, wrapping at 16; byte 2 is the tag 0x10 (line 150). DS5D main.cpp:220-223
        // and SDL_hidapi_ps5.c:1100-1102 write the same three bytes.
        DualSenseOutput out = new DualSenseOutput();
        for (int i = 0; i < 40; i++) {
            byte[] bt = out.nextBluetoothReport();
            assertEquals("report " + i, (i % 16) << 4, bt[1] & 0xFF);
            assertEquals(0x10, bt[2]);
            // LINUX line 306: 24 reserved bytes after the 47, then the checksum
            for (int j = 3 + 47; j < 74; j++) {
                assertEquals(0, bt[j]);
            }
            assertEquals(DualSenseCrc.outputCrc(bt, 74), readLe32(bt, 74));
        }
    }

    @Test
    public void lightBarReportMatchesTheDesignDraft() {
        // LINUX lines 1344-1349: flag 1 bit 0x04 and the colour at common bytes 44-46
        DualSenseOutput out = new DualSenseOutput();
        out.setLightbar(255, 0, 0);
        assertEquals(DualSenseCrcTest.LIGHTBAR_RED_REPORT.toLowerCase(), Hex.of(out.nextBluetoothReport()));
        // Sent once: the next report leaves the light bar alone
        assertEquals(0, out.nextUsbReport()[1 + DualSenseOutput.FLAGS1]);
    }

    @Test
    public void rumbleUsesTheOriginalOrImprovedFlag() {
        // LINUX lines 1332-1342: haptics select (0x02), then compatible vibration in flag 0 (0x01) or,
        // on newer firmware, flag 2's 0x04; right motor at common byte 2, left at 3.
        // SDL_hidapi_ps5.c:724-735 sets the same bits.
        DualSenseOutput out = new DualSenseOutput();
        out.setRumble(0x80, 0x40);
        byte[] c = Hex.common(out.nextUsbReport());
        assertEquals(0x03, c[0]);
        assertEquals(0x40, c[2]);
        assertEquals((byte) 0x80, c[3]);
        assertEquals(0, c[38]);

        out.setImprovedRumble(true);
        c = Hex.common(out.nextUsbReport());
        assertEquals(0x02, c[0]);
        assertEquals(0x04, c[38]);
        assertEquals(0x40, c[2]);
        assertEquals((byte) 0x80, c[3]);
    }

    @Test
    public void runningMotorsGoInEveryReportAndAStopGoesOnce() {
        DualSenseOutput out = new DualSenseOutput();
        out.setRumble(10, 20);
        for (int i = 0; i < 3; i++) {
            byte[] c = Hex.common(out.nextUsbReport());
            assertEquals(0x03, c[0]);
            assertEquals(20, c[2]);
            assertEquals(10, c[3]);
        }
        out.setRumble(0, 0);
        byte[] stop = Hex.common(out.nextUsbReport());
        assertEquals(0x03, stop[0]);
        assertEquals(0, stop[2]);
        assertEquals(0, stop[3]);
        // SDL_hidapi_ps5.c:737: with the rumble bits off, the pad goes back to HD haptics
        assertEquals(0, out.nextUsbReport()[1]);
        assertFalse(out.hasChanges());
    }

    @Test
    public void motorValuesAreClampedToAByte() {
        DualSenseOutput out = new DualSenseOutput();
        out.setRumble(300, -5);
        byte[] c = Hex.common(out.nextUsbReport());
        assertEquals((byte) 0xFF, c[3]);
        assertEquals(0x03, c[0]);
        assertEquals(0, c[2]);
    }

    @Test
    public void playerLightsMatchLinuxAndSdl() {
        // LINUX lines 1722-1728, the player 1 to 5 patterns (centred, as on a PS5), and lines
        // 1353-1357: flag 1 bit 0x10 and the mask at common byte 43
        int[] linux = {0x04, 0x0A, 0x15, 0x1B, 0x1F};
        for (int mask : linux) {
            DualSenseOutput out = new DualSenseOutput();
            out.setPlayerLeds(mask, false);
            byte[] c = Hex.common(out.nextUsbReport());
            assertEquals(0x10, c[1]);
            assertEquals(mask, c[43]);
        }
        // SDL_hidapi_ps5.c:356-369: the same patterns with 0x20, "changes instantly instead of fade"
        DualSenseOutput out = new DualSenseOutput();
        out.setPlayerLeds(0x04, true);
        assertEquals(0x24, Hex.common(out.nextUsbReport())[43]);
        // Only five lights: other bits are dropped
        out.setPlayerLeds(0xFF, false);
        assertEquals(0x1F, Hex.common(out.nextUsbReport())[43]);
    }

    @Test
    public void triggersMatchSdlsTestProgram() {
        // SDL testcontroller.c:521-536: both triggers get { 0x01, 0, 110 } with flag 0 bits
        // 0x04 | 0x08, right block at common byte 10, left at 21 (its DS5EffectsState_t, 453-477)
        DualSenseOutput out = new DualSenseOutput();
        TriggerEffect constant = TriggerEffect.continuousResistance(0, 110);
        out.setRightTrigger(constant);
        out.setLeftTrigger(constant);
        String expected = "0c00" + "00".repeat(8) + "01006e" + "00".repeat(8) + "01006e"
                + "00".repeat(8) + "00".repeat(15);
        assertEquals(expected, Hex.of(Hex.common(out.nextUsbReport())));
    }

    @Test
    public void triggersGoOnceAndAgainAfterResendAll() {
        DualSenseOutput out = new DualSenseOutput();
        out.setRightTrigger(TriggerEffect.weapon(2, 6, 8));
        assertEquals(0x04, out.nextUsbReport()[1]);
        assertEquals(0x00, out.nextUsbReport()[1]);
        // The same effect again changes nothing: hosts send only on change, and resending could
        // restart a weapon effect mid-pull (a guess, not tried on a pad)
        out.setRightTrigger(TriggerEffect.weapon(2, 6, 8));
        assertFalse(out.hasChanges());

        out.setLeftTrigger(TriggerEffect.feedback(3, 6));
        out.setLightbar(1, 2, 3);
        out.setPlayerLeds(0x04, true);
        out.setMotorPowerCut(1, 1);
        out.nextUsbReport();
        assertFalse(out.hasChanges());

        out.resendAll();
        byte[] c = Hex.common(out.nextUsbReport());
        assertEquals(0x0C, c[0]);
        assertEquals(0x04 | 0x10 | 0x40, c[1] & 0xFF);
        assertEquals("2544000700000000000000", Hex.of(slice(c, 10, 11)));
        assertEquals("21f80300dab62d00000000", Hex.of(slice(c, 21, 11)));
    }

    @Test
    public void motorPowerCutNibbles() {
        // DS5D utils.h:320 (flag 1 bit 6) and 363-364 (common byte 36: rumble in the low four bits,
        // triggers in the high four). DS4W DualSenseDevice.cs:1063 (0x40) and 1122-1123 (USB byte 37,
        // "lower nibble: main motor; upper nibble trigger effects", 0 to 7, 12.5% a step).
        DualSenseOutput out = new DualSenseOutput();
        out.setMotorPowerCut(2, 5);
        byte[] c = Hex.common(out.nextUsbReport());
        assertEquals(0x40, c[1]);
        assertEquals(0x52, c[36]);
        try {
            out.setMotorPowerCut(8, 0);
            fail("8 steps is past what both sources agree on");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void releasingTheLedsMatchesLinuxAndSdl() {
        // LINUX lines 1694-1695 (dualsense_reset_leds): flag 2 bit 0x02 and light bar setup 0x02,
        // "Fade light out". LINUX line 162 and SDL_hidapi_ps5.c:749: flag 1 bit 0x08 releases the LEDs.
        DualSenseOutput out = new DualSenseOutput();
        out.releaseLeds();
        assertTrue(out.hasChanges());
        byte[] c = Hex.common(out.nextUsbReport());
        assertEquals(0x08, c[1]);
        assertEquals(0x02, c[38]);
        assertEquals(0x02, c[41]);
        assertFalse(out.hasChanges());
    }

    @Test
    public void everythingAtOnceOverUsbAndBluetooth() {
        // Rumble left 0x80 and right 0x40, right trigger weapon(2, 6, 8), left feedback(3, 6),
        // player light 0x04 with no fade, light bar 00 40 80. The Bluetooth report is the fourth
        // (sequence 3). Both expected strings were rebuilt with a separate Node script whose
        // checksum is zlib's crc32: 0x30F0246D over 0xA2 and bytes 0 to 73.
        String common = "0f144080000000000000254400070000000000000021f80300dab62d000000000000000000000000000000"
                + "24004080";
        DualSenseOutput usb = state(new DualSenseOutput());
        assertEquals("02" + common, Hex.of(usb.nextUsbReport()));

        DualSenseOutput bt = new DualSenseOutput();
        for (int i = 0; i < 3; i++) {
            bt.nextBluetoothReport();
        }
        state(bt);
        String expected = "313010" + common + "00".repeat(24) + "6d24f030";
        byte[] report = bt.nextBluetoothReport();
        assertEquals(expected, Hex.of(report));
        assertEquals(0x30F0246D, DualSenseCrcTest.bitwiseCrc32(withSeed(report, 74)));
    }

    private static DualSenseOutput state(DualSenseOutput out) {
        out.setRumble(0x80, 0x40);
        out.setRightTrigger(TriggerEffect.weapon(2, 6, 8));
        out.setLeftTrigger(TriggerEffect.feedback(3, 6));
        out.setPlayerLeds(0x04, true);
        out.setLightbar(0x00, 0x40, 0x80);
        return out;
    }

    private static byte[] slice(byte[] b, int from, int length) {
        byte[] s = new byte[length];
        System.arraycopy(b, from, s, 0, length);
        return s;
    }

    private static byte[] withSeed(byte[] report, int length) {
        byte[] data = new byte[length + 1];
        data[0] = (byte) 0xA2;
        System.arraycopy(report, 0, data, 1, length);
        return data;
    }

    private static int readLe32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }
}
