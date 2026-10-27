package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.os.Looper;
import android.view.InputDevice;

import com.limelight.binding.input.dualsense.DualSenseBluetooth;
import com.limelight.binding.input.dualsense.DualSenseStatus;
import com.limelight.binding.input.dualsense.FakeHidHost;
import com.limelight.binding.input.dualsense.LimeLogLines;
import com.limelight.binding.input.dualsense.PadMatcher;
import com.limelight.binding.input.dualsense.TriggerEffect;
import com.limelight.binding.input.dualsense.TriggerPreset;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowInputDevice;

import java.util.Arrays;
import java.util.Collections;

/**
 * How ControllerHandler wires the DualSense code into a stream: each controller it takes on is
 * shown to it, a removed one is reported, the host's trigger effects go only to the controller
 * they name and are logged only with the DualSense switch on, and stopping the stream turns the
 * triggers off. Android's Bluetooth is FakeHidHost,
 * on Robolectric's paused main looper; no stream starts (there's no connection).
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class ControllerHandlerDualSenseTest {
    private static final String PAD = "A0:5A:5C:00:00:01";
    private static final String OFF = FakeHidHost.hex(TriggerEffect.off());
    private static final byte[] WEAPON = {0x44, 0, 0x07, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] FEEDBACK = {(byte) 0xF8, 0x03, 0, (byte) 0xDA, (byte) 0xB6, 0x2D, 0, 0, 0, 0};

    private Activity activity;
    private final FakeHidHost link = FakeHidHost.working(PAD, "DualSense Wireless Controller");
    private DualSenseBluetooth dualSense;
    private ControllerHandler handler;

    @Before
    public void startAStream() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        // No SHIELD controller service here: ControllerHandler's SceManager finds none, as on a phone
        shadowOf(activity.getApplication()).declareComponentUnbindable(
                new ComponentName("com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        prefs.dualSenseEffects = true;
        prefs.dualSenseTriggerPreset = TriggerPreset.NONE;
        dualSense = DualSenseBluetooth.forStream(activity, prefs, context -> link, Looper.getMainLooper());
        handler = new ControllerHandler(activity, null, null, prefs, dualSense);
        idle();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    // A controller ControllerHandler takes on, with the number the host knows it by
    private ControllerHandler.InputDeviceContext controller(int id, int vendorId, int productId, int number) {
        return controller(handler, id, vendorId, productId, number);
    }

    private static ControllerHandler.InputDeviceContext controller(ControllerHandler handler, int id, int vendorId,
                                                                  int productId, int number) {
        InputDevice device = ShadowInputDevice.makeInputDeviceNamed("Controller " + id);
        shadowOf(device).setVendorId(vendorId);
        shadowOf(device).setProductId(productId);
        ControllerHandler.InputDeviceContext context = handler.new InputDeviceContext();
        context.id = id;
        context.inputDevice = device;
        context.hasJoystickAxes = true;
        context.controllerNumber = (short) number;
        context.assignedControllerNumber = true;
        handler.addInputDeviceContext(id, context);
        idle();
        return context;
    }

    // Right weapon(2, 6, 8), left feedback(3, 6), for this controller number
    private void hostSends(int controllerNumber) {
        hostSends(handler, controllerNumber);
    }

    private static void hostSends(ControllerHandler handler, int controllerNumber) {
        handler.handleSetAdaptiveTriggers((short) controllerNumber, (byte) 0x0C, (byte) 0x21, (byte) 0x25,
                FEEDBACK, WEAPON);
        idle();
    }

    @Test
    public void eachNewControllerIsShownToTheDualSenseCode() {
        controller(3, 0x045E, 0x0B13, 0);   // an Xbox pad
        assertEquals(0, link.calls.size());
        controller(7, PadMatcher.SONY_VENDOR_ID, PadMatcher.DUALSENSE_PRODUCT_ID, 1);
        assertEquals(DualSenseStatus.WORKING, dualSense.status());
        assertEquals("the connect report", 1, link.calls.size());
    }

    @Test
    public void theHostsEffectsGoOnlyToTheControllerTheyName() {
        controller(3, 0x045E, 0x0B13, 0);
        controller(7, PadMatcher.SONY_VENDOR_ID, PadMatcher.DUALSENSE_PRODUCT_ID, 1);
        hostSends(0);
        assertEquals("controller 0 is the Xbox pad", 1, link.calls.size());
        hostSends(2);
        assertEquals(1, link.calls.size());

        hostSends(1);
        assertEquals(2, link.calls.size());
        assertEquals("weapon(2, 6, 8) on R2", "2544000700000000000000", FakeHidHost.rightTrigger(link.lastTaken()));
        assertEquals("feedback(3, 6) on L2", "21f80300dab62d00000000", FakeHidHost.leftTrigger(link.lastTaken()));
    }

    @Test
    public void theHostsEffectsAreLoggedOnlyWithTheSwitchOn() {
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        prefs.dualSenseEffects = false;
        ControllerHandler switchedOff = new ControllerHandler(activity, null, null, prefs,
                DualSenseBluetooth.forStream(activity, prefs, context -> link, Looper.getMainLooper()));
        try (LimeLogLines log = LimeLogLines.listen()) {
            controller(switchedOff, 7, PadMatcher.SONY_VENDOR_ID, PadMatcher.DUALSENSE_PRODUCT_ID, 1);
            hostSends(switchedOff, 1);
            switchedOff.stop();
            idle();
            assertEquals("switch off", Collections.emptyList(), log.effects());
            assertEquals(0, link.calls.size());

            controller(7, PadMatcher.SONY_VENDOR_ID, PadMatcher.DUALSENSE_PRODUCT_ID, 1);
            hostSends(1);
            assertEquals("switch on", Arrays.asList(
                    "DualSense effect: t=0ms R2 mode=25 params=44000700000000000000 sent",
                    "DualSense effect: t=0ms L2 mode=21 params=f80300dab62d00000000 sent"), log.effects());
        }
    }

    @Test
    public void aRemovedControllerIsReported() {
        ControllerHandler.InputDeviceContext pad = controller(7, PadMatcher.SONY_VENDOR_ID,
                PadMatcher.DUALSENSE_PRODUCT_ID, 1);
        // Not a gamepad the host knows yet, so removing it sends the host nothing (there's no connection)
        pad.assignedControllerNumber = false;
        handler.onInputDeviceRemoved(7);
        idle();
        assertEquals(DualSenseStatus.NO_PAD, dualSense.status());
    }

    @Test
    public void stoppingTheStreamTurnsTheTriggersOff() {
        controller(7, PadMatcher.SONY_VENDOR_ID, PadMatcher.DUALSENSE_PRODUCT_ID, 1);
        hostSends(1);
        handler.stop();
        idle();
        assertEquals(3, link.calls.size());
        assertEquals(OFF, FakeHidHost.rightTrigger(link.lastTaken()));
        assertEquals(OFF, FakeHidHost.leftTrigger(link.lastTaken()));
        assertEquals(1, link.closes);

        // Nothing reaches the pad after the stream
        hostSends(1);
        assertEquals(3, link.calls.size());
    }
}
