package com.limelight.binding.usb.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import com.limelight.R;
import com.limelight.binding.usb.share.UsbShareController.State;

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
 * gives it back, and that answers for a share that's over are dropped. The main thread runs at
 * once; the worker runs only when the test says, so each step can be checked in between.
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
    private final UsbTunnel.Target target = new UsbTunnel.Target("192.168.1.20", "pc-uuid");
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

    // The pad, from choosing it to SHARE_ACK
    private void sharePad() throws IOException {
        usb.activate(PAD);
        worker.runAll();
        backends.tunnel().bindAck();
        backends.tunnel().shareAck();
        assertEquals(State.SHARED, state(PAD));
    }

    @Test
    public void eachDeviceShowsWhatTheRulesSay() {
        assertEquals(Arrays.asList(PAD, KEYBOARD, STICK, HEADSET),
                Arrays.asList(usb.entries().get(0).key, usb.entries().get(1).key, usb.entries().get(2).key,
                        usb.entries().get(3).key));
        assertEquals("Test pad", usb.entry(PAD).name);
        // A pad's report descriptor decides, once the device can be opened
        assertEquals(str(R.string.usb_verdict_unchecked), detail(PAD));
        assertEquals(str(R.string.usb_verdict_ask), detail(KEYBOARD));
        assertEquals(str(R.string.usb_verdict_refused, str(R.string.usb_rule_storage)), detail(STICK));
        assertEquals(str(R.string.usb_verdict_allowed), detail(HEADSET));
    }

    @Test
    public void sharingAPadGoesThroughEachState() throws IOException {
        devices.autoAnswer = -1;
        usb.activate(PAD);
        assertEquals(State.PERMISSION, state(PAD));
        assertEquals(Collections.singletonList(PAD), devices.asked);
        assertEquals(str(R.string.usb_state_permission), detail(PAD));

        devices.answer(UsbDevices.GRANTED);
        assertEquals(State.CHECKING, state(PAD));
        assertEquals(0, backends.rules.checks);
        worker.runAll();
        assertEquals(1, backends.rules.checks);
        assertEquals(State.CONNECTING, state(PAD));

        // Connecting to this stream's PC, with no yes for input, and nothing exported yet
        FakeUsb.Tunnel tunnel = backends.tunnel();
        assertEquals("192.168.1.20", tunnel.target.host);
        assertEquals("pc-uuid", tunnel.target.computerUuid);
        assertFalse(tunnel.confirmed);
        assertEquals(0, backends.exporter().starts);

        tunnel.bindAck();
        assertEquals(1, backends.exporter().starts);
        assertEquals("1-1", tunnel.export.busId());
        assertEquals(State.CONNECTING, state(PAD));

        tunnel.shareAck();
        assertEquals(State.SHARED, state(PAD));
        assertEquals(str(R.string.usb_state_shared), detail(PAD));
        assertEquals(str(R.string.usb_shared_toast, "Test pad"), ui.lastMessage());
        assertTrue(usb.isBusy());
    }

    @Test
    public void stopGivesItBack() throws IOException {
        sharePad();
        FakeUsb.Exporter exporter = backends.exporter();
        usb.activate(PAD);
        assertEquals(State.STOPPING, state(PAD));
        assertEquals(str(R.string.usb_state_stopping), detail(PAD));
        // Choosing it again while it's being given back does nothing more
        usb.activate(PAD);
        assertEquals(1, worker.size());

        worker.runAll();
        assertEquals(Collections.singletonList(UsbTunnel.END_STOPPED), backends.tunnel().closes);
        assertEquals(1, exporter.stops);
        assertFalse(exporter.running);
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_verdict_allowed), detail(PAD));
        assertFalse(usb.isBusy());
    }

    // Nothing about the device leaves the phone, and the exporter doesn't start, until the
    // helper has proved the pairing
    @Test
    public void aRefusalBeforeBindAckLeavesTheExporterAlone() {
        usb.activate(PAD);
        worker.runAll();
        backends.tunnel().closed(UsbTunnel.Closed.REFUSED, UsbTunnel.REFUSE_NOT_ALLOWED, 0);
        worker.runAll();
        assertEquals(0, backends.exporter().starts);
        assertEquals(0, backends.exporter().stops);
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_refuse_not_allowed), detail(PAD));
        assertEquals(str(R.string.usb_refuse_not_allowed), ui.lastMessage());
    }

    @Test
    public void aKeyboardIsSharedOnlyAfterAYes() throws IOException {
        usb.activate(KEYBOARD);
        worker.runAll();
        assertEquals(State.CONFIRM, state(KEYBOARD));
        assertEquals("Test keyboard", ui.confirming);
        assertTrue(backends.tunnels.isEmpty());

        ui.answer.onAnswer(true);
        assertEquals(State.CONNECTING, state(KEYBOARD));
        assertTrue(backends.tunnel().confirmed);
        backends.tunnel().bindAck();
        backends.tunnel().shareAck();
        assertEquals(State.SHARED, state(KEYBOARD));
    }

    @Test
    public void noToAKeyboardSharesNothing() {
        usb.activate(KEYBOARD);
        worker.runAll();
        ui.answer.onAnswer(false);
        worker.runAll();
        assertEquals(State.IDLE, state(KEYBOARD));
        assertTrue(backends.tunnels.isEmpty());
        assertTrue(backends.exporters.isEmpty());
        // An answer that comes again, or late, changes nothing
        ui.answer.onAnswer(true);
        assertTrue(backends.tunnels.isEmpty());
    }

    // The check of all its descriptors can find a keyboard behind a pad's class codes
    @Test
    public void theFullCheckCanAsk() {
        backends.rules.verdict = UsbVerdict.ASK_FIRST;
        usb.activate(PAD);
        worker.runAll();
        assertEquals(State.CONFIRM, state(PAD));
        assertEquals(str(R.string.usb_verdict_ask), UsbShareText.verdict(context, usb.entry(PAD).verdict()));
    }

    @Test
    public void theFullCheckCanRefuse() {
        backends.rules.verdict = UsbVerdict.refused(UsbVerdict.RULE_TOO_FAST);
        usb.activate(PAD);
        worker.runAll();
        assertEquals(State.IDLE, state(PAD));
        assertTrue(backends.tunnels.isEmpty());
        String refused = str(R.string.usb_verdict_refused, str(R.string.usb_rule_too_fast));
        assertEquals(refused, detail(PAD));
        assertEquals(refused, ui.lastMessage());
        // From then on it's refused without asking Android again
        usb.activate(PAD);
        assertEquals(1, devices.asked.size());
    }

    // The preview saw a boot keyboard: a check that says "allowed" doesn't take away the question
    @Test
    public void aKeyboardThePreviewSawStillAsks() {
        backends.rules.verdict = UsbVerdict.ALLOW;
        usb.activate(KEYBOARD);
        worker.runAll();
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
        worker.runAll();
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_permission_denied), detail(PAD));
        assertEquals(0, backends.rules.checks);

        devices.autoAnswer = UsbDevices.BLOCKED;
        usb.activate(PAD);
        worker.runAll();
        assertEquals(str(R.string.error_usb_prohibited), detail(PAD));
    }

    @Test
    public void anUnreadableDeviceIsntShared() {
        backends.rules.failure = new IOException("no descriptors");
        usb.activate(PAD);
        worker.runAll();
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_read_failed), detail(PAD));
        assertTrue(backends.tunnels.isEmpty());
    }

    @Test
    public void unpluggingItGivesItBack() throws IOException {
        sharePad();
        devices.unplug(PAD);
        worker.runAll();
        assertEquals(Collections.singletonList(UsbTunnel.END_UNPLUGGED), backends.tunnel().closes);
        assertEquals(1, backends.exporter().stops);
        assertNull(usb.entry(PAD));
        assertEquals(3, usb.entries().size());
        assertEquals(str(R.string.usb_unplugged_toast, "Test pad"), ui.lastMessage());
        assertFalse(usb.isBusy());
    }

    // The exporter can lose the device before Android says it's gone
    @Test
    public void theExporterLosingItGivesItBack() throws IOException {
        sharePad();
        backends.exporter().listener.onDeviceGone();
        worker.runAll();
        assertEquals(Collections.singletonList(UsbTunnel.END_UNPLUGGED), backends.tunnel().closes);
        assertEquals(1, backends.exporter().stops);
        assertEquals(State.IDLE, state(PAD));
    }

    @Test
    public void streamEndGivesItBack() throws IOException {
        sharePad();
        FakeUsb.Exporter exporter = backends.exporter();
        FakeUsb.Tunnel tunnel = backends.tunnel();
        usb.close(UsbTunnel.END_STREAM_ENDED);
        worker.runAll();
        assertEquals(Collections.singletonList(UsbTunnel.END_STREAM_ENDED), tunnel.closes);
        assertEquals(1, exporter.stops);
        assertFalse(exporter.running);
        assertEquals(State.IDLE, state(PAD));
        assertTrue(devices.stopped);
        assertFalse(usb.isBusy());

        // Nothing happens after the stream: no new share, no late answer, no new device
        int messages = ui.messages.size();
        usb.activate(HEADSET);
        tunnel.closed(UsbTunnel.Closed.ENDED, UsbTunnel.END_USB_OFF, 0);
        worker.runAll();
        assertEquals(1, devices.asked.size());
        assertEquals(1, backends.tunnels.size());
        assertEquals(messages, ui.messages.size());
        assertEquals(1, exporter.stops);
    }

    @Test
    public void streamEndWhileConnectingGivesItBack() throws IOException {
        usb.activate(PAD);
        worker.runAll();
        backends.tunnel().bindAck();
        usb.close(UsbTunnel.END_STREAM_ENDED);
        worker.runAll();
        assertEquals(Collections.singletonList(UsbTunnel.END_STREAM_ENDED), backends.tunnel().closes);
        assertEquals(1, backends.exporter().stops);
        // A SHARE_ACK that comes after doesn't make it shared
        backends.tunnel().shareAck();
        assertEquals(State.IDLE, state(PAD));
    }

    @Test
    public void aWifiDropOffersShareAgain() throws IOException {
        sharePad();
        FakeUsb.Exporter first = backends.exporter();
        backends.tunnel().closed(UsbTunnel.Closed.LOST, 0, 0);
        worker.runAll();
        assertEquals(1, first.stops);
        assertEquals(State.LOST, state(PAD));
        assertEquals(str(R.string.usb_state_lost), detail(PAD));
        assertEquals(str(R.string.usb_lost_toast, "Test pad"), ui.lastMessage());
        assertFalse(usb.isBusy());

        // Share again: the same flow, a new tunnel and exporter
        sharePad();
        assertEquals(2, backends.tunnels.size());
        assertEquals(1, backends.exporter().starts);
    }

    // Only a share that was up offers to be shared again
    @Test
    public void aDropBeforeSharingIsAFailure() {
        usb.activate(PAD);
        worker.runAll();
        backends.tunnel().closed(UsbTunnel.Closed.LOST, 0, 0);
        worker.runAll();
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_lost), detail(PAD));
    }

    @Test
    public void streamEndForgetsShareAgain() throws IOException {
        sharePad();
        backends.tunnel().closed(UsbTunnel.Closed.LOST, 0, 0);
        worker.runAll();
        usb.stopAll(UsbTunnel.END_STREAM_ENDED);
        assertEquals(State.IDLE, state(PAD));
    }

    @Test
    public void thePcCanEndIt() throws IOException {
        sharePad();
        backends.tunnel().closed(UsbTunnel.Closed.ENDED, UsbTunnel.END_USB_OFF, 0);
        worker.runAll();
        assertEquals(1, backends.exporter().stops);
        assertEquals(State.IDLE, state(PAD));
        assertEquals(str(R.string.usb_ended_usb_off), detail(PAD));
        assertEquals(str(R.string.usb_ended_usb_off), ui.lastMessage());
    }

    @Test
    public void thePcCanRefuseTheShare() throws IOException {
        usb.activate(PAD);
        worker.runAll();
        backends.tunnel().bindAck();
        backends.tunnel().closed(UsbTunnel.Closed.NOT_SHARED, UsbTunnel.SHARE_REFUSED, UsbVerdict.RULE_MISMATCH);
        worker.runAll();
        assertEquals(1, backends.exporter().stops);
        assertEquals(str(R.string.usb_not_shared_rules, str(R.string.usb_rule_mismatch)), detail(PAD));
    }

    @Test
    public void oneDeviceAtATime() throws IOException {
        sharePad();
        usb.activate(HEADSET);
        assertEquals(1, devices.asked.size());
        assertEquals(State.IDLE, state(HEADSET));
        assertEquals(str(R.string.usb_one_at_a_time, "Test pad"), ui.lastMessage());
    }

    @Test
    public void stopWhileConnectingGivesUp() {
        usb.activate(PAD);
        worker.runAll();
        usb.activate(PAD);
        worker.runAll();
        assertEquals(Collections.singletonList(UsbTunnel.END_STOPPED), backends.tunnel().closes);
        assertEquals(State.IDLE, state(PAD));
        // The tunnel's BIND_ACK comes after all: the exporter mustn't start
        try {
            backends.tunnel().bindAck();
            fail("the exporter started for a share that was stopped");
        }
        catch (IOException expected) {
            // The tunnel then ends the session
        }
        assertEquals(0, backends.exporter().starts);
    }

    // Stop is chosen, and BIND_ACK comes before the worker has given the device back: the
    // exporter starts, and is then stopped, not left running
    @Test
    public void anExporterStartedDuringStopIsStopped() throws IOException {
        usb.activate(PAD);
        worker.runAll();
        usb.activate(PAD);
        backends.tunnel().bindAck();
        assertTrue(backends.exporter().running);
        worker.runAll();
        assertFalse(backends.exporter().running);
        assertEquals(1, backends.exporter().stops);
    }

    // The check's answer comes after Stop: it doesn't go on to connect
    @Test
    public void aCheckAnsweredAfterStopIsDropped() {
        usb.activate(PAD);
        usb.activate(PAD);
        worker.runAll();
        assertTrue(backends.tunnels.isEmpty());
        assertEquals(State.IDLE, state(PAD));
    }

    @Test
    public void theExporterFailingToStartIsSaid() {
        usb.activate(PAD);
        worker.runAll();
        FakeUsb.Exporter exporter = backends.exporter();
        exporter.failure = new IOException("claim failed");
        try {
            backends.tunnel().bindAck();
            fail("no export");
        }
        catch (IOException expected) {
            // The tunnel ends the session and says why
        }
        backends.tunnel().closed(UsbTunnel.Closed.FAILED, UsbTunnel.FAILED_EXPORTER, 0);
        worker.runAll();
        assertEquals(0, exporter.stops);
        assertEquals(str(R.string.usb_failed_exporter), detail(PAD));
    }

    // An exporter that throws while stopping mustn't leave the share stuck, with nothing else
    // allowed to be shared for the rest of the stream
    @Test
    public void aStopThatThrowsStillEndsTheShare() throws IOException {
        sharePad();
        backends.exporter().stopFailure = new IllegalStateException("the native side went away");
        usb.activate(PAD);
        worker.runAll();
        assertEquals(State.IDLE, state(PAD));
        assertFalse(usb.isBusy());
        usb.activate(HEADSET);
        assertEquals(State.CHECKING, state(HEADSET));
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
        worker.runAll();
        devices.answer(UsbDevices.GRANTED);
        worker.runAll();
        assertEquals(0, backends.rules.checks);
        assertFalse(usb.isBusy());
    }
}
