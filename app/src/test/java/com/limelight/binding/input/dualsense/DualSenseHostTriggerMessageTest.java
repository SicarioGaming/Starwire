package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Message 0x5503 from the host onto the pad's trigger blocks. Sources, read at a fixed commit:
 * <ul>
 * <li>VP: Nonary/Vibepollo src/stream.cpp @608032e1 (GPL-3.0): the packed struct at lines 321 and
 *     444-457, filled at 1441-1451.</li>
 * <li>MCC: Starwire's moonlight-common-c submodule @976f261b, src/ControlStream.c:1080-1089 (the
 *     parser) and src/Limelight.h:528-531 (flags and callback). Nonary's fork @4ea115e8 is the same.</li>
 * <li>MLQT: Nonary/moonlight-qt @d6205e1e (GPL-3.0), app/streaming/input/dualsensetriggers.h and
 *     tests/haptics/tst_dualsensehaptics.cpp:388-403.</li>
 * </ul>
 */
public class DualSenseHostTriggerMessageTest {
    // Vibepollo's control_adaptive_triggers_t for controller 1, both triggers, with the left trigger
    // on feedback(3, 6) and the right on weapon(2, 6, 8), laid out field by field (VP 444-457):
    //   01 00       id, little-endian
    //   0c          event_flags: 0x04 right | 0x08 left
    //   21          type_left
    //   25          type_right
    //   f8 03 00 da b6 2d 00 00 00 00   left[10]
    //   44 00 07 00 00 00 00 00 00 00   right[10]
    static final String VIBEPOLLO_PAYLOAD = "01000c2125" + "f80300dab62d00000000" + "44000700000000000000";

    @Test
    public void vibepolloPayloadLandsOnTheRightBlocks() {
        byte[] payload = Hex.bytes(VIBEPOLLO_PAYLOAD);
        assertEquals(HostTriggerMessage.PAYLOAD_SIZE, payload.length);

        HostTriggerMessage message = HostTriggerMessage.parse(payload, 0, payload.length);
        assertNotNull(message);
        assertEquals(1, message.controllerNumber);
        assertEquals(0x0C, message.triggers);
        assertEquals(TriggerEffect.feedback(3, 6), message.left);
        assertEquals(TriggerEffect.weapon(2, 6, 8), message.right);

        DualSenseOutput out = new DualSenseOutput();
        message.applyTo(out);
        // Right block (mode 0x25) at common byte 10, left (0x21) at 21: the reverse of the message's order
        assertEquals("02" + "0c" + "00".repeat(9) + "2544000700000000000000" + "21f80300dab62d00000000"
                + "00".repeat(15), Hex.of(out.nextUsbReport()));
    }

    @Test
    public void parsesFromInsideAWholeControlMessage() {
        // VP 394-397 and 1443-1444: control_header_v2 is the type (0x5503) and the payload length
        // (sizeof minus the header, 25 with pack(1) from line 321), both 16-bit little-endian.
        byte[] message = Hex.bytes("0355" + "1900" + VIBEPOLLO_PAYLOAD);
        HostTriggerMessage parsed = HostTriggerMessage.parse(message, 4, message.length - 4);
        assertNotNull(parsed);
        assertEquals(TriggerEffect.weapon(2, 6, 8), parsed.right);
        assertEquals(TriggerEffect.feedback(3, 6), parsed.left);
    }

    @Test
    public void matchesMoonlightQtsTriggerTest() {
        // MLQT tst_dualsensehaptics.cpp:388-399: left[i] = i + 1, right[i] = 255 - i, left type 0x21,
        // right type 0x26, flags 0xff. Expected: flag byte 0x0c, right type at 10, left type at 21,
        // the payloads at 11 and 22, and nothing else touched.
        byte[] left = new byte[10];
        byte[] right = new byte[10];
        for (int i = 0; i < 10; i++) {
            left[i] = (byte) (i + 1);
            right[i] = (byte) (255 - i);
        }
        HostTriggerMessage message = HostTriggerMessage.fromCallback(0, 0xFF, 0x21, 0x26, left, 0, right, 0);
        DualSenseOutput out = new DualSenseOutput();
        message.applyTo(out);
        byte[] c = Hex.common(out.nextUsbReport());
        assertEquals(0x0C, c[0]);
        assertEquals(0x26, c[10]);
        assertEquals(0x21, c[21]);
        for (int i = 0; i < 10; i++) {
            assertEquals(right[i], c[11 + i]);
            assertEquals(left[i], c[22 + i]);
        }
        for (int i = 1; i < 47; i++) {
            if (i < 10 || i >= 32) {
                assertEquals("byte " + i, 0, c[i]);
            }
        }
    }

    @Test
    public void onlyTheNamedTriggerChanges() {
        // MLQT tst_dualsensehaptics.cpp:400-403: flags with only the left bit give flag byte 8.
        // libvirtualgamepad driver/src/dualsense.cpp:355-367 @998deebd: an omitted trigger keeps its program.
        DualSenseOutput out = new DualSenseOutput();
        out.setRightTrigger(TriggerEffect.vibration(8, 8, 10));
        out.nextUsbReport();

        byte[] params = Hex.bytes("f80300dab62d00000000");
        HostTriggerMessage.fromCallback(0, HostTriggerMessage.LEFT_TRIGGER, 0x21, 0x25, params, 0, params, 0)
                .applyTo(out);
        byte[] c = Hex.common(out.nextUsbReport());
        assertEquals(0x08, c[0]);
        assertEquals(0, c[10]);
        assertEquals(0x21, c[21]);

        out.resendAll();
        c = Hex.common(out.nextUsbReport());
        assertEquals("2600030000003f00000a00", Hex.of(java.util.Arrays.copyOfRange(c, 10, 21)));
    }

    @Test
    public void callbackAndPayloadAgree() {
        byte[] payload = Hex.bytes(VIBEPOLLO_PAYLOAD);
        HostTriggerMessage parsed = HostTriggerMessage.parse(payload, 0, payload.length);
        HostTriggerMessage called = HostTriggerMessage.fromCallback(1, 0x0C, 0x21, 0x25, payload, 5, payload, 15);
        assertEquals(parsed.left, called.left);
        assertEquals(parsed.right, called.right);
        assertEquals(parsed.triggers, called.triggers);
        assertEquals(parsed.controllerNumber, called.controllerNumber);
    }

    @Test
    public void shortOrBadPayloadsAreRefused() {
        byte[] payload = Hex.bytes(VIBEPOLLO_PAYLOAD);
        assertNull(HostTriggerMessage.parse(payload, 0, 24));
        assertNull(HostTriggerMessage.parse(payload, 1, 25));
        assertNull(HostTriggerMessage.parse(payload, -1, 25));
        assertNull(HostTriggerMessage.parse(null, 0, 25));
        assertNull(HostTriggerMessage.parse(new byte[0], 0, 0));
        // Longer is fine: moonlight-common-c reads the 25 bytes it knows and ignores the rest
        byte[] longer = Hex.bytes(VIBEPOLLO_PAYLOAD + "ffff");
        assertNotNull(HostTriggerMessage.parse(longer, 0, longer.length));
    }

    @Test
    public void debugModesFromTheHostBecomeOff() {
        // A game (or a hostile network) could send 0xFC to 0xFE, which break the trigger until a reset
        byte[] params = new byte[10];
        HostTriggerMessage message = HostTriggerMessage.fromCallback(0, 0x0C, 0xFC, 0xFE, params, 0, params, 0);
        assertSame(TriggerEffect.off(), message.left);
        assertSame(TriggerEffect.off(), message.right);
    }

    @Test
    public void unknownFlagBitsAreIgnored() {
        byte[] params = new byte[10];
        HostTriggerMessage message = HostTriggerMessage.fromCallback(0x12345, 0xF3, 0x21, 0x21, params, 0, params, 0);
        assertEquals(0, message.triggers);
        assertEquals(0x2345, message.controllerNumber);
        DualSenseOutput out = new DualSenseOutput();
        message.applyTo(out);
        assertEquals(0, out.nextUsbReport()[1]);
    }
}
