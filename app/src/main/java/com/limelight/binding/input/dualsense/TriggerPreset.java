package com.limelight.binding.input.dualsense;

/**
 * Starwire's own trigger effects, for games that send none (Diablo IV on PC, a host that isn't
 * emulating a DualSense, any game before its first effect). The game's own effects always win, and
 * OFF turns every trigger effect off, the game's too.
 *
 * Each preset uses only the official modes (feedback, weapon, vibration), never the firmware's
 * debug modes 0xFC to 0xFE. The positions are zones 0 (at rest) to 9 (fully pulled), and the
 * strengths 1 to 8, as in TriggerEffect. The values were picked from the descriptions in Nielk1's
 * TriggerEffectGenerator gist; nobody has felt them on a pad yet.
 */
public enum TriggerPreset {
    /** The game's effects only. Nothing plays when the game sends none. */
    NONE("none", null),
    /** Light resistance from about a third of the pull. */
    LIGHT_RESISTANCE("light_resistance", TriggerEffect.feedback(3, 3)),
    /** A gun trigger: resistance that gives way with a click about halfway down. */
    GUN_CLICK("gun_click", TriggerEffect.weapon(4, 6, 6)),
    /** Heavy resistance from near the top, like a car's pedal. */
    HEAVY_PEDAL("heavy_pedal", TriggerEffect.feedback(1, 7)),
    /** The trigger buzzes once it's pulled past a fifth of the way. */
    BUZZ("buzz", TriggerEffect.vibration(2, 5, 40)),
    /** No trigger effects at all, not even the game's. */
    OFF("off", TriggerEffect.off());

    /** The value saved in the settings. */
    public final String prefValue;
    private final TriggerEffect effect;

    TriggerPreset(String prefValue, TriggerEffect effect) {
        this.prefValue = prefValue;
        this.effect = effect;
    }

    /** What the preset puts on each trigger, or null for NONE, which puts nothing there. */
    public TriggerEffect effect() {
        return effect;
    }

    /** The preset saved under this value, or NONE for anything else. */
    public static TriggerPreset fromPref(String value) {
        for (TriggerPreset preset : values()) {
            if (preset.prefValue.equals(value)) {
                return preset;
            }
        }
        return NONE;
    }
}
