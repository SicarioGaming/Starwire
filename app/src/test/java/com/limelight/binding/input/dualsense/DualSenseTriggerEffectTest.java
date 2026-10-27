package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Each trigger effect's 11 bytes against published vectors. Sources, all read at a fixed commit:
 * <ul>
 * <li>GIST: Nielk1's TriggerEffectGenerator, gist.github.com/Nielk1/6d54cc2c00d2201ccb8c2720ad7538db
 *     @f73a299f (revision 6, MIT). Its reWASD factories are byte patterns logged from reWASD.</li>
 * <li>SDL: libsdl-org/SDL test/testcontroller.c @8adc0f98 (zlib licence), CyclePS5TriggerEffect.</li>
 * <li>DS4W: ds4windowsapp/DS4Windows DS4Windows/DS4Library/InputDevices/DualSenseDevice.cs
 *     @43c4aed3 (GPL-3.0), the FullClick preset.</li>
 * <li>DSW: Ohjurot/DualSense-Windows VS19_Solution/DualSenseWindows/src/DualSenseWindows/DS5_Output.cpp
 *     @a78fbab1 (MIT), processTrigger.</li>
 * </ul>
 * Where no source prints the bytes of an official effect, the vector is worked out by hand from
 * GIST's bit layout, with the arithmetic in the comment, and was rechecked with a separate Node
 * transcription of GIST's functions.
 */
public class DualSenseTriggerEffectTest {
    private static void assertBytes(String expected, TriggerEffect effect) {
        assertEquals(expected.replace(" ", ""), Hex.of(effect.toBytes()));
    }

    @Test
    public void off() {
        // GIST Off(), lines 139-153: mode 0x05 and ten zeros. SDL testcontroller.c:523 sends the same.
        assertBytes("05 00 00 00 00 00 00 00 00 00 00", TriggerEffect.off());
        assertEquals(TriggerEffect.MODE_OFF, TriggerEffect.off().mode());
    }

    @Test
    public void continuousResistanceMatchesSdlAndReWasd() {
        // SDL testcontroller.c:524-525, "Constant resistance across entire trigger pull": { 0x01, 0, 110 }
        assertBytes("01 00 6e 00 00 00 00 00 00 00 00", TriggerEffect.continuousResistance(0, 110));
        // GIST reWASD MediumRigidity (line 1015), MaxRigidity (1029) and HalfPress (1043) call
        // Simple_Feedback (573-587: [1] = position, [2] = strength) with these values
        assertBytes("01 00 64 00 00 00 00 00 00 00 00", TriggerEffect.continuousResistance(0x00, 0x64));
        assertBytes("01 00 dc 00 00 00 00 00 00 00 00", TriggerEffect.continuousResistance(0x00, 0xdc));
        assertBytes("01 55 64 00 00 00 00 00 00 00 00", TriggerEffect.continuousResistance(0x55, 0x64));
        // DSW DS5_Output.cpp:43-50 writes the same layout: mode 0x01, start, force
    }

    @Test
    public void sectionResistanceMatchesReWasdAndDs4Windows() {
        // GIST reWASD FullPress (905), SoftPress (919), MediumPress (933), HardPress (947) call
        // Simple_Weapon (602-616: [1] = start, [2] = end, [3] = strength)
        assertBytes("02 90 a0 ff 00 00 00 00 00 00 00", TriggerEffect.sectionResistance(0x90, 0xa0, 0xff));
        assertBytes("02 70 a0 ff 00 00 00 00 00 00 00", TriggerEffect.sectionResistance(0x70, 0xa0, 0xff));
        assertBytes("02 45 a0 ff 00 00 00 00 00 00 00", TriggerEffect.sectionResistance(0x45, 0xa0, 0xff));
        assertBytes("02 20 a0 ff 00 00 00 00 00 00 00", TriggerEffect.sectionResistance(0x20, 0xa0, 0xff));
        // DS4W DualSenseDevice.cs:89-103, FullClick at full strength (maxValue 255): mode 0x02,
        // start 0x94 * 255/255 = 0x94, then (0xB4 - 0x94) * 255/255 + 0x94 = 0xB4, then 0xFF
        assertBytes("02 94 b4 ff 00 00 00 00 00 00 00", TriggerEffect.sectionResistance(0x94, 0xb4, 0xff));
    }

    @Test
    public void vibrationMatchesReWasdRifle() {
        // GIST reWASD Rifle(frequency 10), lines 1054-1072, prints the bytes outright:
        // 26 00 03 00 00 00 3F 00 00 0A 00. That's Vibration(position 8, amplitude 8, 10 Hz):
        // zones 8 and 9 on (0x0300), each with amplitude 8 - 1 = 7 in bits 24-26 and 27-29 (0x3F000000)
        assertBytes("26 00 03 00 00 00 3f 00 00 0a 00", TriggerEffect.vibration(8, 8, 10));
    }

    @Test
    public void vibrationWorkedFromTheGistLayout() {
        // GIST Vibration(), lines 250-281, position 0, amplitude 5, 30 Hz: all ten zones on (0x03FF);
        // 5 - 1 = 4 (0b100) in each 3-bit field: 0b100100...100 over 30 bits = 0x24924924;
        // frequency in byte 9
        assertBytes("26 ff 03 24 49 92 24 00 00 1e 00", TriggerEffect.vibration(0, 5, 30));
    }

    @Test
    public void feedbackWorkedFromTheGistLayout() {
        // GIST Feedback(), lines 165-196. Bytes 1-2: one bit per zone from the position on.
        // Bytes 3-6: (strength - 1) in three bits per zone, zone 0 lowest.
        // Position 0, strength 8: zones 0x03FF, 7 in all ten fields = 0x3FFFFFFF
        assertBytes("21 ff 03 ff ff ff 3f 00 00 00 00", TriggerEffect.feedback(0, 8));
        // Position 5, strength 1: zones 5-9 = 0x03E0, and 1 - 1 = 0 everywhere
        assertBytes("21 e0 03 00 00 00 00 00 00 00 00", TriggerEffect.feedback(5, 1));
        // Position 3, strength 4: zones 3-9 = 0x03F8; 3 at bits 9, 12, ... 27 = 0x1B6DB600
        assertBytes("21 f8 03 00 b6 6d 1b 00 00 00 00", TriggerEffect.feedback(3, 4));
    }

    @Test
    public void weaponWorkedFromTheGistLayout() {
        // GIST Weapon(), lines 210-238: bytes 1-2 have the start and end zone bits, byte 3 strength - 1.
        // Start 2, end 7, strength 8: (1 << 2) | (1 << 7) = 0x0084, 7
        assertBytes("25 84 00 07 00 00 00 00 00 00 00", TriggerEffect.weapon(2, 7, 8));
        // Start 4, end 6, strength 3: 0x0050, 2
        assertBytes("25 50 00 02 00 00 00 00 00 00 00", TriggerEffect.weapon(4, 6, 3));
    }

    @Test
    public void zeroStrengthGivesOffAsSonysApiDoes() {
        // GIST lines 124-126: parameters that would give no effect produce Off
        assertSame(TriggerEffect.off(), TriggerEffect.feedback(4, 0));
        assertSame(TriggerEffect.off(), TriggerEffect.weapon(2, 5, 0));
        assertSame(TriggerEffect.off(), TriggerEffect.vibration(0, 0, 30));
        assertSame(TriggerEffect.off(), TriggerEffect.vibration(0, 8, 0));
    }

    @Test
    public void outOfRangeArgumentsAreRefused() {
        // GIST's ranges: feedback position 0-9, strength 0-8; weapon start 2-7, end start+1 to 8
        expectRefused(() -> TriggerEffect.feedback(10, 1));
        expectRefused(() -> TriggerEffect.feedback(-1, 1));
        expectRefused(() -> TriggerEffect.feedback(0, 9));
        expectRefused(() -> TriggerEffect.weapon(1, 5, 4));
        expectRefused(() -> TriggerEffect.weapon(8, 9, 4));
        expectRefused(() -> TriggerEffect.weapon(5, 5, 4));
        expectRefused(() -> TriggerEffect.weapon(5, 9, 4));
        expectRefused(() -> TriggerEffect.vibration(0, 9, 10));
        expectRefused(() -> TriggerEffect.vibration(0, 4, 256));
        expectRefused(() -> TriggerEffect.continuousResistance(256, 0));
        expectRefused(() -> TriggerEffect.sectionResistance(0, 0, -1));
    }

    @Test
    public void hostEffectsPassThroughOnlyInTheKnownModes() {
        // SDL testcontroller.c:526-527, "Resistance and vibration when trigger is pulled":
        // the older mode 0x06 with 15, 63, 128. A host sends whatever the game wrote, so a known
        // mode passes as is.
        byte[] params = {15, 63, (byte) 128, 0, 0, 0, 0, 0, 0, 0};
        assertBytes("06 0f 3f 80 00 00 00 00 00 00 00", TriggerEffect.fromHost(0x06, params, 0));

        byte[] padded = new byte[14];
        System.arraycopy(params, 0, padded, 4, 10);
        assertEquals(TriggerEffect.fromHost(0x06, params, 0), TriggerEffect.fromHost(0x06, padded, 4));

        // GIST's TriggerEffectType: the official four, three unofficial ones, the older simple
        // and limited forms, then the debug modes 0xFC to 0xFE, which break the trigger until the
        // pad's reset button is pressed
        int[] known = {0x05, 0x21, 0x25, 0x26, 0x22, 0x23, 0x27, 0x01, 0x02, 0x06, 0x11, 0x12};
        int allowed = 0;
        for (int mode = 0; mode <= 0xFF; mode++) {
            boolean listed = false;
            for (int k : known) {
                listed |= k == mode;
            }
            assertEquals(String.format("mode 0x%02x", mode), listed, TriggerEffect.isKnownMode(mode));
            TriggerEffect effect = TriggerEffect.fromHost(mode, params, 0);
            if (listed) {
                allowed++;
                assertEquals(mode, effect.mode());
            }
            else {
                assertSame(String.format("mode 0x%02x", mode), TriggerEffect.off(), effect);
            }
        }
        assertEquals(12, allowed);
        assertTrue("only the low byte counts", TriggerEffect.isKnownMode(0x121));
    }

    @Test
    public void effectsCantBeChangedFromOutside() {
        TriggerEffect effect = TriggerEffect.weapon(2, 7, 8);
        byte[] copy = effect.toBytes();
        copy[3] = 0;
        assertArrayEquals(Hex.bytes("2584000700000000000000"), effect.toBytes());

        byte[] params = new byte[10];
        TriggerEffect fromHost = TriggerEffect.fromHost(0x21, params, 0);
        params[0] = 0x7F;
        assertEquals(0, fromHost.toBytes()[1]);
    }

    private static void expectRefused(Runnable call) {
        try {
            call.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // refused, as it should be
        }
    }
}
