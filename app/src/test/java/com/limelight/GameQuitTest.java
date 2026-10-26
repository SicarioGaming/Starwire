package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.overlay.OverlayMenuButton;
import com.limelight.ui.overlay.OverlayMenuView;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

/**
 * Quit Session in the stream menu closes the game on the PC, losing anything unsaved, so it asks
 * first (Moonlight X e6014480). Yes quits; No or Back keeps streaming; a controller can answer.
 *
 * Game is created for a game but never connects: the stream starts once its surface exists, and
 * Robolectric never makes one. The host is a documentation address that nothing contacts.
 */
@RunWith(RobolectricTestRunner.class)
// A phone held sideways, 1600 x 720 px, where the menu's Close, Quit Session and Disconnect share
// one row. MoonBridge loads the native streaming library in its static block. Instrumenting it
// lets Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, qualifiers = "w800dp-h360dp-normal-long-land-xhdpi",
        instrumentedPackages = {"com.limelight.nvstream.jni"})
public class GameQuitTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private Game game;
    private OverlayMenuView menu;
    private Dialog spinner;

    @Before
    public void openStream() {
        // The services a stream binds to answer with nothing here: Starwire's USB controller
        // driver, and SHIELD's controller extensions
        ShadowApplication app = shadowOf(RuntimeEnvironment.getApplication());
        app.declareComponentUnbindable(new ComponentName(context, UsbDriverService.class));
        app.declareComponentUnbindable(new ComponentName("com.nvidia.blakepairing",
                "com.nvidia.blakepairing.AccessoryService"));

        Intent intent = new Intent(context, Game.class)
                .putExtra(Game.EXTRA_APP_ID, 1)
                .putExtra(Game.EXTRA_APP_NAME, "Hades II")
                .putExtra(Game.EXTRA_HOST, "192.0.2.1")
                .putExtra(Game.EXTRA_PC_UUID, "test-pc")
                .putExtra(Game.EXTRA_PC_NAME, "Test PC")
                .putExtra(Game.EXTRA_UNIQUEID, "0123456789ABCDEF");
        game = Robolectric.buildActivity(Game.class, intent).create().get();
        idle();
        assertFalse("the stream screen is up", game.isFinishing());
        menu = game.findViewById(R.id.overlayMenuView);
        // "Connecting", which stays up until a stream starts
        spinner = ShadowDialog.getLatestDialog();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private boolean quitting() {
        ControllerHandler controllers = ReflectionHelpers.getField(game, "controllerHandler");
        boolean userLeft = ReflectionHelpers.getField(game, "userInitiatedDisconnect");
        return controllers.pendingApplicationQuit || userLeft || game.isFinishing();
    }

    // Finds the menu button whose label is exactly this text
    private static View findButton(View root, String label) {
        if (root instanceof OverlayMenuButton) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView && label.contentEquals(((TextView) child).getText())) {
                    return root;
                }
            }
            return null;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findButton(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void openMenu() {
        menu.show();
        View window = game.getWindow().getDecorView();
        window.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, 1600, 720);
        idle();
        assertEquals(View.VISIBLE, menu.getVisibility());
    }

    private AlertDialog tapQuit() {
        openMenu();
        View quit = findButton(menu, game.getString(R.string.overlay_menu_quit_session));
        assertNotNull(quit);
        quit.performClick();
        idle();
        return question();
    }

    // The quit question, which has to be up, with the menu closed behind it
    private AlertDialog question() {
        Dialog shown = ShadowDialog.getLatestDialog();
        assertTrue("Quit didn't ask first", shown instanceof AlertDialog && shown != spinner);
        AlertDialog dialog = (AlertDialog) shown;
        assertTrue(dialog.isShowing());
        assertEquals(game.getString(R.string.applist_quit_confirmation),
                shadowOf(dialog).getMessage().toString());
        assertFalse("quit before an answer", quitting());
        assertEquals("the menu is still open behind the question", View.GONE, menu.getVisibility());
        return dialog;
    }

    // A key from a gamepad, as Android delivers it
    private static KeyEvent gamepadKey(int action, int keyCode) {
        long now = SystemClock.uptimeMillis();
        return new KeyEvent(now, now, action, keyCode, 0, 0, -1, 0, 0,
                InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_DPAD);
    }

    private static void press(View target, int keyCode) {
        target.dispatchKeyEvent(gamepadKey(KeyEvent.ACTION_DOWN, keyCode));
        target.dispatchKeyEvent(gamepadKey(KeyEvent.ACTION_UP, keyCode));
        idle();
    }

    private static void press(Dialog target, int keyCode) {
        target.dispatchKeyEvent(gamepadKey(KeyEvent.ACTION_DOWN, keyCode));
        target.dispatchKeyEvent(gamepadKey(KeyEvent.ACTION_UP, keyCode));
        idle();
    }

    @Test
    public void quitAsksFirstAndYesQuits() {
        AlertDialog dialog = tapQuit();
        assertEquals(game.getString(R.string.yes), dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        ControllerHandler controllers = ReflectionHelpers.getField(game, "controllerHandler");
        assertTrue("the game on the PC is quit", controllers.pendingApplicationQuit);
        assertTrue("the stream screen closes", game.isFinishing());
    }

    /**
     * With automatic clipboard sync on, leaving copies the PC's clipboard first, because the PC
     * stops answering once the stream ends. Yes still leaves that way, through leaveStream().
     */
    @Test
    public void yesCopiesThePcClipboardBeforeQuitting() {
        List<Runnable> copies = new ArrayList<>();
        game.hostClipboardCopy = copies::add;
        PreferenceConfiguration prefs = ReflectionHelpers.getField(game, "prefConfig");
        prefs.smartClipboardSync = true;
        ReflectionHelpers.setField(game, "connected", true);

        AlertDialog dialog = tapQuit();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("the PC's clipboard is copied first", 1, copies.size());
        assertFalse("quit before the copy finished", quitting());

        // The copy is done. Nothing really streams here, so stopConnection() can't talk to a PC.
        ReflectionHelpers.setField(game, "connected", false);
        copies.get(0).run();
        idle();
        ControllerHandler controllers = ReflectionHelpers.getField(game, "controllerHandler");
        assertTrue("the game on the PC is quit", controllers.pendingApplicationQuit);
        assertTrue("the stream screen closes", game.isFinishing());
    }

    @Test
    public void noKeepsStreaming() {
        AlertDialog dialog = tapQuit();
        assertEquals(game.getString(R.string.no), dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();
        assertFalse(dialog.isShowing());
        assertFalse(quitting());
    }

    @Test
    public void backKeepsStreaming() {
        AlertDialog dialog = tapQuit();
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        idle();
        assertFalse(dialog.isShowing());
        assertFalse(quitting());
    }

    /**
     * Picked and answered with a controller. Robolectric doesn't move focus with the D-pad or turn
     * buttons into the keys Android turns them into when an app doesn't use them (B into Back, A
     * into DPAD_CENTER on the focused button), so the dialog gets those keys as they arrive then.
     */
    @Test
    public void aControllerCanAnswer() {
        // Close is picked first, and Quit Session is next to it
        openMenu();
        press(menu, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(menu, KeyEvent.KEYCODE_BUTTON_A);
        AlertDialog first = question();
        assertFalse("the closed menu still holds the controller", menu.hasFocus());

        // B: Back
        press(first, KeyEvent.KEYCODE_BACK);
        assertFalse(first.isShowing());
        assertFalse(quitting());

        // Again, and A on Yes
        openMenu();
        press(menu, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(menu, KeyEvent.KEYCODE_BUTTON_A);
        AlertDialog second = question();
        assertNotSame(first, second);
        press(second.getButton(DialogInterface.BUTTON_POSITIVE), KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue("A on Yes quits", game.isFinishing());
    }
}
