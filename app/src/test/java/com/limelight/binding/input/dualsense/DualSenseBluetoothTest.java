package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.InputDevice;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowInputDevice;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * DualSenseBluetooth, the part that runs a stream's or Settings' DualSense: the checks before any
 * hidden call, which controllers count, one pad at a time, the game's effects, the retry and the
 * timeout, turning the triggers off at the end, and the log line for each of the game's effects.
 * It runs on Robolectric's paused main looper with FakeHidHost as Android's Bluetooth, so time
 * only moves when a test moves it.
 */
@RunWith(RobolectricTestRunner.class)
// PreferenceConfiguration reaches MoonBridge, which loads the native streaming library in its
// static block. Instrumenting it lets Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class DualSenseBluetoothTest {
    private static final String PAD = "A0:5A:5C:00:00:01";
    private static final String PAD_NAME = "DualSense Wireless Controller";
    // Android's usual descriptor: a hash, with no address in it
    private static final String DESCRIPTOR = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
    // weapon(2, 6, 8) and feedback(3, 6) as a game sends them (DualSenseHostTriggerMessageTest)
    private static final byte[] WEAPON = Hex.bytes("44000700000000000000");
    private static final byte[] FEEDBACK = Hex.bytes("f80300dab62d00000000");
    private static final String OFF = FakeHidHost.hex(TriggerEffect.off());

    private final Application context = RuntimeEnvironment.getApplication();
    private final FakeHidHost link = FakeHidHost.working(PAD, PAD_NAME);
    private final List<DualSenseStatus> heard = new ArrayList<>();
    private int linksMade;
    private LimeLogLines log;

    @Before
    public void allowNearbyDevices() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        log = LimeLogLines.listen();
    }

    @After
    public void stopListening() {
        log.close();
    }

    private DualSenseBluetooth.LinkFactory fake() {
        return c -> {
            linksMade++;
            return link;
        };
    }

    private DualSenseBluetooth stream(TriggerPreset preset) {
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(context);
        prefs.dualSenseEffects = true;
        prefs.dualSenseTriggerPreset = preset;
        prefs.dualSenseTriggerStrength = TriggerStrength.FULL;
        DualSenseBluetooth dualSense = DualSenseBluetooth.forStream(context, prefs, fake(), Looper.getMainLooper());
        dualSense.start();
        idle();
        return dualSense;
    }

    private DualSenseBluetooth settings() {
        DualSenseBluetooth dualSense = DualSenseBluetooth.forSettings(context, TriggerStrength.FULL, heard::add,
                fake(), Looper.getMainLooper());
        dualSense.start();
        idle();
        return dualSense;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void waitFor(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    // A DualSense shows up as a controller, as ControllerHandler reports it
    private static void dualSense(DualSenseBluetooth dualSense, int deviceId) {
        controller(dualSense, deviceId, PadMatcher.DUALSENSE_PRODUCT_ID, true);
    }

    private static void controller(DualSenseBluetooth dualSense, int deviceId, int productId, boolean gamepad) {
        dualSense.onController(deviceId, PadMatcher.SONY_VENDOR_ID, productId,
                "Input Device " + deviceId + ": DualSense Wireless Controller", DESCRIPTOR, gamepad);
        idle();
    }

    private static void gameSends(DualSenseBluetooth dualSense, int deviceId) {
        // Right weapon, left feedback, as Vibepollo sends a game's effects
        dualSense.onHostTriggers(deviceId, 0x0C, 0x21, 0x25, FEEDBACK, WEAPON);
        idle();
    }

    @Test
    public void withTheSwitchOffThereIsNothing() {
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(context);
        prefs.dualSenseEffects = false;
        assertNull(DualSenseBluetooth.forStream(context, prefs, fake(), Looper.getMainLooper()));
        assertEquals(0, linksMade);
    }

    @Test
    public void connectSendsThePresetAndThenTheGamesEffects() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        assertEquals(1, link.opens);
        assertEquals(0, link.calls.size());

        dualSense(dualSense, 7);
        assertEquals(DualSenseStatus.WORKING, dualSense.status());
        assertEquals(1, link.calls.size());
        assertEquals(PAD, link.calls.get(0).address);
        String gunClick = FakeHidHost.hex(TriggerPreset.GUN_CLICK.effect());
        assertEquals(gunClick, FakeHidHost.rightTrigger(link.lastTaken()));
        assertEquals(gunClick, FakeHidHost.leftTrigger(link.lastTaken()));

        gameSends(dualSense, 7);
        assertEquals(2, link.calls.size());
        assertEquals("25" + Hex.of(WEAPON), FakeHidHost.rightTrigger(link.lastTaken()));
        assertEquals("21" + Hex.of(FEEDBACK), FakeHidHost.leftTrigger(link.lastTaken()));
    }

    @Test
    public void aBurstOfHostMessagesCostsOneSend() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.NONE);
        dualSense(dualSense, 7);
        dualSense.onHostTriggers(7, 0x0C, 0x21, 0x21, FEEDBACK, FEEDBACK);
        dualSense.onHostTriggers(7, 0x0C, 0x05, 0x05, new byte[10], new byte[10]);
        dualSense.onHostTriggers(7, 0x0C, 0x21, 0x25, FEEDBACK, WEAPON);
        idle();
        assertEquals("the connect, then one send with the last state", 2, link.calls.size());
        assertEquals("25" + Hex.of(WEAPON), FakeHidHost.rightTrigger(link.lastTaken()));
    }

    @Test
    public void messagesForAnotherControllerAreIgnored() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.NONE);
        dualSense(dualSense, 7);
        gameSends(dualSense, 8);
        assertEquals(1, link.calls.size());
        // Too short to be from moonlight-common-c: dropped
        dualSense.onHostTriggers(7, 0x0C, 0x21, 0x25, new byte[9], WEAPON);
        idle();
        assertEquals(1, link.calls.size());
    }

    @Test
    public void stopTurnsTheTriggersOffAndLetsGo() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        dualSense.stop();
        idle();
        assertEquals(2, link.calls.size());
        assertEquals(OFF, FakeHidHost.rightTrigger(link.lastTaken()));
        assertEquals(OFF, FakeHidHost.leftTrigger(link.lastTaken()));
        assertEquals(1, link.closes);
        assertEquals("WORKING", PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PreferenceConfiguration.DUALSENSE_LAST_STATUS_PREF_STRING, null));

        // Nothing after that, and stopping again is safe
        gameSends(dualSense, 7);
        dualSense.stop();
        idle();
        assertEquals(2, link.calls.size());
        assertEquals(1, link.closes);
    }

    @Test
    public void twoDualSensesStayOnTodaysPathUntilOneLeaves() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        assertEquals(1, link.calls.size());

        dualSense(dualSense, 8);
        assertEquals(DualSenseStatus.TWO_PADS, dualSense.status());
        assertEquals("the first pad's triggers are turned off", 2, link.calls.size());
        assertEquals(OFF, FakeHidHost.rightTrigger(link.lastTaken()));

        // The game's effect is kept for later, but goes to neither pad now
        gameSends(dualSense, 7);
        assertEquals(2, link.calls.size());

        // The one left takes over, with the game's effects
        dualSense.onControllerRemoved(8);
        idle();
        assertEquals(DualSenseStatus.WORKING, dualSense.status());
        assertEquals(3, link.calls.size());
        assertEquals("25" + Hex.of(WEAPON), FakeHidHost.rightTrigger(link.lastTaken()));
    }

    @Test
    public void onlyADualSenseWithSticksCounts() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        controller(dualSense, 5, 0x09CC, true);   // a DualShock 4
        dualSense.onController(6, 0x045E, 0x0B13, "Xbox Wireless Controller", DESCRIPTOR, true);
        controller(dualSense, 7, PadMatcher.DUALSENSE_PRODUCT_ID, false);  // the pad's touchpad
        dualSense.onController(9, null, true);
        idle();
        waitFor(DualSenseBluetooth.PAD_RETRY_MS);
        assertEquals(0, link.calls.size());
        assertEquals(DualSenseStatus.CHECKING, dualSense.status());

        controller(dualSense, 8, PadMatcher.DUALSENSE_EDGE_PRODUCT_ID, true);
        assertEquals(DualSenseStatus.WORKING, dualSense.status());
        assertEquals(1, link.calls.size());
    }

    @Test
    public void withoutNearbyDevicesNoHiddenCallIsMade() {
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        assertEquals(DualSenseStatus.NO_PERMISSION, dualSense.status());
        assertEquals(0, link.opens);
        assertEquals(0, link.calls.size());
    }

    @Test
    public void withBluetoothOffNoHiddenCallIsMade() {
        link.bluetoothOn = false;
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        assertEquals(DualSenseStatus.BLUETOOTH_OFF, dualSense.status());
        assertEquals(0, link.opens);
        assertEquals(0, link.calls.size());
    }

    @Test
    public void aRefusedServiceMeansTodaysPath() {
        link.openTaken = false;
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        assertEquals(DualSenseStatus.NO_HID_SERVICE, dualSense.status());
        assertEquals(0, link.calls.size());
    }

    @Test
    public void aServiceThatNeverAnswersTimesOut() {
        link.serviceAnswers = false;
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        assertEquals(DualSenseStatus.CHECKING, dualSense.status());
        waitFor(DualSenseBluetooth.LINK_TIMEOUT_MS - 1);
        assertEquals(DualSenseStatus.CHECKING, dualSense.status());
        waitFor(1);
        assertEquals(DualSenseStatus.NO_HID_SERVICE, dualSense.status());
        assertEquals(0, link.calls.size());
    }

    @Test
    public void aPadThatShowsLateIsFoundOnTheRetry() {
        HidHost.Device pad = link.devices.remove(0);
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        assertEquals(0, link.calls.size());
        link.devices.add(pad);
        waitFor(DualSenseBluetooth.PAD_RETRY_MS);
        assertEquals(DualSenseStatus.WORKING, dualSense.status());
        assertEquals(1, link.calls.size());
    }

    @Test
    public void aCabledDualSenseBesideADualShock4SendsNothing() {
        // Android shows the DualSense on its cable as a controller; the only Bluetooth HID device
        // is a DualShock 4, which calls itself "Wireless Controller"
        link.devices.clear();
        link.devices.add(new HidHost.Device("11:22:33:44:55:77", "Wireless Controller"));
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        waitFor(DualSenseBluetooth.PAD_RETRY_MS);
        assertEquals(DualSenseStatus.NO_PAD, dualSense.status());
        assertEquals(0, link.calls.size());
    }

    @Test
    public void aRemovedPadEndsItsSession() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        dualSense.onControllerRemoved(7);
        idle();
        assertEquals(DualSenseStatus.NO_PAD, dualSense.status());
        gameSends(dualSense, 7);
        assertEquals(1, link.calls.size());
    }

    private String savedForSettings() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PreferenceConfiguration.DUALSENSE_LAST_STATUS_PREF_STRING, null);
    }

    @Test
    public void aPadThatWorkedAndThenLeftIsNotNoPadInSettings() {
        // Turned off or out of range before the stream ended: it worked, so Settings shouldn't
        // say "Last stream: No DualSense connected"
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        dualSense.onControllerRemoved(7);
        idle();
        assertEquals(DualSenseStatus.NO_PAD, dualSense.status());
        dualSense.stop();
        idle();
        assertEquals("WORKING", savedForSettings());
    }

    @Test
    public void aPadThatNeverWorkedIsNoPadInSettings() {
        // The DualSense on a cable, say: Android shows it, but its HID service never lists it
        link.devices.clear();
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        waitFor(DualSenseBluetooth.PAD_RETRY_MS);
        dualSense.stop();
        idle();
        assertEquals("NO_PAD", savedForSettings());
    }

    @Test
    public void aPadThatLeftAndCameBackFailingSavesTheFailure() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        dualSense.onControllerRemoved(7);
        idle();
        link.answer(FakeHidHost.Answer.FALSE, SendForm.values());
        dualSense(dualSense, 8);
        dualSense.stop();
        idle();
        assertEquals("SEND_FAILED", savedForSettings());
    }

    @Test
    public void aRefusedSendFallsBackForTheRestOfTheStream() {
        link.answer(FakeHidHost.Answer.FALSE, SendForm.values());
        DualSenseBluetooth dualSense = stream(TriggerPreset.GUN_CLICK);
        dualSense(dualSense, 7);
        assertEquals(DualSenseStatus.SEND_FAILED, dualSense.status());
        int tried = link.calls.size();
        gameSends(dualSense, 7);
        dualSense.stop();
        idle();
        assertEquals(tried, link.calls.size());
        assertEquals("SEND_FAILED", PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PreferenceConfiguration.DUALSENSE_LAST_STATUS_PREF_STRING, null));
    }

    @Test
    public void settingsFindsThePadAndSendsOnlyOnTest() {
        addInputDevice(7, PadMatcher.DUALSENSE_PRODUCT_ID);
        DualSenseBluetooth dualSense = settings();
        assertEquals(DualSenseStatus.READY, dualSense.status());
        assertEquals(DualSenseStatus.READY, heard.get(heard.size() - 1));
        assertEquals("nothing is sent before Test", 0, link.calls.size());

        dualSense.test(TriggerPreset.GUN_CLICK.effect(), 10_000);
        idle();
        assertEquals(DualSenseStatus.WORKING, heard.get(heard.size() - 1));
        assertEquals(1, link.calls.size());
        String gunClick = FakeHidHost.hex(TriggerPreset.GUN_CLICK.effect());
        assertEquals(gunClick, FakeHidHost.rightTrigger(link.lastTaken()));
        assertEquals(gunClick, FakeHidHost.leftTrigger(link.lastTaken()));

        // Off again after the hold, and back to "found"
        waitFor(9_999);
        assertEquals(1, link.calls.size());
        waitFor(1);
        assertEquals(2, link.calls.size());
        assertEquals(OFF, FakeHidHost.rightTrigger(link.lastTaken()));
        assertEquals(DualSenseStatus.READY, heard.get(heard.size() - 1));

        dualSense.stop();
        idle();
        assertNull("Settings never saves a stream's status", PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PreferenceConfiguration.DUALSENSE_LAST_STATUS_PREF_STRING, null));
    }

    @Test
    public void settingsWithNoPadSaysSoAndTestSendsNothing() {
        DualSenseBluetooth dualSense = settings();
        assertEquals(DualSenseStatus.NO_PAD, dualSense.status());
        dualSense.test(TriggerPreset.GUN_CLICK.effect(), 10_000);
        idle();
        assertEquals(0, link.calls.size());
        assertTrue(heard.contains(DualSenseStatus.NO_PAD));
    }

    @Test
    public void eachEffectIsLoggedOnceWithTheTimeSinceTheStreamStarted() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.NONE);
        dualSense(dualSense, 7);
        waitFor(2500);
        gameSends(dualSense, 7);
        gameSends(dualSense, 7);
        assertEquals(Arrays.asList(
                "DualSense effect: t=2500ms R2 mode=0x25 params=44000700000000000000 sent",
                "DualSense effect: t=2500ms L2 mode=0x21 params=f80300dab62d00000000 sent"), log.effects());
        assertEquals("the connect and the effects, as without the log", 2, link.calls.size());
        for (String line : log.effects()) {
            assertTrue("nothing about the pad: " + line, !line.contains(PAD) && !line.contains(PAD_NAME));
        }
    }

    @Test
    public void anEffectBeforeThePadIsReadyIsLoggedAsHeld() {
        link.serviceAnswers = false;
        DualSenseBluetooth dualSense = stream(TriggerPreset.NONE);
        dualSense(dualSense, 7);
        assertEquals(DualSenseStatus.CHECKING, dualSense.status());
        dualSense.onHostTriggers(7, HostTriggerMessage.RIGHT_TRIGGER, 0, 0x25, new byte[10], WEAPON);
        idle();
        assertEquals(Collections.singletonList(
                "DualSense effect: t=0ms R2 mode=0x25 params=44000700000000000000 held (no pad ready)"), log.effects());
    }

    @Test
    public void anUnknownModeIsLoggedAsDropped() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.NONE);
        dualSense(dualSense, 7);
        dualSense.onHostTriggers(7, HostTriggerMessage.RIGHT_TRIGGER, 0, 0xFC, new byte[10],
                Hex.bytes("0102030405060708090a"));
        idle();
        assertEquals(Collections.singletonList(
                "DualSense effect: t=0ms R2 mode=0xfc params=0102030405060708090a dropped (unknown mode, trigger off)"),
                log.effects());
        assertEquals(OFF, FakeHidHost.rightTrigger(link.lastTaken()));
    }

    @Test
    public void withTheOffPresetTheGamesEffectsAreLoggedAsReplaced() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.OFF);
        dualSense(dualSense, 7);
        gameSends(dualSense, 7);
        assertEquals(Arrays.asList(
                "DualSense effect: t=0ms R2 mode=0x25 params=44000700000000000000 replaced by preset off",
                "DualSense effect: t=0ms L2 mode=0x21 params=f80300dab62d00000000 replaced by preset off"),
                log.effects());
        assertEquals("only the connect", 1, link.calls.size());
    }

    @Test
    public void stopLogsWhatTheLimitHeldBack() {
        DualSenseBluetooth dualSense = stream(TriggerPreset.NONE);
        dualSense(dualSense, 7);
        for (int force = 0; force < TriggerEffectLog.MAX_LINES_A_SECOND + 2; force++) {
            byte[] resistance = new byte[10];
            resistance[1] = (byte) force;
            dualSense.onHostTriggers(7, HostTriggerMessage.RIGHT_TRIGGER, 0, 0x01, new byte[10], resistance);
        }
        idle();
        assertEquals(TriggerEffectLog.MAX_LINES_A_SECOND, log.effects().size());

        dualSense.stop();
        idle();
        List<String> lines = log.effects();
        assertEquals(Arrays.asList(
                "DualSense effect: t=0ms 1 change not logged, more than 10 in a second",
                "DualSense effect: t=0ms R2 mode=0x01 params=000b0000000000000000 sent"),
                lines.subList(TriggerEffectLog.MAX_LINES_A_SECOND, lines.size()));
    }

    @Test
    public void settingsLogsNoEffects() {
        DualSenseBluetooth dualSense = settings();
        dualSense(dualSense, 7);
        gameSends(dualSense, 7);
        dualSense.stop();
        idle();
        assertEquals(Collections.emptyList(), log.effects());
    }

    // A controller Android lists, as Settings finds it through InputDevice
    private void addInputDevice(int id, int productId) {
        InputDevice device = ShadowInputDevice.makeInputDeviceNamed(PAD_NAME);
        shadowOf(device).setVendorId(PadMatcher.SONY_VENDOR_ID);
        shadowOf(device).setProductId(productId);
        ReflectionHelpers.setField(device, "mId", id);
        ReflectionHelpers.setField(device, "mSources", InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK);
        shadowOf(context.getSystemService(InputManager.class)).addInputDevice(device);
    }
}
