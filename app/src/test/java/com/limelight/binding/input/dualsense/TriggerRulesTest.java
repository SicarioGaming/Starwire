package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The preset rules: a preset plays until the game sends its first effect, the game wins after
 * that, and Off beats both. Also the presets' own bytes and the strength steps.
 */
public class TriggerRulesTest {
    private static HostTriggerMessage game(int triggers, int typeLeft, String left, int typeRight, String right) {
        return HostTriggerMessage.fromCallback(0, triggers, typeLeft, typeRight, Hex.bytes(left), 0, Hex.bytes(right), 0);
    }

    private static final String ZEROS = "00000000000000000000";
    // weapon(2, 6, 8) and feedback(3, 6) as a game sends them (DualSenseHostTriggerMessageTest)
    private static final String WEAPON = "44000700000000000000";
    private static final String FEEDBACK = "f80300dab62d00000000";

    @Test
    public void thePresetPlaysUntilTheGameSendsAnEffect() {
        TriggerRules rules = new TriggerRules(TriggerPreset.GUN_CLICK);
        assertEquals(TriggerPreset.GUN_CLICK.effect(), rules.right());
        assertEquals(TriggerPreset.GUN_CLICK.effect(), rules.left());
        assertFalse(rules.gameTookOver());

        rules.onGameMessage(game(HostTriggerMessage.RIGHT_TRIGGER, 0, ZEROS, 0x25, WEAPON));
        assertTrue(rules.gameTookOver());
        assertEquals(TriggerEffect.weapon(2, 6, 8), rules.right());
        // The game drives the triggers now, so the preset leaves the other one too
        assertEquals(TriggerEffect.off(), rules.left());
    }

    @Test
    public void thePresetNeverComesBackInTheStream() {
        TriggerRules rules = new TriggerRules(TriggerPreset.BUZZ);
        rules.onGameMessage(game(0x0C, 0x21, FEEDBACK, 0x25, WEAPON));
        // The game turns its effects off: off it is, not the buzz
        rules.onGameMessage(game(0x0C, 0x05, ZEROS, 0x05, ZEROS));
        assertEquals(TriggerEffect.off(), rules.right());
        assertEquals(TriggerEffect.off(), rules.left());
    }

    @Test
    public void aMessageChangesOnlyTheTriggersItNames() {
        TriggerRules rules = new TriggerRules(TriggerPreset.NONE);
        rules.onGameMessage(game(0x0C, 0x21, FEEDBACK, 0x25, WEAPON));
        rules.onGameMessage(game(HostTriggerMessage.LEFT_TRIGGER, 0x05, ZEROS, 0x26, "ffffffffffffffffffff"));
        assertEquals("right kept", TriggerEffect.weapon(2, 6, 8), rules.right());
        assertEquals(TriggerEffect.off(), rules.left());
    }

    @Test
    public void aMessageThatNamesNoTriggerChangesNothing() {
        // Neither bit set: the game hasn't set a trigger, so the preset plays on
        TriggerRules rules = new TriggerRules(TriggerPreset.GUN_CLICK);
        rules.onGameMessage(game(0x00, 0x21, FEEDBACK, 0x25, WEAPON));
        rules.onGameMessage(game(0xF3, 0x21, FEEDBACK, 0x25, WEAPON));
        assertFalse(rules.gameTookOver());
        assertEquals(TriggerPreset.GUN_CLICK.effect(), rules.right());
        assertEquals(TriggerPreset.GUN_CLICK.effect(), rules.left());
    }

    @Test
    public void offBeatsTheGameAndThePreset() {
        TriggerRules rules = new TriggerRules(TriggerPreset.OFF);
        rules.onGameMessage(game(0x0C, 0x21, FEEDBACK, 0x25, WEAPON));
        assertFalse(rules.gameTookOver());
        assertEquals(TriggerEffect.off(), rules.right());
        assertEquals(TriggerEffect.off(), rules.left());
    }

    @Test
    public void noPresetMeansTriggersOffUntilTheGameSendsOne() {
        TriggerRules rules = new TriggerRules(TriggerPreset.NONE);
        assertNull(TriggerPreset.NONE.effect());
        assertEquals(TriggerEffect.off(), rules.right());
        assertEquals(TriggerEffect.off(), rules.left());
    }

    @Test
    public void theGamesDebugModesBecomeOff() {
        // 0xFC to 0xFE leave a trigger stuck until the pad is reset (Nielk1's gist)
        for (int mode = 0xFC; mode <= 0xFE; mode++) {
            TriggerRules rules = new TriggerRules(TriggerPreset.NONE);
            rules.onGameMessage(game(0x0C, mode, "01020304050607080900", mode, "01020304050607080900"));
            assertEquals(TriggerEffect.off(), rules.right());
            assertEquals(TriggerEffect.off(), rules.left());
        }
    }

    @Test
    public void presetsUseOnlyTheOfficialModes() {
        for (TriggerPreset preset : TriggerPreset.values()) {
            TriggerEffect effect = preset.effect();
            if (effect == null) {
                continue;
            }
            int mode = effect.mode();
            assertTrue(preset + " sends mode " + mode, TriggerEffect.isSafeMode(mode));
            assertTrue(preset + " sends mode " + mode, mode == TriggerEffect.MODE_OFF || mode == TriggerEffect.MODE_FEEDBACK
                    || mode == TriggerEffect.MODE_WEAPON || mode == TriggerEffect.MODE_VIBRATION);
        }
    }

    @Test
    public void presetBytes() {
        // Worked out by hand from the gist's layout, as in DualSenseTriggerEffectTest: feedback has a
        // bit per zone from the start zone (bytes 1-2) and strength - 1 in three bits per zone (3-6);
        // weapon has the start and end zone bits and strength - 1; vibration adds the frequency at 9.
        // Rechecked with a separate Perl script.
        // feedback(3, 3): zones 3-9 (0x03f8), 2 in each zone's three bits (0x12492400)
        assertEquals("21f8030024491200000000", hex(TriggerPreset.LIGHT_RESISTANCE));
        // weapon(4, 6, 6): zones 4 and 6 (0x0050), 6 - 1
        assertEquals("2550000500000000000000", hex(TriggerPreset.GUN_CLICK));
        // feedback(1, 7): zones 1-9 (0x03fe), 6 in each (0x36db6db0)
        assertEquals("21fe03b06ddb3600000000", hex(TriggerPreset.HEAVY_PEDAL));
        // vibration(2, 5, 40): zones 2-9 (0x03fc), 4 in each (0x24924900), 40 a second (0x28)
        assertEquals("26fc030049922400002800", hex(TriggerPreset.BUZZ));
        assertEquals("0500000000000000000000", hex(TriggerPreset.OFF));
    }

    @Test
    public void presetsBySavedValue() {
        for (TriggerPreset preset : TriggerPreset.values()) {
            assertSame(preset, TriggerPreset.fromPref(preset.prefValue));
        }
        assertSame(TriggerPreset.NONE, TriggerPreset.fromPref(null));
        assertSame(TriggerPreset.NONE, TriggerPreset.fromPref("pulse_with_bass"));
    }

    @Test
    public void strengthSteps() {
        assertEquals(0, TriggerStrength.powerCut(8));
        assertEquals(1, TriggerStrength.powerCut(7));
        assertEquals(7, TriggerStrength.powerCut(1));
        assertEquals("never past what DualSenseOutput allows", DualSenseOutput.MAX_POWER_CUT, TriggerStrength.powerCut(-3));
        assertEquals(0, TriggerStrength.powerCut(99));
        assertEquals(8, TriggerStrength.fromPref("8"));
        assertEquals(3, TriggerStrength.fromPref("3"));
        assertEquals(8, TriggerStrength.fromPref(null));
        assertEquals(8, TriggerStrength.fromPref("full"));
        assertEquals(1, TriggerStrength.fromPref("0"));
    }

    private static String hex(TriggerPreset preset) {
        return Hex.of(preset.effect().toBytes());
    }
}
