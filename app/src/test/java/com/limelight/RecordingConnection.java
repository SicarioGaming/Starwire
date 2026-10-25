package com.limelight;

import android.content.Context;

import com.limelight.nvstream.NvConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * A connection to no PC that writes down the input Game sends it, in order, for the JVM tests:
 * "down1" and "up1" for a mouse button (1 is the left one), "pos" for the cursor placed, "move"
 * for it moved, "scroll", "touch" with the event type, "pad" with the buttons held (in hex), and
 * "key" for a key or text.
 */
public class RecordingConnection extends NvConnection {
    public final List<String> sent = new ArrayList<>();

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
        sent.add("touch" + eventType);
        return 0;
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
