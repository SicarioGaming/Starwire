package com.limelight.binding.input.dualsense;

import android.os.SystemClock;

import com.limelight.LimeLog;

/**
 * A log line for each change in a game's trigger effects during a stream, so a screen recording
 * can be matched to the effects the game really sent. The lines go through LimeLog to logcat:
 * <pre>
 * DualSense effect: t=12345ms R2 mode=0x25 params=44000700000000000000 sent
 * </pre>
 * That's the time since the stream started (when DualSenseBluetooth.forStream made this, as the
 * stream screen opens), the trigger (R2 or L2), its mode and ten parameter bytes in hex as the
 * host sent them (the mode with 0x, so 25 can't be read as decimal), and what Starwire did with
 * the effect:
 * <ul>
 * <li>{@code sent}: passed on to the pad. Messages that come faster than one send go out as one
 * report with the latest effects, and a send Android refuses gets its own warning line.</li>
 * <li>{@code held (no pad ready)}: no pad takes effects right now (still checking, two pads, or
 * a check failed). The effect is kept, and goes out if a pad passes its checks later on.</li>
 * <li>{@code dropped (unknown mode, trigger off)}: a mode TriggerEffect.fromHost doesn't pass on,
 * so the trigger goes off instead.</li>
 * <li>{@code replaced by preset off}: the Off preset ignores the game's effects
 * (TriggerRules).</li>
 * </ul>
 * A line that would repeat a trigger's last one isn't logged, so a game that sends the same effect
 * every frame logs it once. At most 10 lines go out in a second, counted from the second's first
 * line. Past that, changes are only counted. With the next change after that second, or when the
 * stream ends, one line says how many weren't logged, and each trigger's last change in that time
 * is logged with its own time. So the log always ends on what each trigger got last.
 *
 * Nothing about the pad, the PC or the player goes in. DualSenseBluetooth makes one only for a
 * stream with the DualSense switch on. Plain Java and not thread-safe: DualSenseBluetooth calls it
 * on its worker thread.
 */
public final class TriggerEffectLog {
    /** What every line starts with, to filter logcat on. */
    public static final String PREFIX = "DualSense effect: ";
    /** The most lines in a second, besides the one that counts the changes left out. */
    public static final int MAX_LINES_A_SECOND = 10;

    private static final long SECOND_MS = 1000;
    private static final int RIGHT = 0;
    private static final int LEFT = 1;
    private static final String[] TRIGGER_NAMES = {"R2", "L2"};

    /** Where the lines go: LimeLog in the app, a list in the tests. */
    interface Sink {
        void line(String text);
    }

    private final long startMs;
    private final Sink sink;
    private final String replaced;
    // Each trigger's last line logged, without its time
    private final String[] logged = new String[2];
    // Each trigger's last change the limit held back, and when it came, or null
    private final String[] heldBack = new String[2];
    private final long[] heldBackMs = new long[2];
    private int heldBackCount;
    private long firstHeldBackMs;
    // The second the limit counts in, from its first line
    private boolean secondStarted;
    private long secondStartMs;
    private int linesThisSecond;

    /** For a stream: lines to LimeLog, timed from now on SystemClock.elapsedRealtime(). */
    static TriggerEffectLog forStream(TriggerPreset preset) {
        return new TriggerEffectLog(SystemClock.elapsedRealtime(), LimeLog::info, preset);
    }

    /**
     * @param startMs when the stream started, on the same clock as the times given later
     * @param preset  the stream's trigger preset
     */
    TriggerEffectLog(long startMs, Sink sink, TriggerPreset preset) {
        this.startMs = startMs;
        this.sink = sink;
        this.replaced = "replaced by preset " + preset.prefValue;
    }

    /**
     * The host's message for the stream's DualSense, after TriggerRules took it. Logs each trigger
     * it names, if that trigger's line changed.
     *
     * @param atMs         when the message came, on the start's clock
     * @param triggers     which triggers it names: HostTriggerMessage.RIGHT_TRIGGER, LEFT_TRIGGER
     * @param left         the left trigger's ten parameter bytes as the host sent them
     * @param right        the right trigger's, the same way
     * @param gameTookOver TriggerRules.gameTookOver() after the message; false means a preset plays
     *                     instead of the game's effects
     * @param padReady     a pad takes effects now, so this one goes out
     */
    void onGameMessage(long atMs, int triggers, int typeLeft, int typeRight, byte[] left, byte[] right,
                       boolean gameTookOver, boolean padReady) {
        if ((triggers & HostTriggerMessage.RIGHT_TRIGGER) != 0) {
            onEffect(atMs, RIGHT, typeRight, right, gameTookOver, padReady);
        }
        if ((triggers & HostTriggerMessage.LEFT_TRIGGER) != 0) {
            onEffect(atMs, LEFT, typeLeft, left, gameTookOver, padReady);
        }
    }

    /** The stream ended: logs what the limit held back, if anything. */
    void finish() {
        logHeldBack();
    }

    private void onEffect(long atMs, int trigger, int mode, byte[] parameters, boolean gameTookOver,
                          boolean padReady) {
        String line = TRIGGER_NAMES[trigger] + " mode=0x" + hex(mode) + " params=" + hex(parameters)
                + " " + outcome(mode, gameTookOver, padReady);
        String last = heldBack[trigger] != null ? heldBack[trigger] : logged[trigger];
        if (line.equals(last)) {
            return;
        }
        if (!secondStarted || atMs - secondStartMs >= SECOND_MS) {
            secondStarted = true;
            secondStartMs = atMs;
            linesThisSecond = 0;
            logHeldBack();
        }
        if (linesThisSecond < MAX_LINES_A_SECOND) {
            write(atMs, trigger, line);
            return;
        }
        if (heldBackCount == 0) {
            firstHeldBackMs = atMs;
        }
        heldBackCount++;
        heldBack[trigger] = line;
        heldBackMs[trigger] = atMs;
    }

    private String outcome(int mode, boolean gameTookOver, boolean padReady) {
        if (!gameTookOver) {
            return replaced;
        }
        if (!TriggerEffect.isKnownMode(mode)) {
            return "dropped (unknown mode, trigger off)";
        }
        return padReady ? "sent" : "held (no pad ready)";
    }

    // How many changes went unlogged, then each trigger's last held-back change, oldest first,
    // unless the trigger ended up where its last line says
    private void logHeldBack() {
        if (heldBackCount == 0) {
            return;
        }
        boolean right = heldBack[RIGHT] != null && !heldBack[RIGHT].equals(logged[RIGHT]);
        boolean left = heldBack[LEFT] != null && !heldBack[LEFT].equals(logged[LEFT]);
        int lost = heldBackCount - (right ? 1 : 0) - (left ? 1 : 0);
        if (lost > 0) {
            sink.line(PREFIX + "t=" + (firstHeldBackMs - startMs) + "ms " + lost
                    + (lost == 1 ? " change" : " changes") + " not logged, more than "
                    + MAX_LINES_A_SECOND + " in a second");
        }
        boolean leftFirst = left && right && heldBackMs[LEFT] < heldBackMs[RIGHT];
        if (leftFirst) {
            write(heldBackMs[LEFT], LEFT, heldBack[LEFT]);
        }
        if (right) {
            write(heldBackMs[RIGHT], RIGHT, heldBack[RIGHT]);
        }
        if (left && !leftFirst) {
            write(heldBackMs[LEFT], LEFT, heldBack[LEFT]);
        }
        heldBack[RIGHT] = null;
        heldBack[LEFT] = null;
        heldBackCount = 0;
    }

    private void write(long atMs, int trigger, String line) {
        sink.line(PREFIX + "t=" + (atMs - startMs) + "ms " + line);
        logged[trigger] = line;
        heldBack[trigger] = null;
        linesThisSecond++;
    }

    private static String hex(int value) {
        return new String(new char[] {Character.forDigit((value >> 4) & 0xF, 16), Character.forDigit(value & 0xF, 16)});
    }

    private static String hex(byte[] parameters) {
        StringBuilder s = new StringBuilder(2 * TriggerEffect.PARAMETER_SIZE);
        for (int i = 0; i < TriggerEffect.PARAMETER_SIZE; i++) {
            s.append(hex(parameters[i]));
        }
        return s.toString();
    }
}
