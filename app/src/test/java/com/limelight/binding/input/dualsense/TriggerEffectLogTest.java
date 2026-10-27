package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The log line for each change in a game's trigger effects: what it says, that a repeat isn't
 * logged again, and the limit of 10 lines a second. Times are given, not read from a clock.
 */
public class TriggerEffectLogTest {
    private static final int RIGHT = HostTriggerMessage.RIGHT_TRIGGER;
    private static final int LEFT = HostTriggerMessage.LEFT_TRIGGER;
    private static final int BOTH = RIGHT | LEFT;
    // weapon(2, 6, 8) and feedback(3, 6) as a game sends them (DualSenseHostTriggerMessageTest)
    private static final byte[] WEAPON = Hex.bytes("44000700000000000000");
    private static final byte[] FEEDBACK = Hex.bytes("f80300dab62d00000000");
    private static final byte[] ZEROS = new byte[TriggerEffect.PARAMETER_SIZE];
    private static final long START = 5000;

    private final List<String> lines = new ArrayList<>();
    private final TriggerEffectLog log = new TriggerEffectLog(START, lines::add, TriggerPreset.NONE);

    // At this many ms into the stream: right weapon, left feedback, with the pad ready
    private void gameSends(long ms) {
        log.onGameMessage(START + ms, BOTH, 0x21, 0x25, FEEDBACK, WEAPON, true, true);
    }

    // Older continuous resistance on R2 (mode 0x01), from rest, with this force
    private void rightResistance(long ms, int force) {
        byte[] parameters = new byte[TriggerEffect.PARAMETER_SIZE];
        parameters[1] = (byte) force;
        log.onGameMessage(START + ms, RIGHT, 0, 0x01, ZEROS, parameters, true, true);
    }

    private static String resistanceLine(long ms, int force) {
        return String.format("DualSense effect: t=%dms R2 mode=0x01 params=00%02x0000000000000000 sent", ms, force);
    }

    @Test
    public void aLineSaysWhenWhichTriggerWhatAndWhatHappened() {
        gameSends(12345);
        assertEquals(Arrays.asList(
                "DualSense effect: t=12345ms R2 mode=0x25 params=44000700000000000000 sent",
                "DualSense effect: t=12345ms L2 mode=0x21 params=f80300dab62d00000000 sent"), lines);
    }

    @Test
    public void itSaysWhenAnEffectWasHeldDroppedOrReplaced() {
        // No pad ready yet: Starwire keeps it for when one is
        log.onGameMessage(START + 100, RIGHT, 0, 0x25, ZEROS, WEAPON, true, false);
        // A firmware debug mode as the host passed it on: the trigger goes off instead
        byte[] debug = Hex.bytes("0102030405060708090a");
        log.onGameMessage(START + 200, LEFT, 0xFC, 0, debug, ZEROS, true, true);
        // The Off preset ignores the game, whatever the mode
        TriggerEffectLog off = new TriggerEffectLog(0, lines::add, TriggerPreset.OFF);
        off.onGameMessage(300, BOTH, 0xFC, 0x25, debug, WEAPON, false, true);
        assertEquals(Arrays.asList(
                "DualSense effect: t=100ms R2 mode=0x25 params=44000700000000000000 held (no pad ready)",
                "DualSense effect: t=200ms L2 mode=0xfc params=0102030405060708090a dropped (unknown mode, trigger off)",
                "DualSense effect: t=300ms R2 mode=0x25 params=44000700000000000000 replaced by preset off",
                "DualSense effect: t=300ms L2 mode=0xfc params=0102030405060708090a replaced by preset off"), lines);
    }

    @Test
    public void onlyTheTriggersAMessageNamesAreLogged() {
        log.onGameMessage(START, RIGHT, 0x21, 0x25, FEEDBACK, WEAPON, true, true);
        log.onGameMessage(START + 10, 0, 0x26, 0x26, FEEDBACK, FEEDBACK, true, true);
        assertEquals(Arrays.asList(
                "DualSense effect: t=0ms R2 mode=0x25 params=44000700000000000000 sent"), lines);
    }

    @Test
    public void aRepeatIsNotLoggedAgain() {
        // Two seconds of a game sending the same effects every frame
        for (int frame = 0; frame < 120; frame++) {
            gameSends(frame * 16L);
        }
        assertEquals(2, lines.size());

        // Only L2 changes, so only L2 is logged
        log.onGameMessage(START + 2000, BOTH, 0x05, 0x25, ZEROS, WEAPON, true, true);
        // Back to the first effect: that's a change too
        gameSends(2100);
        assertEquals(Arrays.asList(
                "DualSense effect: t=2000ms L2 mode=0x05 params=00000000000000000000 sent",
                "DualSense effect: t=2100ms L2 mode=0x21 params=f80300dab62d00000000 sent"), lines.subList(2, 4));

        // The same effect with another outcome is a new line: the pad went away
        log.onGameMessage(START + 2200, RIGHT, 0, 0x25, ZEROS, WEAPON, true, false);
        assertEquals("DualSense effect: t=2200ms R2 mode=0x25 params=44000700000000000000 held (no pad ready)",
                lines.get(4));
        assertEquals(5, lines.size());
    }

    @Test
    public void aFloodIsCutToTenLinesASecond() {
        // A new R2 force every 10 ms, 30 in all
        for (int force = 0; force < 30; force++) {
            rightResistance(10L * force, force);
        }
        assertEquals(TriggerEffectLog.MAX_LINES_A_SECOND, lines.size());
        assertEquals(resistanceLine(90, 9), lines.get(9));

        // The next change, once the second is up: first what the limit held back, then the change
        log.onGameMessage(START + 1500, LEFT, 0x21, 0, FEEDBACK, ZEROS, true, true);
        assertEquals(Arrays.asList(
                "DualSense effect: t=100ms 19 changes not logged, more than 10 in a second",
                resistanceLine(290, 29),
                "DualSense effect: t=1500ms L2 mode=0x21 params=f80300dab62d00000000 sent"),
                lines.subList(10, lines.size()));

        // A new second has its own 10 lines, and the held-back line counted in it
        for (int force = 30; force < 45; force++) {
            rightResistance(1500 + force, force);
        }
        assertEquals(13 + 8, lines.size());
    }

    @Test
    public void aFloodThatEndsWhereTheLogIsLeavesOnlyTheCount() {
        // A game switching between two effects every 5 ms
        for (int i = 0; i < 20; i++) {
            rightResistance(5L * i, i % 2 == 0 ? 3 : 4);
        }
        log.finish();
        assertEquals(11, lines.size());
        assertEquals("the last line logged is where R2 ended up", resistanceLine(45, 4), lines.get(9));
        assertEquals("DualSense effect: t=50ms 10 changes not logged, more than 10 in a second", lines.get(10));
    }

    @Test
    public void theEndOfTheStreamLogsWhatTheLimitHeldBack() {
        for (int force = 0; force < 12; force++) {
            rightResistance(force, force);
        }
        log.onGameMessage(START + 12, LEFT, 0x21, 0, FEEDBACK, ZEROS, true, true);
        assertEquals(10, lines.size());

        // Three were held back: the last one for each trigger is logged, so one is lost
        log.finish();
        assertEquals(Arrays.asList(
                "DualSense effect: t=10ms 1 change not logged, more than 10 in a second",
                resistanceLine(11, 11),
                "DualSense effect: t=12ms L2 mode=0x21 params=f80300dab62d00000000 sent"),
                lines.subList(10, lines.size()));

        log.finish();
        assertEquals("nothing more the second time", 13, lines.size());
    }
}
