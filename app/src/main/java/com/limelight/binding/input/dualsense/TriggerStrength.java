package com.limelight.binding.input.dualsense;

/**
 * The trigger strength setting, 8 steps from Full (8) to Lightest (1). It's the pad's own limit
 * on trigger motor power, which DualSenseOutput.setMotorPowerCut sends: Full cuts nothing, and
 * each step down cuts 12.5% more, so Lightest leaves 12.5%.
 *
 * DS4Windows says the cut weakens only the repeating effects (vibration) and leaves plain
 * resistance alone. Nobody has felt it on a pad for Starwire yet, so the settings text says the
 * pad decides.
 */
public final class TriggerStrength {
    public static final int FULL = 8;
    public static final int LIGHTEST = 1;

    private TriggerStrength() {
    }

    /** The saved setting as a step from 1 to 8. Anything unreadable is Full. */
    public static int fromPref(String value) {
        try {
            return clamp(Integer.parseInt(value));
        } catch (NumberFormatException | NullPointerException e) {
            return FULL;
        }
    }

    /** The pad's trigger power cut for a step: 0 (none) for Full to 7 for Lightest. */
    public static int powerCut(int step) {
        return FULL - clamp(step);
    }

    private static int clamp(int step) {
        return Math.max(LIGHTEST, Math.min(FULL, step));
    }
}
