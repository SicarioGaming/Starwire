package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * The light bar setup goes in a report of its own, before any colour, as Linux's
 * dualsense_reset_leds sends it (drivers/hid/hid-playstation.c:1677-1700 @734bef54, called once
 * at probe before the first colour) and as Moonlight V+ does (DualSenseBluetoothOutput.kt:85-92
 * at 5840bab3).
 *
 * The expected reports are the light-bar test app v2's "Setup, then RED" button, byte for byte:
 * its README lists their checksums, 0x3631E643 and 0x2F9F847C, worked out by that app's own
 * bit-by-bit CRC, not by DualSenseCrc.
 */
public class DualSenseLightbarSetupTest {
    // setup, sequence 0: flags 2 (byte 41) 0x02 and light bar setup (byte 44) 0x02, nothing else
    private static final String SETUP = "310010" + "00".repeat(38) + "02" + "0000" + "02" + "00".repeat(29) + "43e63136";
    // red, sequence 1: flags 1 (byte 4) 0x04 and red (byte 47) 0xff
    private static final String RED = "311010" + "0004" + "00".repeat(42) + "ff0000" + "00".repeat(24) + "7c849f2f";

    @Test
    public void setupThenRedMatchesTheTestApp() {
        DualSenseOutput out = new DualSenseOutput();
        out.resetLightbar();
        out.setLightbar(255, 0, 0);
        assertEquals(78, Hex.bytes(SETUP).length);
        assertEquals(SETUP, Hex.of(out.nextBluetoothReport()));
        assertEquals(RED, Hex.of(out.nextBluetoothReport()));
        assertFalse(out.hasChanges());
    }

    @Test
    public void theSetupWaitsForNothingAndNothingWaitsInIt() {
        // Everything already set waits for the report after the setup
        DualSenseOutput out = new DualSenseOutput();
        out.setRightTrigger(TriggerEffect.feedback(3, 6));
        out.setPlayerLeds(0x04, true);
        out.setLightbar(0, 0x40, 0x80);
        out.resetLightbar();

        byte[] setup = Hex.common(out.nextUsbReport());
        byte[] expected = new byte[DualSenseOutput.COMMON_SIZE];
        expected[38] = 0x02;
        expected[41] = 0x02;
        assertEquals(Hex.of(expected), Hex.of(setup));

        byte[] rest = Hex.common(out.nextUsbReport());
        assertEquals("right trigger", 0x04, rest[0]);
        assertEquals("light bar and player lights", 0x14, rest[1]);
        assertEquals("no setup again", 0, rest[38]);
        assertFalse(out.hasChanges());
    }

    @Test
    public void releasingTheLedsStillGoesWithTheSetupAlone() {
        // The spike's releaseLeds (Linux's and SDL's release flag plus the setup) is one report of
        // its own too, ahead of a colour
        DualSenseOutput out = new DualSenseOutput();
        out.setLightbar(255, 0, 0);
        out.releaseLeds();
        byte[] first = Hex.common(out.nextUsbReport());
        assertEquals(0x08, first[1]);
        assertEquals(0x02, first[38]);
        assertEquals(0x02, first[41]);
        assertEquals("no colour yet", 0, first[44] & 0xFF);
        byte[] second = Hex.common(out.nextUsbReport());
        assertEquals(0x04, second[1]);
        assertEquals(0xFF, second[44] & 0xFF);
    }
}
