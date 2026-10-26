package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * One DualSense connection, against a fake HID host: the checks on connect, what goes out and
 * when, and the fall back to today's path with a reason when anything fails.
 */
public class DualSenseSessionTest {
    private static final String PAD = "A0:5A:5C:00:00:01";
    private static final String PAD_NAME = "DualSense Wireless Controller";

    // The 47-byte common block of a report with both triggers and the strength set: flags 0 0x0c
    // (right and left trigger), flags 1 0x40 (motor power), the right block at 10, the left at 21
    // and the power cut at 36 (DualSenseOutput's layout, checked byte by byte in DualSenseOutputTest)
    private static String common(String right, String left, int powerCut) {
        return "0c40" + "00".repeat(8) + right + left + "00".repeat(4)
                + String.format("%02x", powerCut) + "00".repeat(10);
    }

    private static String bluetooth(int sequence, String common) {
        byte[] r = Hex.bytes(String.format("31%x010", sequence) + common + "00".repeat(24) + "00000000");
        DualSenseCrc.sign(r);
        return Hex.of(r);
    }

    private static final String OFF = "05" + "00".repeat(10);
    // TriggerEffect.weapon(4, 6, 6): zones 4 and 6 (0x0050), strength 6 - 1
    private static final String GUN_CLICK = "25500005" + "00".repeat(7);

    @Test
    public void connectSendsTheStrengthAndBothTriggersInOneReport() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.GUN_CLICK), 5);

        assertEquals(DualSenseStatus.WORKING, session.connect(null));
        assertEquals(PAD, session.address());
        assertEquals(1, host.calls.size());
        // Strength 5 of 8 is a cut of 3 steps, in the trigger half of byte 36
        assertEquals(bluetooth(0, common(GUN_CLICK, GUN_CLICK, 0x30)), Hex.of(host.calls.get(0).report));
        assertEquals(PAD, host.calls.get(0).address);
    }

    @Test
    public void withNoPresetTheTriggersAreTurnedOffOnConnect() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.NONE), TriggerStrength.FULL);

        assertEquals(DualSenseStatus.WORKING, session.connect(null));
        assertEquals(bluetooth(0, common(OFF, OFF, 0)), Hex.of(host.calls.get(0).report));
    }

    @Test
    public void theGamesEffectGoesOutAtOnceAndOnlyWhatChanged() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.GUN_CLICK), 8);
        session.connect(null);

        // Vibepollo's payload from DualSenseHostTriggerMessageTest: right weapon(2, 6, 8), left feedback(3, 6)
        byte[] payload = Hex.bytes(DualSenseHostTriggerMessageTest.VIBEPOLLO_PAYLOAD);
        assertEquals(DualSenseStatus.WORKING, session.onGameMessage(HostTriggerMessage.parse(payload, 0, payload.length)));
        assertEquals(2, host.calls.size());
        byte[] c = new byte[DualSenseOutput.COMMON_SIZE];
        System.arraycopy(host.calls.get(1).report, DualSenseOutput.BLUETOOTH_COMMON_OFFSET, c, 0, c.length);
        assertEquals("both triggers, no strength this time", 0x0C, c[0]);
        assertEquals(0, c[1]);
        assertEquals("2544000700000000000000", Hex.of(java.util.Arrays.copyOfRange(c, 10, 21)));
        assertEquals("21f80300dab62d00000000", Hex.of(java.util.Arrays.copyOfRange(c, 21, 32)));

        // The same message again changes nothing, so nothing goes out
        session.onGameMessage(HostTriggerMessage.parse(payload, 0, payload.length));
        assertEquals(2, host.calls.size());
    }

    @Test
    public void aBurstOfMessagesCostsOneSend() {
        // DualSenseBluetooth applies every message that arrived to the rules, then syncs once, so
        // a host that floods 0x5503 can't queue up one Bluetooth send per message
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        TriggerRules rules = new TriggerRules(TriggerPreset.NONE);
        DualSenseSession session = new DualSenseSession(host, rules, 8);
        session.connect(null);
        byte[] none = new byte[10];
        for (int i = 1; i <= 50; i++) {
            byte[] right = new byte[10];
            right[0] = (byte) i;
            rules.onGameMessage(HostTriggerMessage.fromCallback(0, HostTriggerMessage.RIGHT_TRIGGER, 0, 0x21, none, 0, right, 0));
        }
        assertEquals(DualSenseStatus.WORKING, session.sync());
        assertEquals("connect, then one send", 2, host.calls.size());
        byte[] last = host.calls.get(1).report;
        assertEquals("the last message wins", 50, last[DualSenseOutput.BLUETOOTH_COMMON_OFFSET + 11]);
        assertEquals(DualSenseStatus.WORKING, session.sync());
        assertEquals("nothing changed, nothing sent", 2, host.calls.size());
    }

    @Test
    public void aGameEffectBeforeConnectIsSentOnConnect() {
        // Hosts send an effect only when it changes, so one that came while the pad was
        // connecting, or before a reconnect, must still reach the pad
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        TriggerRules rules = new TriggerRules(TriggerPreset.BUZZ);
        DualSenseSession first = new DualSenseSession(host, rules, 8);
        first.connect(null);
        byte[] left = new byte[10];
        byte[] right = Hex.bytes("50000005000000000000");
        first.onGameMessage(HostTriggerMessage.fromCallback(0, HostTriggerMessage.RIGHT_TRIGGER, 0, 0x25, left, 0, right, 0));
        first.lost();

        DualSenseSession again = new DualSenseSession(host, rules, 8);
        assertEquals(DualSenseStatus.WORKING, again.connect(null));
        // The game's right effect, and the left off: the buzz preset doesn't come back
        assertEquals(bluetooth(0, common("2550000005000000000000", OFF, 0)), Hex.of(host.calls.get(host.calls.size() - 1).report));
    }

    @Test
    public void releaseTurnsBothTriggersOff() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.HEAVY_PEDAL), 8);
        session.connect(null);
        assertEquals(DualSenseStatus.WORKING, session.release());
        byte[] last = host.calls.get(host.calls.size() - 1).report;
        assertEquals(0x0C, last[DualSenseOutput.BLUETOOTH_COMMON_OFFSET]);
        assertEquals(TriggerEffect.MODE_OFF, last[DualSenseOutput.BLUETOOTH_COMMON_OFFSET + 10]);
        assertEquals(TriggerEffect.MODE_OFF, last[DualSenseOutput.BLUETOOTH_COMMON_OFFSET + 21]);
        assertEquals("sequence 1", 0x10, last[1] & 0xFF);
    }

    @Test
    public void testSendsOneEffectToAFoundPad() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.NONE), 8);

        assertEquals(DualSenseStatus.READY, session.find(null));
        assertEquals("finding sends nothing", 0, host.calls.size());
        assertEquals(DualSenseStatus.WORKING, session.test(TriggerPreset.GUN_CLICK.effect()));
        assertEquals(1, host.calls.size());
        assertEquals(bluetooth(0, common(GUN_CLICK, GUN_CLICK, 0)), Hex.of(host.calls.get(0).report));
    }

    // ---- Falling back, with the reason ----

    @Test
    public void noServiceMeansNoHidService() {
        DualSenseSession session = new DualSenseSession(null, new TriggerRules(TriggerPreset.GUN_CLICK), 8);
        assertEquals(DualSenseStatus.NO_HID_SERVICE, session.connect(PAD));
    }

    @Test
    public void noCallFormMeansNoCallForm() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        host.answers.clear();
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.GUN_CLICK), 8);
        assertEquals(DualSenseStatus.NO_CALL_FORM, session.connect(PAD));
        assertEquals(0, host.calls.size());
    }

    @Test
    public void noPadMeansNoPadAndNothingIsSent() {
        FakeHidHost host = FakeHidHost.working("11:22:33:44:55:66", "Keyboard K380");
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.GUN_CLICK), 8);
        assertEquals(DualSenseStatus.NO_PAD, session.connect(null));
        assertEquals(0, host.calls.size());
    }

    @Test
    public void twoPadsMeansTwoPadsAndNothingIsSent() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        host.devices.add(new HidHost.Device("A0:5A:5C:00:00:02", PAD_NAME));
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.GUN_CLICK), 8);
        assertEquals(DualSenseStatus.TWO_PADS, session.connect(PAD));
        assertEquals(0, host.calls.size());
    }

    @Test
    public void aRefusedSendFallsBackAndSendsNothingMore() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME)
                .answer(FakeHidHost.Answer.FALSE, SendForm.SEND_DATA_BYTES, SendForm.SET_REPORT_BYTES, SendForm.SEND_DATA_HEX)
                .answer(FakeHidHost.Answer.THROW, SendForm.SET_REPORT_HEX);
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.GUN_CLICK), 8);

        assertEquals(DualSenseStatus.SEND_FAILED, session.connect(null));
        assertEquals("each form tried once", 4, host.calls.size());
        assertTrue(session.lastError() instanceof IllegalStateException);
        assertTrue(session.status().isFallback());

        // Today's path from now on: the game's effect is remembered but not sent, and release sends nothing
        byte[] payload = Hex.bytes(DualSenseHostTriggerMessageTest.VIBEPOLLO_PAYLOAD);
        assertEquals(DualSenseStatus.SEND_FAILED, session.onGameMessage(HostTriggerMessage.parse(payload, 0, payload.length)));
        assertEquals(DualSenseStatus.SEND_FAILED, session.release());
        assertEquals(DualSenseStatus.SEND_FAILED, session.test(TriggerEffect.off()));
        assertEquals(4, host.calls.size());
    }

    @Test
    public void aSendThatFailsLaterFallsBackToo() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.NONE), 8);
        assertEquals(DualSenseStatus.WORKING, session.connect(null));

        // The pad went out of range: Android refuses everything
        host.answer(FakeHidHost.Answer.FALSE, SendForm.values());
        byte[] payload = Hex.bytes(DualSenseHostTriggerMessageTest.VIBEPOLLO_PAYLOAD);
        assertEquals(DualSenseStatus.SEND_FAILED, session.onGameMessage(HostTriggerMessage.parse(payload, 0, payload.length)));
        assertNull(session.sendForm());
    }

    @Test
    public void aLostPadSendsNothing() {
        FakeHidHost host = FakeHidHost.working(PAD, PAD_NAME);
        DualSenseSession session = new DualSenseSession(host, new TriggerRules(TriggerPreset.NONE), 8);
        session.connect(null);
        session.lost();
        assertEquals(DualSenseStatus.NO_PAD, session.status());
        assertEquals(DualSenseStatus.NO_PAD, session.release());
        assertEquals(1, host.calls.size());
    }
}
