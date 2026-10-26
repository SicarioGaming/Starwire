package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What Back does on the stream screen. With no game in its Intent, Game.onCreate sets up the
 * menu and then finishes before anything connects, so no stream starts. Each test sets up the
 * rest of the input handling as onCreate would, with a stand-in for the PC that writes down what
 * it gets, and forgets that finish, so a finish from Back shows. Keys go in through
 * Game.dispatchKeyEvent, as Android hands them over. The clock is Robolectric's, so holding a
 * key takes no real time.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class GameBackKeyTest {
    private static final String BACK_OPENS_MENU = "checkbox_back_opens_menu";
    // Keys that come with no input device (device ID 0)
    private static final int NO_DEVICE = 0;
    private static final int REMOTE_SOURCES = InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD;

    private final Context context = RuntimeEnvironment.getApplication();
    private final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

    private Game game;
    private View menu;
    private RecordingConnection pc;
    private ControllerHandler controllers;

    /** Stands in for the PC, writing down the mouse buttons, keys and controller buttons it gets. */
    private static class RecordingConnection extends NvConnection {
        final List<String> sent = new ArrayList<>();

        RecordingConnection(Context context) {
            super(context, null, 0, null, null, null, null);
        }

        @Override
        public void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags) {
            sent.add("key " + (keyDirection == KeyboardPacket.KEY_DOWN ? "down " : "up ")
                    + Integer.toHexString(keyMap & 0xFFFF) + (modifier != 0 ? " modifiers " + modifier : ""));
        }

        @Override
        public void sendMouseButtonDown(byte mouseButton) {
            sent.add("mouse down " + mouseButton);
        }

        @Override
        public void sendMouseButtonUp(byte mouseButton) {
            sent.add("mouse up " + mouseButton);
        }

        @Override
        public void sendControllerInput(short controllerNumber, short activeGamepadMask, int buttonFlags,
                                        byte leftTrigger, byte rightTrigger, short leftStickX, short leftStickY,
                                        short rightStickX, short rightStickY) {
            sent.add("controller " + Integer.toHexString(buttonFlags));
        }
    }

    private void openStreamScreen() {
        // Shown, so its window keeps track of a key from down to up as a real one does
        game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().visible().get();
        shadowOf(game).resetIsFinishing();
        menu = game.findViewById(R.id.overlayMenuView);
        assertEquals("the menu starts closed", View.GONE, menu.getVisibility());

        // What onCreate sets up after the point where a stream with no game stops. This isn't an
        // NVIDIA SHIELD, so the SHIELD's controller service isn't there for ControllerHandler.
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName("com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));
        pc = new RecordingConnection(context);
        PreferenceConfiguration prefConfig = ReflectionHelpers.getField(game, "prefConfig");
        controllers = new ControllerHandler(game, pc, game, prefConfig);
        ReflectionHelpers.setField(game, "conn", pc);
        ReflectionHelpers.setField(game, "controllerHandler", controllers);
        ReflectionHelpers.setField(game, "keyboardTranslator", new KeyboardTranslator());
        ReflectionHelpers.callInstanceMethod(game, "setupOverlayMenu");
    }

    @After
    public void closeControllers() {
        if (controllers != null) {
            controllers.destroy();
        }
    }

    private void key(int action, long downTime, int keyCode, int deviceId, int source, int flags) {
        game.dispatchKeyEvent(new KeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode, 0, 0,
                deviceId, 0, flags, source));
    }

    private void press(int keyCode, int deviceId, int source, int flags) {
        long downTime = SystemClock.uptimeMillis();
        key(KeyEvent.ACTION_DOWN, downTime, keyCode, deviceId, source, flags);
        key(KeyEvent.ACTION_UP, downTime, keyCode, deviceId, source, flags);
    }

    // The navigation bar's Back button and the back gesture
    private void pressSystemBack() {
        press(KeyEvent.KEYCODE_BACK, KeyCharacterMap.VIRTUAL_KEYBOARD, InputDevice.SOURCE_KEYBOARD,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY);
    }

    // A TV remote is a keyboard with a D-pad but not a full keyboard, so ControllerHandler takes
    // it as a controller, and its Back as a remote's Back. Robolectric can't give a remote the
    // vibrators, lights and sensors ControllerHandler looks at, so this Back comes with no device.
    // ControllerHandler takes such a Back as a remote's too, the same way.
    private void pressRemoteBack() {
        assertRemoteBack();
        press(KeyEvent.KEYCODE_BACK, NO_DEVICE, REMOTE_SOURCES, 0);
    }

    private void assertRemoteBack() {
        Object noDeviceContext = ReflectionHelpers.getField(controllers, "defaultContext");
        assertTrue("a Back with no device isn't taken as a remote's",
                (Boolean) ReflectionHelpers.getField(noDeviceContext, "ignoreBack"));
    }

    private void turnBackOpensMenuOff() {
        prefs.edit().putBoolean(BACK_OPENS_MENU, false).commit();
    }

    @Test
    public void backOpensTheMenuByDefault() {
        openStreamScreen();
        pressSystemBack();
        assertEquals("Back didn't open the menu", View.VISIBLE, menu.getVisibility());
        assertFalse("Back ended the stream", game.isFinishing());
    }

    @Test
    public void theSettingIsOnInTheInStreamMenuSection() throws Exception {
        // So the settings screen shows what the stream does when nothing is saved
        String xml = RepoFiles.read("app/src/main/res/xml/preferences.xml");
        int section = xml.indexOf("android:key=\"category_overlay_menu\"");
        int at = xml.indexOf("android:key=\"" + BACK_OPENS_MENU + "\"");
        int end = xml.indexOf("/>", at);
        assertTrue("not in the In-stream menu section",
                section >= 0 && at > section && at < xml.indexOf("</PreferenceCategory>", section));
        assertTrue(xml.substring(at, end), xml.substring(at, end).contains("android:defaultValue=\"true\""));
    }

    @Test
    public void theStreamScreenKeepsTheOldBack() throws Exception {
        // With predictive Back, Android doesn't call onBackPressed, so Back would leave the stream
        // without opening the menu. Robolectric doesn't act on the opt-out: the tests here still
        // pass with Game opted in. So this reads Game's entry in the manifest.
        String xml = RepoFiles.read("app/src/main/AndroidManifest.xml");
        int at = xml.indexOf("android:name=\".Game\"");
        assertTrue("Game isn't in the manifest", at >= 0);
        String entry = xml.substring(at, xml.indexOf('>', at));
        assertTrue(entry, entry.contains("android:enableOnBackInvokedCallback=\"false\""));
    }

    @Test
    public void backAgainClosesTheMenu() {
        openStreamScreen();
        pressSystemBack();
        assertEquals("the first Back didn't open the menu", View.VISIBLE, menu.getVisibility());
        assertFalse("the first Back ended the stream", game.isFinishing());
        pressSystemBack();
        assertEquals("the second Back didn't close the menu", View.GONE, menu.getVisibility());
        assertFalse("the second Back ended the stream", game.isFinishing());

        // And a third opens it again
        pressSystemBack();
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertFalse(game.isFinishing());
    }

    @Test
    public void withTheSettingOffBackEndsTheStream() {
        turnBackOpensMenuOff();
        openStreamScreen();
        pressSystemBack();
        assertTrue("Back didn't end the stream", game.isFinishing());
        assertEquals("Back opened the menu", View.GONE, menu.getVisibility());
    }

    @Test
    public void aRemotesBackOpensTheMenu() {
        openStreamScreen();
        pressRemoteBack();
        assertEquals("the remote's Back didn't open the menu", View.VISIBLE, menu.getVisibility());
        assertFalse("the remote's Back ended the stream", game.isFinishing());

        // The remote's Back closes it again
        pressRemoteBack();
        assertEquals("the remote's second Back didn't close the menu", View.GONE, menu.getVisibility());
        assertFalse(game.isFinishing());
    }

    @Test
    public void withTheSettingOffARemotesBackEndsTheStream() {
        turnBackOpensMenuOff();
        openStreamScreen();
        pressRemoteBack();
        assertTrue("the remote's Back didn't end the stream", game.isFinishing());
        assertEquals(View.GONE, menu.getVisibility());
    }

    @Test
    public void holdingARemotesBackStillOpensTheMenuOnce() {
        openStreamScreen();
        holdRemoteBack();
    }

    @Test
    public void withTheSettingOffHoldingARemotesBackStillOpensTheMenu() {
        turnBackOpensMenuOff();
        openStreamScreen();
        holdRemoteBack();
    }

    // Moonlight X opens the menu when a remote's Back is held, whatever the setting
    private void holdRemoteBack() {
        assertRemoteBack();
        long downTime = SystemClock.uptimeMillis();
        key(KeyEvent.ACTION_DOWN, downTime, KeyEvent.KEYCODE_BACK, NO_DEVICE, REMOTE_SOURCES, 0);
        PreferenceConfiguration prefConfig = ReflectionHelpers.getField(game, "prefConfig");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(prefConfig.overlayHoldDurationMs));
        assertEquals("holding Back didn't open the menu", View.VISIBLE, menu.getVisibility());

        // Letting go leaves it open
        key(KeyEvent.ACTION_UP, downTime, KeyEvent.KEYCODE_BACK, NO_DEVICE, REMOTE_SOURCES, 0);
        assertEquals("letting go of Back closed the menu", View.VISIBLE, menu.getVisibility());
        assertFalse(game.isFinishing());
    }

    @Test
    public void aMouseRightClickTurnedIntoBackStaysARightClick() {
        openStreamScreen();
        // Some Android versions send a right-click as Back from the mouse
        press(KeyEvent.KEYCODE_BACK, NO_DEVICE, InputDevice.SOURCE_MOUSE, 0);
        assertEquals("the PC didn't get a right-click",
                Arrays.asList("mouse down " + MouseButtonPacket.BUTTON_RIGHT, "mouse up " + MouseButtonPacket.BUTTON_RIGHT),
                pc.sent);
        assertEquals("the right-click opened the menu", View.GONE, menu.getVisibility());
        assertFalse("the right-click ended the stream", game.isFinishing());
    }

    @Test
    public void controllerBGoesToTheGame() {
        openStreamScreen();
        press(KeyEvent.KEYCODE_BUTTON_B, NO_DEVICE, InputDevice.SOURCE_GAMEPAD, 0);
        assertEquals("the game didn't get B",
                Arrays.asList("controller " + Integer.toHexString(ControllerPacket.B_FLAG), "controller 0"),
                pc.sent);
        assertEquals("B opened the menu", View.GONE, menu.getVisibility());
        assertFalse("B ended the stream", game.isFinishing());
    }

    // The open menu takes every key, so a button or key let go of while it's open would stay
    // held on the PC until it's pressed again. Opening the menu lets go of them first.

    private static final String A_DOWN = "controller " + Integer.toHexString(ControllerPacket.A_FLAG);

    private void holdA() {
        key(KeyEvent.ACTION_DOWN, SystemClock.uptimeMillis(), KeyEvent.KEYCODE_BUTTON_A, NO_DEVICE,
                InputDevice.SOURCE_GAMEPAD, 0);
        assertEquals(Arrays.asList(A_DOWN), pc.sent);
    }

    private void assertThePcLetGoOfA() {
        String last = null;
        for (String packet : pc.sent) {
            if (packet.startsWith("controller ")) {
                last = packet;
            }
        }
        assertEquals("the PC still holds A", "controller 0", last);
    }

    private void tapTheFloatingButton() {
        game.findViewById(R.id.floatingMenuButton).performClick();
        assertEquals("the floating button didn't open the menu", View.VISIBLE, menu.getVisibility());
    }

    @Test
    public void aButtonHeldWhenBackOpensTheMenuIsLetGo() {
        openStreamScreen();
        holdA();
        pressSystemBack();
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertThePcLetGoOfA();
    }

    @Test
    public void aButtonHeldWhenTheFloatingButtonOpensTheMenuIsLetGo() {
        openStreamScreen();
        holdA();
        tapTheFloatingButton();
        assertThePcLetGoOfA();
    }

    @Test
    public void aButtonHeldWhenARemotesBackOpensTheMenuIsLetGo() {
        openStreamScreen();
        holdA();
        pressRemoteBack();
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertThePcLetGoOfA();
    }

    // Shift and W from a keyboard, held down until the test lets go. The PC gets them as 0x80 and
    // the Windows key code: A0 for left Shift and 57 for W.
    private long holdShiftAndW() {
        long downTime = SystemClock.uptimeMillis();
        key(KeyEvent.ACTION_DOWN, downTime, KeyEvent.KEYCODE_SHIFT_LEFT, NO_DEVICE, InputDevice.SOURCE_KEYBOARD, 0);
        key(KeyEvent.ACTION_DOWN, downTime, KeyEvent.KEYCODE_W, NO_DEVICE, InputDevice.SOURCE_KEYBOARD, 0);
        assertEquals(Arrays.asList("key down 80a0 modifiers 1", "key down 8057 modifiers 1"), pc.sent);
        return downTime;
    }

    private void assertThePcLetGoOfShiftAndW() {
        Set<String> keys = new HashSet<>();
        for (String sent : pc.sent.subList(2, pc.sent.size())) {
            if (sent.startsWith("key ")) {
                keys.add(sent);
            }
        }
        assertEquals("the PC still holds Shift or W", new HashSet<>(Arrays.asList("key up 8057", "key up 80a0")), keys);
    }

    @Test
    public void keysHeldWhenBackOpensTheMenuAreLetGo() {
        openStreamScreen();
        long downTime = holdShiftAndW();
        pressSystemBack();
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertThePcLetGoOfShiftAndW();

        // Let go of in the menu, and the menu closed again
        key(KeyEvent.ACTION_UP, downTime, KeyEvent.KEYCODE_W, NO_DEVICE, InputDevice.SOURCE_KEYBOARD, 0);
        key(KeyEvent.ACTION_UP, downTime, KeyEvent.KEYCODE_SHIFT_LEFT, NO_DEVICE, InputDevice.SOURCE_KEYBOARD, 0);
        pressSystemBack();
        assertEquals(View.GONE, menu.getVisibility());

        // The next key doesn't still come with Shift
        pc.sent.clear();
        press(KeyEvent.KEYCODE_W, NO_DEVICE, InputDevice.SOURCE_KEYBOARD, 0);
        assertEquals(Arrays.asList("key down 8057", "key up 8057"), pc.sent);
    }

    @Test
    public void keysHeldWhenTheFloatingButtonOpensTheMenuAreLetGo() {
        openStreamScreen();
        holdShiftAndW();
        tapTheFloatingButton();
        assertThePcLetGoOfShiftAndW();
    }
}
