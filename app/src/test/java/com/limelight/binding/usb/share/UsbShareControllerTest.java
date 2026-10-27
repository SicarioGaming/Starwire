package com.limelight.binding.usb.share;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.limelight.R;
import com.limelight.binding.usb.share.UsbShareController.State;
import com.limelight.binding.usb.share.UsbShareTunnel.Ending;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 * The share flow, with a fake for each part (FakeUsb): the states a device goes through, what
 * gives it back, who gets your yes, and that answers for a share that's over are dropped. The
 * main thread runs at once; the worker runs only when the test says, and the tunnel's side is
 * driven by hand, so each step can be checked in between.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class UsbShareControllerTest {
    private static final String PAD = "/dev/bus/usb/001/002";
    private static final String KEYBOARD = "/dev/bus/usb/001/003";
    private static final String STICK = "/dev/bus/usb/001/004";
    private static final String HEADSET = "/dev/bus/usb/001/005";

    private final Context context = RuntimeEnvironment.getApplication();
    private final FakeUsb.Devices devices = new FakeUsb.Devices();
    private final FakeUsb.Backends backends = new FakeUsb.Backends();
    private final FakeUsb.Ui ui = new FakeUsb.Ui();
    private final FakeUsb.Queue worker = new FakeUsb.Queue();
    private final UsbShareTunnel.Target target = new UsbShareTunnel.Target("192.168.1.20", "pc-uuid");
    private UsbShareController usb;

    @Before
    public void plugIn() {
        devices.plugged.addAll(Arrays.asList(FakeUsb.pad(PAD), FakeUsb.keyboard(KEYBOARD),
                FakeUsb.storage(STICK), FakeUsb.headset(HEADSET)));
        usb = new UsbShareController(context, devices, backends, target, ui, Runnable::run, worker);
    }

    private State state(String key) {
        return usb.entry(key).state();
    }

    private String detail(String key) {
        return UsbShareText.detail(context, usb.entry(key));
    }

    private String str(int id, Object... args) {
        return context.getString(id, args);
    }

    // A device, from choosing it to SHARE_ACK, with no question on the way
    private void share(String key) {
        usb.activate(key);
        worker.runAll();
        assertTrue(backends.tunnel().bindAck());
        backends.tunnel().shareAck();
        assertEquals(State.SHARED, state(key));
    }

    @Test
    public void eachDeviceShowsWhatTheRulesSay() {
        assertEquals(Arrays.asList(PAD, KEYBOARD, STICK, HEADSET),
                Arrays.asList(usb.entries().get(0).key, usb.entries().get(1).key, usb.entries().get(2).key,
                        usb.entries().get(3).key));
        assertEquals("Test pad", usb.entry(PAD).name);
        assertEquals(0x1209, usb.entry(PAD).vendorId);
        assertEquals(0x0001, usb.entry(PAD).productId);
        // A pad's report descriptor decides, once the device can be opened
        assertEquals(str(R.string.usb_verdict_unchecked), detail(PAD));
        assertEquals(str(R.string.usb_verdict_ask), detail(KEYBOARD));
        assertEquals(str(R.string.usb_verdict_refused, str(R.string.usb_rule_storage)), detail(STICK));
        assertEquals(str(R.string.usb_verdict_allowed), detail(HEADSET));
    }

    @Test
    public void sharingAPadGoesThroughEachState() {
        devices.autoAnswer = -1;
        usb.activate(PAD);
        assertEquals(State.PERMISSION, state(PAD));
        assertEquals(Collections.singletonList(PAD), devices.asked);
        assertEquals(str(R.string.usb_state_permission), detail(PAD));

        devices.answer(UsbDevices.GRANTED);
        assertEquals(State.CONNECTING, state(PAD));
        assertEquals(str(R.string.usb_state_connecting), detail(PAD));
        // The tunnel connects off the main thread, to this stream's PC
        FakeUsb.Tunnel tunnel = backends.tunnel();
        assertEquals(0, tunnel.connects);
        worker.runAll();
        assertEquals(1, tunnel.connects);
        assertEquals("192.168.1.20", tunnel.target.host);
        assertEquals("pc-uuid", tunnel.target.computerUuid);
        // Nothing is exported until the helper has proved the pairing
        assertEquals(0, backends.exporter().starts);

        assertTrue(tunnel.bindAck());
        FakeUsb.Exporter exporter = backends.exporter();
        assertEquals(1, exporter.starts);
        assertFalse("nobody said yes to anything", exporter.confirmed);
        assertArrayEquals(FakeUsb.SHARE, tunnel.share);
        assertEquals(State.CONNECTING, state(PAD));

        tunnel.shareAck();
        assertEquals(State.SHARED, state(PAD));
        assertEquals(str(R.string.usb_state_shared), detail(PAD));
        assertEquals(str(R.string.usb_shared_toast, "Test pad"), ui.lastMessage());
        assertTrue(usb.isBusy());
        // The exporter's own check let it through, so that's the verdict now
        assertEquals(UsbVerdict.ALLOW, usb.entry(PAD).verdict());
    }

    @Test
    public void stopGivesItBack() {
        share(PAD);
        FakeUsb.Exporter exporter = backends.exporter();
        FakeUsb.Tunnel tunnel = backends.tunnel();
        usb.activate(PAD);
        assertEquals(Collections.singletonList(UsbShareTunnel.END_STOPPED), tunnel.closes);
        assertEquals(State.STOPPING, state(PAD));
        assertEquals(str(R.string.usb_state_stopping), detail(PAD));
        // Choosing it again while it's being given back does nothing more
        usb.activate(PAD);
        assertEquals(1, tunnel.closes.size());
        // Busy until the tunnel says it's over
        assertTrue(usb.isBusy());

        tunnel.endAsAsked();
        assertEquals(1, exporter.stops);
        assertFalse(exporter.running);
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_verdict_allowed), detail(PAD));
        assertFalse(usb.isBusy());
        assertEquals(1, ui.messages.size());
    }

    // Nothing about the device leaves the phone, and the exporter doesn't start, until the
    // helper has proved the pairing
    @Test
    public void aRefusalBeforeBindAckLeavesTheExporterAlone() {
        usb.activate(PAD);
        worker.runAll();
        FakeUsb.Tunnel tunnel = backends.tunnel();
        tunnel.messageId = R.string.usb_share_connect_failed;
        tunnel.end(Ending.REFUSED, 4, 0);
        assertEquals(0, backends.exporter().starts);
        assertEquals(0, backends.exporter().stops);
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_share_connect_failed), detail(PAD));
        assertEquals(str(R.string.usb_share_connect_failed), ui.lastMessage());
        assertFalse(usb.isBusy());
    }

    @Test
    public void aKeyboardIsSharedOnlyAfterAYes() {
        usb.activate(KEYBOARD);
        assertEquals(State.CONFIRM, state(KEYBOARD));
        assertEquals(str(R.string.usb_state_confirm), detail(KEYBOARD));
        assertSame(usb.entry(KEYBOARD), ui.confirming);
        assertTrue(backends.tunnels.isEmpty());

        ui.answer.onAnswer(true);
        assertEquals(State.CONNECTING, state(KEYBOARD));
        worker.runAll();
        assertTrue(backends.tunnel().bindAck());
        // The yes goes to the exporter, which puts it in SHARE
        assertTrue(backends.exporter().confirmed);
        backends.tunnel().shareAck();
        assertEquals(State.SHARED, state(KEYBOARD));
    }

    @Test
    public void noToAKeyboardSharesNothing() {
        usb.activate(KEYBOARD);
        ui.answer.onAnswer(false);
        assertEquals(State.IDLE, state(KEYBOARD));
        assertFalse(usb.isBusy());
        assertTrue(backends.tunnels.isEmpty());
        assertTrue(backends.exporters.isEmpty());
        // An answer that comes again, or late, changes nothing
        ui.answer.onAnswer(true);
        assertTrue(backends.tunnels.isEmpty());
    }

    // The question's answer counts once: a second yes (a second A press, say) doesn't connect again
    @Test
    public void aSecondAnswerChangesNothing() {
        usb.activate(KEYBOARD);
        UsbShareController.Answer answer = ui.answer;
        answer.onAnswer(true);
        answer.onAnswer(true);
        answer.onAnswer(false);
        worker.runAll();
        assertEquals(1, backends.tunnels.size());
        assertEquals(1, backends.tunnel().connects);
        assertEquals(State.CONNECTING, state(KEYBOARD));
    }

    // The exporter reads all of a device's descriptors and finds a keyboard behind what the list
    // showed: with no yes for this share it exports nothing, and the question comes. Only a yes
    // then lets the next connection's exporter share it.
    @Test
    public void theExportersOwnCheckDecidesWhetherToAsk() {
        backends.exporterVerdict = UsbVerdict.ASK_FIRST;
        usb.activate(PAD);
        worker.runAll();
        FakeUsb.Tunnel first = backends.tunnel();
        FakeUsb.Exporter firstExporter = backends.exporter();
        assertFalse(first.bindAck());
        assertFalse(firstExporter.confirmed);
        assertFalse(firstExporter.running);
        assertNull("no SHARE went to the PC", first.share);
        assertTrue(first.ended);

        assertEquals(State.CONFIRM, state(PAD));
        assertEquals(1, ui.questions);
        assertEquals(str(R.string.usb_verdict_ask), UsbShareText.verdict(context, usb.entry(PAD).verdict()));
        ui.answer.onAnswer(true);
        worker.runAll();
        assertEquals(2, backends.tunnels.size());
        assertTrue(backends.tunnel().bindAck());
        assertTrue(backends.exporter().confirmed);
        assertTrue(backends.exporter().running);
        backends.tunnel().shareAck();
        assertEquals(State.SHARED, state(PAD));
    }

    // A no to that question shares nothing, and from then on it asks
    @Test
    public void noAfterTheExportersCheckSharesNothing() {
        backends.exporterVerdict = UsbVerdict.ASK_FIRST;
        usb.activate(PAD);
        worker.runAll();
        backends.tunnel().bindAck();
        ui.answer.onAnswer(false);
        assertEquals(State.IDLE, state(PAD));
        assertFalse(usb.isBusy());
        assertEquals(1, backends.tunnels.size());
        assertEquals(str(R.string.usb_verdict_ask), detail(PAD));
    }

    // A yes is for one share: sharing the keyboard again asks again
    @Test
    public void aYesIsForThatShareOnly() {
        usb.activate(KEYBOARD);
        ui.answer.onAnswer(true);
        worker.runAll();
        backends.tunnel().bindAck();
        backends.tunnel().shareAck();
        usb.activate(KEYBOARD);
        backends.tunnel().endAsAsked();

        usb.activate(KEYBOARD);
        assertEquals(State.CONFIRM, state(KEYBOARD));
        assertEquals(2, ui.questions);
        assertEquals(1, backends.tunnels.size());
    }

    @Test
    public void theExportersOwnCheckCanRefuse() {
        backends.exporterVerdict = UsbVerdict.refused(UsbVerdict.RULE_TOO_FAST);
        usb.activate(PAD);
        worker.runAll();
        assertFalse(backends.tunnel().bindAck());
        assertEquals(State.IDLE, state(PAD));
        assertFalse(usb.isBusy());
        String refused = str(R.string.usb_verdict_refused, str(R.string.usb_rule_too_fast));
        assertEquals(refused, detail(PAD));
        assertEquals(refused, ui.lastMessage());
        // From then on it's refused without asking Android again
        usb.activate(PAD);
        assertEquals(1, devices.asked.size());
        assertEquals(1, backends.tunnels.size());
    }

    // The list saw a boot keyboard: an exporter that would let it through doesn't take away the question
    @Test
    public void aKeyboardTheListSawStillAsks() {
        backends.exporterVerdict = UsbVerdict.ALLOW;
        usb.activate(KEYBOARD);
        assertEquals(State.CONFIRM, state(KEYBOARD));
    }

    @Test
    public void aRefusedDeviceIsntOffered() {
        usb.activate(STICK);
        assertTrue(devices.asked.isEmpty());
        assertFalse(usb.isBusy());
        assertEquals(str(R.string.usb_verdict_refused, str(R.string.usb_rule_storage)), ui.lastMessage());
    }

    @Test
    public void androidCanSayNo() {
        devices.autoAnswer = UsbDevices.DENIED;
        usb.activate(PAD);
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_permission_denied), detail(PAD));
        assertTrue(backends.tunnels.isEmpty());
        assertFalse(usb.isBusy());

        devices.autoAnswer = UsbDevices.BLOCKED;
        usb.activate(PAD);
        assertEquals(str(R.string.error_usb_prohibited), detail(PAD));
    }

    @Test
    public void unpluggingItGivesItBack() {
        share(PAD);
        devices.unplug(PAD);
        assertEquals(Collections.singletonList(UsbShareTunnel.END_UNPLUGGED), backends.tunnel().closes);
        assertEquals(str(R.string.usb_unplugged_toast, "Test pad"), ui.lastMessage());
        assertNull(usb.entry(PAD));
        assertEquals(3, usb.entries().size());
        backends.tunnel().endAsAsked();
        assertEquals(1, backends.exporter().stops);
        assertFalse(usb.isBusy());
    }

    // The exporter can lose the device before Android says it's gone
    @Test
    public void theExporterLosingItGivesItBack() {
        share(PAD);
        backends.exporter().listener.onDeviceGone();
        assertEquals(Collections.singletonList(UsbShareTunnel.END_UNPLUGGED), backends.tunnel().closes);
        backends.tunnel().endAsAsked();
        assertEquals(1, backends.exporter().stops);
        assertEquals(State.IDLE, state(PAD));
    }

    @Test
    public void streamEndGivesItBack() {
        share(PAD);
        FakeUsb.Exporter exporter = backends.exporter();
        FakeUsb.Tunnel tunnel = backends.tunnel();
        usb.close(UsbShareTunnel.END_STREAM_ENDED);
        assertEquals(Collections.singletonList(UsbShareTunnel.END_STREAM_ENDED), tunnel.closes);
        assertTrue(devices.stopped);
        tunnel.endAsAsked();
        assertEquals(1, exporter.stops);
        assertFalse(exporter.running);
        assertEquals(State.IDLE, state(PAD));
        assertFalse(usb.isBusy());

        // Nothing happens after the stream: no new share, no new device
        int messages = ui.messages.size();
        usb.activate(HEADSET);
        devices.plugIn(FakeUsb.pad("/dev/bus/usb/002/002"));
        assertEquals(1, devices.asked.size());
        assertEquals(1, backends.tunnels.size());
        assertEquals(messages, ui.messages.size());
        assertEquals(4, usb.entries().size());
    }

    @Test
    public void streamEndWhileConnectingGivesItBack() {
        usb.activate(PAD);
        worker.runAll();
        assertTrue(backends.tunnel().bindAck());
        usb.close(UsbShareTunnel.END_STREAM_ENDED);
        assertEquals(Collections.singletonList(UsbShareTunnel.END_STREAM_ENDED), backends.tunnel().closes);
        // A SHARE_ACK that comes after doesn't make it shared
        backends.tunnel().shareAck();
        assertEquals(State.STOPPING, state(PAD));
        backends.tunnel().endAsAsked();
        assertEquals(1, backends.exporter().stops);
        assertEquals(State.IDLE, state(PAD));
    }

    @Test
    public void streamEndWhileTheQuestionIsUpTakesItAway() {
        usb.activate(KEYBOARD);
        usb.close(UsbShareTunnel.END_STREAM_ENDED);
        assertEquals(1, ui.cancels);
        assertFalse(usb.isBusy());
        ui.answer.onAnswer(true);
        assertTrue(backends.tunnels.isEmpty());
    }

    @Test
    public void aWifiDropOffersShareAgain() {
        share(PAD);
        FakeUsb.Exporter first = backends.exporter();
        backends.tunnel().end(Ending.LOST, 0, 0);
        assertEquals(1, first.stops);
        assertEquals(State.LOST, state(PAD));
        assertEquals(str(R.string.usb_state_lost), detail(PAD));
        assertEquals(str(R.string.usb_lost_toast, "Test pad"), ui.lastMessage());
        assertFalse(usb.isBusy());

        // Share again: the same flow, a new tunnel and exporter
        share(PAD);
        assertEquals(2, backends.tunnels.size());
        assertEquals(1, backends.exporter().starts);
    }

    // The phone saying its network went away reaches the tunnel at once, without 8 s of silence
    @Test
    public void aNetworkDropReachesTheTunnel() {
        usb.networkLost();
        usb.activate(PAD);
        usb.networkLost();
        worker.runAll();
        assertEquals(0, backends.tunnel().networkLosses);
        backends.tunnel().bindAck();
        backends.tunnel().shareAck();
        usb.networkLost();
        assertEquals(1, backends.tunnel().networkLosses);
        backends.tunnel().end(Ending.LOST, 0, 0);
        usb.networkLost();
        assertEquals(1, backends.tunnel().networkLosses);
        assertEquals(State.LOST, state(PAD));
    }

    // Only a share that was up offers to be shared again
    @Test
    public void aDropBeforeSharingIsAFailure() {
        usb.activate(PAD);
        worker.runAll();
        backends.tunnel().messageId = R.string.usb_share_ended;
        backends.tunnel().end(Ending.LOST, 0, 0);
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_share_ended), detail(PAD));
    }

    @Test
    public void streamEndForgetsShareAgain() {
        share(PAD);
        backends.tunnel().end(Ending.LOST, 0, 0);
        usb.stopAll(UsbShareTunnel.END_STREAM_ENDED);
        assertEquals(State.IDLE, state(PAD));
    }

    @Test
    public void thePcCanEndIt() {
        share(PAD);
        backends.tunnel().messageId = R.string.usb_share_connect_failed;
        backends.tunnel().end(Ending.ENDED_BY_PC, 4, 0);
        assertEquals(1, backends.exporter().stops);
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_share_connect_failed), detail(PAD));
        assertEquals(str(R.string.usb_share_connect_failed), ui.lastMessage());
    }

    // The tunnel's own words, with the rule's text where it names one
    @Test
    public void thePcCanRefuseTheShare() {
        usb.activate(PAD);
        worker.runAll();
        FakeUsb.Tunnel tunnel = backends.tunnel();
        tunnel.bindAck();
        tunnel.messageId = R.string.usb_verdict_refused;
        tunnel.ruleId = R.string.usb_rule_mismatch;
        tunnel.end(Ending.NOT_SHARED, 2, UsbVerdict.RULE_MISMATCH);
        assertEquals(1, backends.exporter().stops);
        assertEquals(str(R.string.usb_verdict_refused, str(R.string.usb_rule_mismatch)), detail(PAD));
    }

    @Test
    public void oneDeviceAtATime() {
        share(PAD);
        usb.activate(HEADSET);
        assertEquals(1, devices.asked.size());
        assertEquals(State.IDLE, state(HEADSET));
        assertEquals(str(R.string.usb_one_at_a_time, "Test pad"), ui.lastMessage());
    }

    // Stop, then another device at once: the first one's tunnel isn't over yet, so the second
    // waits, rather than meeting a tunnel that won't connect while the last one finishes
    @Test
    public void theNextShareWaitsForTheLastToEnd() {
        share(PAD);
        usb.activate(PAD);
        usb.activate(HEADSET);
        assertEquals(1, devices.asked.size());
        assertEquals(str(R.string.usb_one_at_a_time, "Test pad"), ui.lastMessage());
        assertTrue(usb.isBusy());

        backends.tunnel().endAsAsked();
        usb.activate(HEADSET);
        worker.runAll();
        assertEquals(2, backends.tunnels.size());
        assertEquals(1, backends.tunnel().connects);
    }

    // A tunnel that won't connect (another share's still ending, say) ends this share with a
    // message, instead of the exception reaching the main thread or the share staying stuck
    @Test
    public void aConnectThatThrowsEndsTheShare() {
        backends.connectFailure = new IllegalStateException("another device is being shared");
        usb.activate(PAD);
        worker.runAll();
        assertEquals(State.IDLE, state(PAD));
        assertFalse(usb.isBusy());
        assertEquals(str(R.string.usb_share_connect_failed), detail(PAD));
        assertEquals(str(R.string.usb_share_connect_failed), ui.lastMessage());
        assertEquals(0, backends.exporter().starts);

        backends.connectFailure = null;
        share(PAD);
    }

    @Test
    public void stopWhileConnectingGivesUp() {
        usb.activate(PAD);
        worker.runAll();
        usb.activate(PAD);
        assertEquals(Collections.singletonList(UsbShareTunnel.END_STOPPED), backends.tunnel().closes);
        // The tunnel's BIND_ACK comes after all: the exporter mustn't start
        assertFalse(backends.tunnel().bindAck());
        assertEquals(0, backends.exporter().starts);
        assertEquals(State.IDLE, state(PAD));
        assertFalse(usb.isBusy());
    }

    // Stop before the worker has got to connect(): over at once, and nothing connects
    @Test
    public void stopBeforeTheTunnelConnects() {
        usb.activate(PAD);
        usb.activate(PAD);
        assertEquals(State.IDLE, state(PAD));
        assertFalse(usb.isBusy());
        worker.runAll();
        assertEquals(0, backends.tunnel().connects);
    }

    // Stop while connect() is running: its END is sent as soon as connect() returns
    @Test
    public void stopDuringConnectIsSentOnceItReturns() {
        usb.activate(PAD);
        FakeUsb.Tunnel tunnel = backends.tunnel();
        tunnel.duringConnect = () -> usb.activate(PAD);
        worker.runAll();
        assertEquals(1, tunnel.connects);
        assertEquals(Collections.singletonList(UsbShareTunnel.END_STOPPED), tunnel.closes);
        assertEquals(State.STOPPING, state(PAD));
        tunnel.endAsAsked();
        assertEquals(State.IDLE, state(PAD));
        assertFalse(usb.isBusy());
    }

    @Test
    public void theExporterFailingToStartIsSaid() {
        backends.exporterFailure = new IOException("claim failed");
        usb.activate(PAD);
        worker.runAll();
        FakeUsb.Tunnel tunnel = backends.tunnel();
        tunnel.messageId = R.string.usb_share_connect_failed;
        assertFalse(tunnel.bindAck());
        assertEquals(0, backends.exporter().stops);
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_share_connect_failed), detail(PAD));
    }

    // An exporter that throws while stopping mustn't leave the share stuck, with nothing else
    // allowed to be shared for the rest of the stream
    @Test
    public void aStopThatThrowsStillEndsTheShare() {
        share(PAD);
        backends.exporter().stopFailure = new IllegalStateException("the native side went away");
        usb.activate(PAD);
        backends.tunnel().endAsAsked();
        assertEquals(State.IDLE, state(PAD));
        assertFalse(usb.isBusy());
        usb.activate(HEADSET);
        assertEquals(State.CONNECTING, state(HEADSET));
    }

    // The tunnel stops the exporter. One that ends without doing so still gets the device back.
    @Test
    public void aTunnelThatForgetsTheExporterStillGivesItBack() {
        share(PAD);
        FakeUsb.Tunnel tunnel = backends.tunnel();
        tunnel.started = false;
        tunnel.end(Ending.ENDED_BY_PC, 1, 0);
        assertEquals(1, backends.exporter().stops);
        assertFalse(backends.exporter().running);
        // And never twice
        backends.exporter().listener.onDeviceGone();
        assertEquals(1, backends.exporter().stops);
    }

    @Test
    public void devicesComeAndGo() {
        int changes = ui.changes;
        devices.plugIn(FakeUsb.pad("/dev/bus/usb/002/002"));
        assertEquals(5, usb.entries().size());
        assertEquals("/dev/bus/usb/002/002", usb.entries().get(4).key);
        assertTrue(ui.changes > changes);
        devices.unplug(KEYBOARD);
        assertNull(usb.entry(KEYBOARD));
        // Unplugging one that isn't shared says nothing
        assertTrue(ui.messages.isEmpty());
    }

    // Unplugged while Android's dialog is up: the answer that follows changes nothing
    @Test
    public void unpluggedWhileAsking() {
        devices.autoAnswer = -1;
        usb.activate(PAD);
        devices.unplug(PAD);
        devices.answer(UsbDevices.GRANTED);
        worker.runAll();
        assertTrue(backends.tunnels.isEmpty());
        assertFalse(usb.isBusy());
    }

    // Unplugged while the question is up: the question goes, and a late yes changes nothing
    @Test
    public void unpluggedWhileTheQuestionIsUp() {
        usb.activate(KEYBOARD);
        devices.unplug(KEYBOARD);
        assertEquals(1, ui.cancels);
        assertFalse(usb.isBusy());
        ui.answer.onAnswer(true);
        assertTrue(backends.tunnels.isEmpty());
        assertTrue(ui.messages.isEmpty());
    }
}
