package com.limelight.binding.input;

/**
 * The "Controller vibration strength" setting. ControllerHandler scales every rumble value from
 * the PC by it before a controller, or this device standing in for one, plays it. 100 percent
 * leaves rumble as the game sent it, and 0 turns it off.
 */
public final class RumbleStrength {
    private RumbleStrength() {}

    /**
     * A motor value from the PC (0 to 65535, carried in a short) scaled by percent and rounded to
     * the nearest whole value, halves up. A percent below 0 counts as 0 and one above 100 as 100.
     */
    public static short scale(short motor, int percent) {
        if (percent >= 100) {
            return motor;
        }
        if (percent <= 0) {
            return 0;
        }
        return (short) (((motor & 0xFFFF) * percent + 50) / 100);
    }
}
