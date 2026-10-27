package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The arithmetic behind "Controller vibration strength". Motor values are unsigned 16-bit numbers
 * carried in Java's signed short, so 0xFFFF is the strongest rumble, not minus one.
 * ControllerRumbleStrengthTest checks that a stream applies it.
 */
public class RumbleStrengthTest {
    private static final int[] MOTORS = {0x0000, 0x0001, 0x00FF, 0x0100, 0x1234, 0x7FFF, 0x8000, 0xFFFF};

    private static int scaled(int motor, int percent) {
        return RumbleStrength.scale((short) motor, percent) & 0xFFFF;
    }

    @Test
    public void fullStrengthLeavesRumbleAlone() {
        for (int motor : MOTORS) {
            assertEquals(Integer.toHexString(motor), motor, scaled(motor, 100));
        }
    }

    @Test
    public void zeroStrengthTurnsRumbleOff() {
        for (int motor : MOTORS) {
            assertEquals(Integer.toHexString(motor), 0, scaled(motor, 0));
        }
    }

    @Test
    public void halfStrengthHalvesRumble() {
        assertEquals(0x8000, scaled(0xFFFF, 50));
        assertEquals(0x4000, scaled(0x8000, 50));
        assertEquals(0x4000, scaled(0x7FFF, 50));
        assertEquals(0x0080, scaled(0x0100, 50));
        assertEquals(0x091A, scaled(0x1234, 50));
        assertEquals(0, scaled(0, 50));
    }

    // To the nearest whole value, halves up
    @Test
    public void roundsToTheNearestValue() {
        assertEquals(1, scaled(1, 50));          // 0.5
        assertEquals(0, scaled(1, 49));          // 0.49
        assertEquals(2, scaled(3, 50));          // 1.5
        assertEquals(655, scaled(0xFFFF, 1));    // 655.35
        assertEquals(64880, scaled(0xFFFF, 99)); // 64879.65
        assertEquals(49151, scaled(0xFFFF, 75)); // 49151.25
        assertEquals(16384, scaled(0xFFFF, 25)); // 16383.75
    }

    // The slider stops at 0 and 100, but a value saved some other way can't make rumble
    // stronger than the game asked for, or wrap around
    @Test
    public void outOfRangeStrengthsCountAsTheNearestEnd() {
        assertEquals(0xFFFF, scaled(0xFFFF, 101));
        assertEquals(0x1234, scaled(0x1234, Integer.MAX_VALUE));
        assertEquals(0, scaled(0xFFFF, -1));
        assertEquals(0, scaled(0xFFFF, Integer.MIN_VALUE));
    }
}
