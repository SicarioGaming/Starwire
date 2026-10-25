package com.limelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.ui.overlay.OverlayMenuButton;
import com.limelight.ui.overlay.OverlayMenuView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The stream menu's Shortcuts page as the stream screen sends it: a tap on a tile goes through
 * Game's own key sender to the connection, which records each key here instead of sending it to
 * a PC. With no game in its Intent, Game.onCreate finds the menu and gives it the game's name
 * and cover, then finishes before it makes a connection or gives the menu its listener.
 * OverlayMenuViewTest (on a device) covers the rest of the menu.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class GameShortcutsTest {
    // Windows virtual key codes
    private static final short WIN = 0x5B;
    private static final short CTRL = 0xA2;
    private static final short TAB = 0x09;
    private static final short LEFT = 0x25;
    private static final short RIGHT = 0x27;

    private static final byte DOWN = KeyboardPacket.KEY_DOWN;
    private static final byte UP = KeyboardPacket.KEY_UP;
    private static final byte META = KeyboardPacket.MODIFIER_META;
    private static final byte META_CTRL = KeyboardPacket.MODIFIER_META | KeyboardPacket.MODIFIER_CTRL;

    private final Context context = RuntimeEnvironment.getApplication();
    // Each key the connection was given: its code, down or up, the modifier flags and the flags
    private final List<short[]> sent = new ArrayList<>();

    private Game game;
    private OverlayMenuView menu;

    // Nothing here connects. The stream key the constructor makes is never used.
    private class RecordingConnection extends NvConnection {
        RecordingConnection() {
            super(context, null, 0, null, null, null, null);
        }

        @Override
        public void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags) {
            sent.add(new short[] {keyMap, keyDirection, modifier, flags});
        }
    }

    @Before
    public void openStreamScreen() {
        game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().get();
        ReflectionHelpers.setField(game, "conn", new RecordingConnection());
        menu = game.findViewById(R.id.overlayMenuView);
        // Game's own listener comes from setupOverlayMenu, which onCreate reaches only for a
        // real game, after it makes the connection and the controller handler. That listener's
        // onSendKeys hands the keys to sendKeys, and so does this one. A proxy, so a new menu
        // action needs nothing here.
        menu.setMenuActionListener((OverlayMenuView.MenuActionListener) Proxy.newProxyInstance(
                OverlayMenuView.MenuActionListener.class.getClassLoader(),
                new Class<?>[] {OverlayMenuView.MenuActionListener.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("onSendKeys")) {
                        ReflectionHelpers.callInstanceMethod(game, "sendKeys",
                                ReflectionHelpers.ClassParameter.from(short[].class, args[0]));
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                }));
        menu.show();
        click(context.getString(R.string.overlay_menu_shortcuts));
    }

    private void click(String label) {
        View tile = findTile(menu, label);
        assertNotNull("no \"" + label + "\" on the page", tile);
        tile.performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    // Taps the tile, then waits long enough for the keys to come back up
    private void send(String label) {
        click(label);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }

    private void assertSent(short[]... expected) {
        assertEquals("keys sent", expected.length, sent.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals("key " + i, expected[i], sent.get(i));
        }
    }

    @Test
    public void theShortcutsPageEndsWithTaskViewAndTheDesktops() {
        // The tiles, not the bar below them
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < menu.getChildCount(); i++) {
            if (menu.getChildAt(i) instanceof ScrollView) {
                collectLabels(menu.getChildAt(i), labels);
            }
        }
        // After Artemide's set, which ends with Win+Alt+B, and before Back
        String winAltB = context.getString(R.string.overlay_menu_send_win_alt_b);
        assertEquals(Arrays.asList(winAltB, "Win+Tab", "Win+Ctrl+Right", "Win+Ctrl+Left",
                        context.getString(R.string.overlay_menu_back)),
                labels.subList(labels.indexOf(winAltB), labels.size()));

        // TalkBack says what each one does, then its keys
        assertEquals("Task View, Win+Tab", findTile(menu, "Win+Tab").getContentDescription().toString());
        assertEquals("Next desktop, Win+Ctrl+Right",
                findTile(menu, "Win+Ctrl+Right").getContentDescription().toString());
        assertEquals("Previous desktop, Win+Ctrl+Left",
                findTile(menu, "Win+Ctrl+Left").getContentDescription().toString());
    }

    // Win goes down first and comes up last, with Tab pressed in between, so Windows opens Task
    // View and not the Start menu
    @Test
    public void winTabHoldsWinAroundTab() {
        send("Win+Tab");
        assertSent(
                new short[] {WIN, DOWN, 0, 0},
                new short[] {TAB, DOWN, META, 0},
                new short[] {TAB, UP, META, 0},
                new short[] {WIN, UP, 0, 0});
    }

    @Test
    public void winCtrlRightHoldsBothModifiersAroundRight() {
        send("Win+Ctrl+Right");
        assertSent(
                new short[] {WIN, DOWN, 0, 0},
                new short[] {CTRL, DOWN, META, 0},
                new short[] {RIGHT, DOWN, META_CTRL, 0},
                new short[] {RIGHT, UP, META_CTRL, 0},
                new short[] {CTRL, UP, META, 0},
                new short[] {WIN, UP, 0, 0});
    }

    @Test
    public void winCtrlLeftHoldsBothModifiersAroundLeft() {
        send("Win+Ctrl+Left");
        assertSent(
                new short[] {WIN, DOWN, 0, 0},
                new short[] {CTRL, DOWN, META, 0},
                new short[] {LEFT, DOWN, META_CTRL, 0},
                new short[] {LEFT, UP, META_CTRL, 0},
                new short[] {CTRL, UP, META, 0},
                new short[] {WIN, UP, 0, 0});
    }

    // The menu tile whose label is exactly this text
    private static View findTile(View root, String label) {
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
                View found = findTile(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // Every tile's label, in the order the page shows them
    private static void collectLabels(View root, List<String> labels) {
        if (root instanceof OverlayMenuButton) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof TextView) {
                    labels.add(((TextView) group.getChildAt(i)).getText().toString());
                }
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectLabels(group.getChildAt(i), labels);
            }
        }
    }
}
