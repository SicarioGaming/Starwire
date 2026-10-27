package com.limelight.binding.input.dualsense;

/**
 * The host's "set adaptive triggers" message, control message 0x5503, a Sunshine extension to the
 * Moonlight protocol. Vibepollo, Sunshine, Apollo and others send it when a game programs a
 * DualSense's triggers, with the game's bytes copied as they are.
 *
 * Its payload, after the 4-byte control header, as Vibepollo writes it (src/stream.cpp,
 * control_adaptive_triggers_t, packed) and moonlight-common-c reads it (src/ControlStream.c,
 * IDX_DS_ADAPTIVE_TRIGGERS):
 * <pre>
 *  0  controller number, 16 bits, little-endian
 *  2  which triggers: 0x04 right, 0x08 left
 *  3  left trigger's mode
 *  4  right trigger's mode
 *  5  left trigger's ten parameter bytes
 * 15  right trigger's ten parameter bytes
 * </pre>
 * 25 bytes. Left comes before right here, but the pad's report has right before left.
 *
 * In the app, moonlight-common-c parses the message and passes the fields to Java through its
 * setAdaptiveTriggers callback (fromCallback, by way of MoonBridge and ControllerHandler). parse reads the raw payload, which pins the layout
 * down in tests. Only the triggers whose bit is set change, as in Nonary's moonlight-qt.
 */
public final class HostTriggerMessage {
    public static final int PAYLOAD_SIZE = 25;
    public static final int RIGHT_TRIGGER = 0x04;
    public static final int LEFT_TRIGGER = 0x08;

    public final int controllerNumber;
    /** The trigger bits the host set, 0x04 right and 0x08 left, with any other bits removed. */
    public final int triggers;
    public final TriggerEffect left;
    public final TriggerEffect right;

    private HostTriggerMessage(int controllerNumber, int triggers, TriggerEffect left, TriggerEffect right) {
        this.controllerNumber = controllerNumber;
        this.triggers = triggers;
        this.left = left;
        this.right = right;
    }

    /**
     * Reads a payload. Returns null if it's shorter than 25 bytes, since it comes from the
     * network. Extra bytes after the 25 are ignored, as moonlight-common-c ignores them.
     */
    public static HostTriggerMessage parse(byte[] payload, int offset, int length) {
        if (payload == null || offset < 0 || length < PAYLOAD_SIZE || length > payload.length - offset) {
            return null;
        }
        int controller = (payload[offset] & 0xFF) | ((payload[offset + 1] & 0xFF) << 8);
        return fromCallback(controller, payload[offset + 2], payload[offset + 3], payload[offset + 4],
                payload, offset + 5, payload, offset + 15);
    }

    /**
     * What a setAdaptiveTriggers(controllerNumber, eventFlags, typeLeft, typeRight, left, right)
     * callback from moonlight-common-c would carry. Each parameter array holds ten bytes from its
     * offset.
     */
    public static HostTriggerMessage fromCallback(int controllerNumber, int eventFlags, int typeLeft, int typeRight,
                                                  byte[] left, int leftOffset, byte[] right, int rightOffset) {
        return new HostTriggerMessage(controllerNumber & 0xFFFF, eventFlags & (RIGHT_TRIGGER | LEFT_TRIGGER),
                TriggerEffect.fromHost(typeLeft, left, leftOffset),
                TriggerEffect.fromHost(typeRight, right, rightOffset));
    }

    /** Sets the triggers this message names on a pad's output. The others keep what they had. */
    public void applyTo(DualSenseOutput output) {
        if ((triggers & RIGHT_TRIGGER) != 0) {
            output.setRightTrigger(right);
        }
        if ((triggers & LEFT_TRIGGER) != 0) {
            output.setLeftTrigger(left);
        }
    }
}
