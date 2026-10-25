package com.limelight;

import android.content.Context;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A connection to no PC that writes down the input Game sends it, in order, for the JVM tests:
 * "down1" and "up1" for a mouse button (1 is the left one), "pos" for the cursor placed, "move"
 * for it moved, "scroll", "touch" with the event type, "pad" with the buttons held (in hex), and
 * "key" for a key or text. Touches are also written down in full, in touches.
 */
public class RecordingConnection extends NvConnection {
    public final List<String> sent = new ArrayList<>();
    // Each touch as "down 1 at 0.25,0.50": what happened, the finger's id, and where on the
    // stream, from 0 to 1 across and down
    public final List<String> touches = new ArrayList<>();
    // False for a PC that can't take touches, as GeForce Experience can't
    public boolean takesTouches = true;

    public RecordingConnection(Context context) {
        super(context, null, 0, "test", null, null, null);
    }

    @Override public void sendMouseButtonDown(byte button) { sent.add("down" + button); }
    @Override public void sendMouseButtonUp(byte button) { sent.add("up" + button); }
    @Override public void sendMousePosition(short x, short y, short width, short height) { sent.add("pos"); }
    @Override public void sendMouseMove(short dx, short dy) { sent.add("move"); }
    @Override public void sendMouseMoveAsMousePosition(short dx, short dy, short width, short height) { sent.add("move"); }
    @Override public void sendMouseScroll(byte clicks) { sent.add("scroll"); }
    @Override public void sendMouseHScroll(byte clicks) { sent.add("scroll"); }
    @Override public void sendMouseHighResScroll(short amount) { sent.add("scroll"); }
    @Override public void sendMouseHighResHScroll(short amount) { sent.add("scroll"); }

    @Override
    public int sendTouchEvent(byte eventType, int pointerId, float x, float y, float pressureOrDistance,
                              float contactAreaMajor, float contactAreaMinor, short rotation) {
        if (!takesTouches) {
            return MoonBridge.LI_ERR_UNSUPPORTED;
        }
        sent.add("touch" + eventType);
        touches.add(String.format(Locale.ROOT, "%s %d at %.2f,%.2f", touchName(eventType), pointerId, x, y));
        return 0;
    }

    private static String touchName(byte eventType) {
        switch (eventType) {
            case MoonBridge.LI_TOUCH_EVENT_DOWN: return "down";
            case MoonBridge.LI_TOUCH_EVENT_UP: return "up";
            case MoonBridge.LI_TOUCH_EVENT_MOVE: return "move";
            case MoonBridge.LI_TOUCH_EVENT_CANCEL: return "cancel";
            case MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL: return "cancel all";
            case MoonBridge.LI_TOUCH_EVENT_HOVER: return "hover";
            default: return "type " + eventType;
        }
    }

    @Override
    public void sendControllerInput(short controllerNumber, short activeGamepadMask, int buttonFlags,
                                    byte leftTrigger, byte rightTrigger, short leftStickX, short leftStickY,
                                    short rightStickX, short rightStickY) {
        sent.add("pad" + Integer.toHexString(buttonFlags));
    }

    @Override
    public void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags) {
        sent.add("key");
    }

    @Override
    public void sendUtf8Text(String text) {
        sent.add("key");
    }
}
