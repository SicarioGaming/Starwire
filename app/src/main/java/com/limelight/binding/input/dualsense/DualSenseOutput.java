package com.limelight.binding.input.dualsense;

/**
 * Builds the DualSense's main output report, the one that sets the two rumble motors, the light
 * bar, the player lights and the adaptive triggers: report 0x02 over USB (48 bytes) and report
 * 0x31 over Bluetooth (78 bytes, with a sequence number and a checksum).
 *
 * Both carry the same 47-byte block, called "common" here:
 * <pre>
 *  0  flags 0: 0x01 rumble (compatible vibration), 0x02 rumble instead of HD haptics,
 *              0x04 right trigger, 0x08 left trigger
 *  1  flags 1: 0x04 light bar, 0x08 release the LEDs, 0x10 player lights, 0x40 motor power
 *  2  right motor (the light, fast one), 0 to 255
 *  3  left motor (the heavy, slow one), 0 to 255
 * 10  right trigger block, 11 bytes (TriggerEffect)
 * 21  left trigger block, 11 bytes
 * 36  motor power cut: low 4 bits rumble, high 4 bits triggers, 12.5% a step
 * 38  flags 2: 0x02 light bar setup, 0x04 rumble (improved compatible vibration)
 * 41  light bar setup: 0x02 fades out the light Bluetooth pairing left on
 * 43  player lights: bits 0 to 4 left to right, 0x20 switches at once instead of fading
 * 44  light bar red, 45 green, 46 blue
 * </pre>
 * USB puts report ID 0x02 in front. Bluetooth puts 0x31, then the sequence number in the high four
 * bits of the next byte, then 0x10, then the 47 bytes, 24 zero bytes and the checksum
 * (DualSenseCrc). The sequence number goes up by one for every report and wraps from 15 to 0.
 *
 * Sources for the layout, all read, none copied: Linux's drivers/hid/hid-playstation.c (copyright
 * Sony Interactive Entertainment), SDL's src/joystick/hidapi/SDL_hidapi_ps5.c, DS5Dongle's src/utils.h
 * (motor power), DS4Windows' DualSenseDevice.cs (motor power), and the pad's own HID descriptor
 * (nondebug/dualsense) for the 48-byte USB size. Linux sends 63 bytes over USB, zero-padded.
 *
 * It keeps what the pad should show and remembers what changed. Each report carries only what
 * changed since the last one, so it never restarts a trigger effect or redoes lights nobody asked
 * to change, which is how Linux's driver works. Rumble is the exception: while a motor runs, every
 * report carries the motor values, and one more report carries the change to zero. After that,
 * rumble is left out, which per SDL hands the actuators back to HD haptics. The light bar setup is
 * the other exception: it always goes in a report of its own, before any colour.
 *
 * DualSenseSession uses it for the Bluetooth reports, for the trigger effects and their strength
 * only; nothing else here is sent by the app yet, and none of it has been tried on a real pad. Not
 * thread-safe. Plain Java, so JVM tests run it as the app does.
 */
public final class DualSenseOutput {
    public static final int COMMON_SIZE = 47;
    public static final int USB_REPORT_ID = 0x02;
    public static final int USB_REPORT_SIZE = 1 + COMMON_SIZE;
    public static final int BLUETOOTH_REPORT_ID = 0x31;
    public static final int BLUETOOTH_REPORT_SIZE = 78;
    /** Where the common block starts in a Bluetooth report. */
    public static final int BLUETOOTH_COMMON_OFFSET = 3;
    /** Linux calls this "tag" and says it must be set; nobody documents what it means. */
    public static final int BLUETOOTH_TAG = 0x10;

    // Offsets in the common block
    static final int FLAGS0 = 0;
    static final int FLAGS1 = 1;
    static final int MOTOR_RIGHT = 2;
    static final int MOTOR_LEFT = 3;
    static final int RIGHT_TRIGGER = 10;
    static final int LEFT_TRIGGER = 21;
    static final int MOTOR_POWER = 36;
    static final int FLAGS2 = 38;
    static final int LIGHTBAR_SETUP = 41;
    static final int PLAYER_LEDS = 43;
    static final int LIGHTBAR_RED = 44;
    static final int LIGHTBAR_GREEN = 45;
    static final int LIGHTBAR_BLUE = 46;

    static final int FLAG0_COMPATIBLE_VIBRATION = 0x01;
    static final int FLAG0_HAPTICS_SELECT = 0x02;
    static final int FLAG0_RIGHT_TRIGGER = 0x04;
    static final int FLAG0_LEFT_TRIGGER = 0x08;
    static final int FLAG1_LIGHTBAR = 0x04;
    static final int FLAG1_RELEASE_LEDS = 0x08;
    static final int FLAG1_PLAYER_LEDS = 0x10;
    static final int FLAG1_MOTOR_POWER = 0x40;
    static final int FLAG2_LIGHTBAR_SETUP = 0x02;
    static final int FLAG2_COMPATIBLE_VIBRATION2 = 0x04;
    static final int LIGHTBAR_SETUP_LIGHT_OUT = 0x02;
    /** Player light bits: five lights, left to right. */
    public static final int PLAYER_LEDS_MASK = 0x1F;
    static final int PLAYER_LEDS_INSTANT = 0x20;
    /** The most each motor power cut goes: 7 steps of 12.5%, the range DS5Dongle and DS4Windows agree on. */
    public static final int MAX_POWER_CUT = 7;

    private int motorLeft;
    private int motorRight;
    private boolean improvedRumble;
    private boolean rumbleChanged;

    private int red;
    private int green;
    private int blue;
    private boolean lightbarSet;
    private boolean lightbarChanged;

    private int playerLeds;
    private boolean playerLedsSet;
    private boolean playerLedsChanged;

    private TriggerEffect rightTrigger;
    private TriggerEffect leftTrigger;
    private boolean rightTriggerChanged;
    private boolean leftTriggerChanged;

    private int powerCut;
    private boolean powerCutSet;
    private boolean powerCutChanged;

    private boolean releaseLedsPending;
    private boolean lightbarSetupPending;

    private int sequence;

    /**
     * Sets the two rumble motors, 0 (off) to 255 each. On a PC, the low-frequency (strong) rumble
     * goes to the left motor and the high-frequency (weak) one to the right, as Linux and SDL do.
     */
    public void setRumble(int left, int right) {
        int l = clampByte(left);
        int r = clampByte(right);
        if (l != motorLeft || r != motorRight) {
            motorLeft = l;
            motorRight = r;
            rumbleChanged = true;
        }
    }

    /**
     * Uses the "improved" rumble flag (flags 2, 0x04) instead of the original one (flags 0, 0x01).
     * Linux picks it from firmware 2.21, SDL and DS4Windows from 2.24. With the original flag, SDL
     * halves the motor values to feel like an Xbox pad; this class sends them as given.
     */
    public void setImprovedRumble(boolean improved) {
        if (improved != improvedRumble) {
            improvedRumble = improved;
            // Stopped motors stay out of the report, so there's nothing to resend
            rumbleChanged |= motorLeft != 0 || motorRight != 0;
        }
    }

    /** Sets the light bar's colour, 0 to 255 for each of red, green and blue. */
    public void setLightbar(int r, int g, int b) {
        int nr = clampByte(r);
        int ng = clampByte(g);
        int nb = clampByte(b);
        if (!lightbarSet || nr != red || ng != green || nb != blue) {
            red = nr;
            green = ng;
            blue = nb;
            lightbarSet = true;
            lightbarChanged = true;
        }
    }

    /**
     * Sets the five player lights under the touchpad.
     *
     * @param mask    one bit per light, left to right from bit 0, so 0x04 is the middle one
     * @param instant switch at once instead of fading
     */
    public void setPlayerLeds(int mask, boolean instant) {
        int value = (mask & PLAYER_LEDS_MASK) | (instant ? PLAYER_LEDS_INSTANT : 0);
        if (!playerLedsSet || value != playerLeds) {
            playerLeds = value;
            playerLedsSet = true;
            playerLedsChanged = true;
        }
    }

    /** Sets the right trigger's effect. The pad keeps it until it gets another. */
    public void setRightTrigger(TriggerEffect effect) {
        if (!effect.equals(rightTrigger)) {
            rightTrigger = effect;
            rightTriggerChanged = true;
        }
    }

    /** Sets the left trigger's effect. The pad keeps it until it gets another. */
    public void setLeftTrigger(TriggerEffect effect) {
        if (!effect.equals(leftTrigger)) {
            leftTrigger = effect;
            leftTriggerChanged = true;
        }
    }

    /**
     * Weakens the motors by 12.5% a step, 0 (full strength) to 7. DS4Windows says the cut leaves
     * plain resistance alone and scales only the repeating trigger effects. DS5Dongle allows the
     * trigger cut up to 10; this class keeps to the 0 to 7 both agree on. Whether the rumble cut
     * also scales HD haptics is unknown.
     */
    public void setMotorPowerCut(int rumbleSteps, int triggerSteps) {
        if (rumbleSteps < 0 || rumbleSteps > MAX_POWER_CUT || triggerSteps < 0 || triggerSteps > MAX_POWER_CUT) {
            throw new IllegalArgumentException("power cuts are 0 to " + MAX_POWER_CUT
                    + ", not " + rumbleSteps + " and " + triggerSteps);
        }
        int value = rumbleSteps | (triggerSteps << 4);
        if (!powerCutSet || value != powerCut) {
            powerCut = value;
            powerCutSet = true;
            powerCutChanged = true;
        }
    }

    /**
     * Asks the pad to hand its LEDs over and fade out the light it shows after Bluetooth pairing,
     * as Linux does once when a pad connects (dualsense_reset_leds), and as SDL does with its
     * "reset LED state" flag. Goes out with the next report only.
     */
    public void releaseLeds() {
        releaseLedsPending = true;
        lightbarSetupPending = true;
    }

    /**
     * Fades out the light Bluetooth pairing left on, so the light bar takes colours afterwards.
     * The next report is exactly Linux's dualsense_reset_leds (hid-playstation.c, sent once when
     * a pad connects, before any colour): flags 2 0x02 and light bar setup 0x02, nothing else.
     * Anything else already set waits for the report after it. Moonlight V+ sends the same setup
     * report on its own first too (DualSenseBluetoothOutput.kt:85-92 at 5840bab3).
     */
    public void resetLightbar() {
        lightbarSetupPending = true;
    }

    /**
     * Sends everything set so far again with the next report, for example after the pad
     * reconnects. Hosts send trigger effects only when they change, so a reconnected pad would
     * otherwise lose the game's effect. Running motors need nothing: every report carries them.
     */
    public void resendAll() {
        lightbarChanged = lightbarSet;
        playerLedsChanged = playerLedsSet;
        rightTriggerChanged = rightTrigger != null;
        leftTriggerChanged = leftTrigger != null;
        powerCutChanged = powerCutSet;
    }

    /** True if the next report would carry anything. */
    public boolean hasChanges() {
        return rumbleChanged || motorLeft != 0 || motorRight != 0 || lightbarChanged
                || playerLedsChanged || rightTriggerChanged || leftTriggerChanged || powerCutChanged
                || releaseLedsPending || lightbarSetupPending;
    }

    /** The next USB report, 48 bytes starting with report ID 0x02. */
    public byte[] nextUsbReport() {
        byte[] report = new byte[USB_REPORT_SIZE];
        report[0] = USB_REPORT_ID;
        takeCommon(report, 1);
        return report;
    }

    /** The next Bluetooth report, 78 bytes starting with report ID 0x31, signed. */
    public byte[] nextBluetoothReport() {
        byte[] report = new byte[BLUETOOTH_REPORT_SIZE];
        report[0] = (byte) BLUETOOTH_REPORT_ID;
        report[1] = (byte) (sequence << 4);
        report[2] = BLUETOOTH_TAG;
        sequence = (sequence + 1) & 0x0F;
        takeCommon(report, BLUETOOTH_COMMON_OFFSET);
        DualSenseCrc.sign(report);
        return report;
    }

    // Writes the common block for what changed, then marks it sent.
    private void takeCommon(byte[] report, int at) {
        int flags0 = 0;
        int flags1 = 0;
        int flags2 = 0;

        // The light bar setup goes on its own, before any colour, as Linux sends it. Whatever
        // else changed goes in the next report.
        if (lightbarSetupPending) {
            if (releaseLedsPending) {
                report[at + FLAGS1] = FLAG1_RELEASE_LEDS;
                releaseLedsPending = false;
            }
            report[at + FLAGS2] = FLAG2_LIGHTBAR_SETUP;
            report[at + LIGHTBAR_SETUP] = LIGHTBAR_SETUP_LIGHT_OUT;
            lightbarSetupPending = false;
            return;
        }

        if (rumbleChanged || motorLeft != 0 || motorRight != 0) {
            flags0 |= FLAG0_HAPTICS_SELECT;
            if (improvedRumble) {
                flags2 |= FLAG2_COMPATIBLE_VIBRATION2;
            } else {
                flags0 |= FLAG0_COMPATIBLE_VIBRATION;
            }
            report[at + MOTOR_RIGHT] = (byte) motorRight;
            report[at + MOTOR_LEFT] = (byte) motorLeft;
            rumbleChanged = false;
        }
        if (rightTriggerChanged) {
            flags0 |= FLAG0_RIGHT_TRIGGER;
            rightTrigger.writeTo(report, at + RIGHT_TRIGGER);
            rightTriggerChanged = false;
        }
        if (leftTriggerChanged) {
            flags0 |= FLAG0_LEFT_TRIGGER;
            leftTrigger.writeTo(report, at + LEFT_TRIGGER);
            leftTriggerChanged = false;
        }
        if (lightbarChanged) {
            flags1 |= FLAG1_LIGHTBAR;
            report[at + LIGHTBAR_RED] = (byte) red;
            report[at + LIGHTBAR_GREEN] = (byte) green;
            report[at + LIGHTBAR_BLUE] = (byte) blue;
            lightbarChanged = false;
        }
        if (playerLedsChanged) {
            flags1 |= FLAG1_PLAYER_LEDS;
            report[at + PLAYER_LEDS] = (byte) playerLeds;
            playerLedsChanged = false;
        }
        if (powerCutChanged) {
            flags1 |= FLAG1_MOTOR_POWER;
            report[at + MOTOR_POWER] = (byte) powerCut;
            powerCutChanged = false;
        }

        report[at + FLAGS0] = (byte) flags0;
        report[at + FLAGS1] = (byte) flags1;
        report[at + FLAGS2] = (byte) flags2;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
