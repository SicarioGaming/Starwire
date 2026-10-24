package com.limelight.binding.input.dualsense;

import java.util.Arrays;

/**
 * One adaptive trigger's program: a mode byte and ten parameter bytes. The DualSense's output
 * report has one of these blocks for each trigger, and the pad keeps running it until it gets
 * another.
 *
 * The official modes (off, feedback, weapon, vibration) split the trigger's travel into ten zones,
 * 0 near rest to 9 fully pulled, and take strengths from 1 to 8. Their bit layout comes from
 * Nielk1's TriggerEffectGenerator (gist 6d54cc2c00d2201ccb8c2720ad7538db, revision 6, MIT), which
 * worked it out from Apple's and Steamworks' DualSense APIs. The two older modes, continuous and
 * section resistance, take raw 0 to 255 positions and forces. They're the ones DualSense-Windows,
 * DS4Windows, reWASD and SDL's test program send. The code here is written fresh from those
 * descriptions; none of it is copied.
 *
 * Immutable. The factories check their arguments and throw IllegalArgumentException when one is out
 * of range. As Sony's own API does (per the gist), a strength or amplitude of 0 gives Off.
 */
public final class TriggerEffect {
    /** Bytes in a trigger block: the mode, then ten parameters. */
    public static final int SIZE = 11;
    /** Parameter bytes after the mode. */
    public static final int PARAMETER_SIZE = 10;

    /** No effect: the trigger moves freely. */
    public static final int MODE_OFF = 0x05;
    /** Resistance from a zone onwards ("continuous resistance" in the newer, official form). */
    public static final int MODE_FEEDBACK = 0x21;
    /** Resistance between two zones that gives way, like a gun's trigger ("section resistance"). */
    public static final int MODE_WEAPON = 0x25;
    /** The trigger buzzes from a zone onwards. */
    public static final int MODE_VIBRATION = 0x26;
    /** The older continuous resistance: a start position and a force, 0 to 255 each. */
    public static final int MODE_SIMPLE_FEEDBACK = 0x01;
    /** The older section resistance: start and end positions and a force, 0 to 255 each. */
    public static final int MODE_SIMPLE_WEAPON = 0x02;

    private static final int ZONES = 10;
    private static final int MAX_STRENGTH = 8;

    private static final TriggerEffect OFF = new TriggerEffect(blank(MODE_OFF));

    private final byte[] bytes;

    private TriggerEffect(byte[] bytes) {
        this.bytes = bytes;
    }

    /** The trigger moves freely, with nothing in its way. */
    public static TriggerEffect off() {
        return OFF;
    }

    /**
     * Resistance from a zone to the end of the pull.
     *
     * @param position the first zone with resistance, 0 to 9
     * @param strength 0 to 8, where 0 gives Off
     */
    public static TriggerEffect feedback(int position, int strength) {
        check("position", position, 0, ZONES - 1);
        check("strength", strength, 0, MAX_STRENGTH);
        if (strength == 0) {
            return OFF;
        }
        return new TriggerEffect(zoned(MODE_FEEDBACK, position, strength));
    }

    /**
     * Resistance that starts at one zone and gives way at a later one, like a gun's trigger.
     *
     * @param start the zone where resistance starts, 2 to 7
     * @param end   the zone where it gives way, start + 1 to 8
     * @param strength 0 to 8, where 0 gives Off
     */
    public static TriggerEffect weapon(int start, int end, int strength) {
        check("start", start, 2, 7);
        check("end", end, start + 1, 8);
        check("strength", strength, 0, MAX_STRENGTH);
        if (strength == 0) {
            return OFF;
        }
        byte[] b = blank(MODE_WEAPON);
        int zones = (1 << start) | (1 << end);
        b[1] = (byte) zones;
        b[2] = (byte) (zones >>> 8);
        b[3] = (byte) (strength - 1);
        return new TriggerEffect(b);
    }

    /**
     * The trigger buzzes from a zone to the end of the pull.
     *
     * @param position  the first zone that buzzes, 0 to 9
     * @param amplitude 0 to 8, where 0 gives Off
     * @param frequency buzzes a second, 0 to 255, where 0 gives Off
     */
    public static TriggerEffect vibration(int position, int amplitude, int frequency) {
        check("position", position, 0, ZONES - 1);
        check("amplitude", amplitude, 0, MAX_STRENGTH);
        check("frequency", frequency, 0, 255);
        if (amplitude == 0 || frequency == 0) {
            return OFF;
        }
        byte[] b = zoned(MODE_VIBRATION, position, amplitude);
        b[9] = (byte) frequency;
        return new TriggerEffect(b);
    }

    /**
     * The older continuous resistance (mode 0x01): resistance from a position onwards.
     *
     * @param start where resistance starts, 0 (rest) to 255
     * @param force how hard, 0 to 255
     */
    public static TriggerEffect continuousResistance(int start, int force) {
        check("start", start, 0, 255);
        check("force", force, 0, 255);
        byte[] b = blank(MODE_SIMPLE_FEEDBACK);
        b[1] = (byte) start;
        b[2] = (byte) force;
        return new TriggerEffect(b);
    }

    /**
     * The older section resistance (mode 0x02): resistance between two positions.
     *
     * @param start where resistance starts, 0 (rest) to 255
     * @param end   where it ends, 0 to 255
     * @param force how hard, 0 to 255
     */
    public static TriggerEffect sectionResistance(int start, int end, int force) {
        check("start", start, 0, 255);
        check("end", end, 0, 255);
        check("force", force, 0, 255);
        byte[] b = blank(MODE_SIMPLE_WEAPON);
        b[1] = (byte) start;
        b[2] = (byte) end;
        b[3] = (byte) force;
        return new TriggerEffect(b);
    }

    /**
     * A block exactly as a host sent it, which is how games' own effects arrive. A host copies the
     * game's bytes without looking at them, so any mode can come in. The firmware's debug modes,
     * 0xFC to 0xFE, leave a trigger broken until the pad is reset (per the gist), so those become
     * Off. Any other mode passes through as sent.
     *
     * @param parameters ten parameter bytes, read from offset
     */
    public static TriggerEffect fromHost(int mode, byte[] parameters, int offset) {
        int m = mode & 0xFF;
        if (!isSafeMode(m)) {
            return OFF;
        }
        byte[] b = new byte[SIZE];
        b[0] = (byte) m;
        System.arraycopy(parameters, offset, b, 1, PARAMETER_SIZE);
        return new TriggerEffect(b);
    }

    /** False for the firmware's debug modes, 0xFC to 0xFE, which Starwire must never send. */
    public static boolean isSafeMode(int mode) {
        int m = mode & 0xFF;
        return m < 0xFC || m > 0xFE;
    }

    /** The mode byte, 0 to 255. */
    public int mode() {
        return bytes[0] & 0xFF;
    }

    /** A copy of the 11 bytes: the mode, then the ten parameters. */
    public byte[] toBytes() {
        return bytes.clone();
    }

    /** Copies the 11 bytes into a report. */
    void writeTo(byte[] destination, int offset) {
        System.arraycopy(bytes, 0, destination, offset, SIZE);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TriggerEffect && Arrays.equals(bytes, ((TriggerEffect) other).bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("TriggerEffect[");
        for (byte b : bytes) {
            s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return s.append(']').toString();
    }

    private static byte[] blank(int mode) {
        byte[] b = new byte[SIZE];
        b[0] = (byte) mode;
        return b;
    }

    // Feedback and vibration: bytes 1 and 2 have one bit per zone that's on (zone 0 is bit 0),
    // bytes 3 to 6 have three bits per zone with the strength minus one (zone 0 is bits 0 to 2).
    // Ten zones need 30 of those 32 bits.
    private static byte[] zoned(int mode, int position, int strength) {
        byte[] b = blank(mode);
        int active = 0;
        int levels = 0;
        for (int zone = position; zone < ZONES; zone++) {
            active |= 1 << zone;
            levels |= (strength - 1) << (3 * zone);
        }
        b[1] = (byte) active;
        b[2] = (byte) (active >>> 8);
        b[3] = (byte) levels;
        b[4] = (byte) (levels >>> 8);
        b[5] = (byte) (levels >>> 16);
        b[6] = (byte) (levels >>> 24);
        return b;
    }

    private static void check(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " is " + value + ", not " + min + " to " + max);
        }
    }
}
