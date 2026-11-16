package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

public class AbsoluteTouchContext implements TouchContext {
    private int lastTouchDownX = 0;
    private int lastTouchDownY = 0;
    private long lastTouchDownTime = 0;
    private int lastTouchUpX = 0;
    private int lastTouchUpY = 0;
    private long lastTouchUpTime = 0;
    private int lastTouchLocationX = 0;
    private int lastTouchLocationY = 0;
    private boolean cancelled;
    private boolean confirmedLongPress;
    private boolean confirmedLongPressDrag;
    private boolean confirmedTap;

    // Tap and long-press buttons; swapped by Artemide's "left/right click swapped" mouse mode
    private final byte buttonPrimary;
    private final byte buttonSecondary;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            // Right button down at original position after a long press. Nothing has been
            // pressed before it.
            confirmedLongPress = true;
            updatePositionToTouchDown();
            conn.sendMouseButtonDown(buttonSecondary);
        }
    };

    private final NvConnection conn;
    private final int actionIndex;
    private final View targetView;
    private final Handler handler;

    private final Runnable leftButtonUpRunnable = new Runnable() {
        @Override
        public void run() {
            conn.sendMouseButtonUp(buttonPrimary);
        }
    };

    private static final int SCROLL_SPEED_FACTOR = 3;

    private static final int LONG_PRESS_TIME_THRESHOLD = 650;
    private static final int LONG_PRESS_DISTANCE_THRESHOLD = 30;

    private static final int DOUBLE_TAP_TIME_THRESHOLD = 250;
    private static final int DOUBLE_TAP_DISTANCE_THRESHOLD = 60;

    private static final int TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD = 20;

    public AbsoluteTouchContext(NvConnection conn, int actionIndex, View view)
    {
        this(conn, actionIndex, view, false);
    }

    public AbsoluteTouchContext(NvConnection conn, int actionIndex, View view, boolean swapped)
    {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.targetView = view;
        this.handler = new Handler(Looper.getMainLooper());

        if (swapped) {
            buttonPrimary = MouseButtonPacket.BUTTON_RIGHT;
            buttonSecondary = MouseButtonPacket.BUTTON_LEFT;
        }
        else {
            buttonPrimary = MouseButtonPacket.BUTTON_LEFT;
            buttonSecondary = MouseButtonPacket.BUTTON_RIGHT;
        }
    }

    @Override
    public int getActionIndex()
    {
        return actionIndex;
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger)
    {
        if (!isNewFinger) {
            // We don't handle finger transitions for absolute mode
            return true;
        }

        lastTouchLocationX = lastTouchDownX = eventX;
        lastTouchLocationY = lastTouchDownY = eventY;
        lastTouchDownTime = eventTime;
        cancelled = confirmedTap = confirmedLongPress = confirmedLongPressDrag = false;

        if (actionIndex == 0) {
            // Start the long press timer. No button goes down until we know whether this is a
            // tap, a drag or a long press. Sunshine's input.cpp, which Vibepollo uses, merges a
            // left button release followed by a right button press into a right click inside the
            // left click, so the left button can't go down first and give way to the right one.
            startLongPressTimer();
        }

        return true;
    }

    private boolean distanceExceeds(int deltaX, int deltaY, double limit) {
        return Math.sqrt(Math.pow(deltaX, 2) + Math.pow(deltaY, 2)) > limit;
    }

    private void updatePosition(int eventX, int eventY) {
        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0), targetView.getWidth());
        eventY = Math.min(Math.max(eventY, 0), targetView.getHeight());

        conn.sendMousePosition((short)eventX, (short)eventY, (short)targetView.getWidth(), (short)targetView.getHeight());
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return;
        }

        if (actionIndex == 0) {
            // Cancel the timer
            cancelLongPressTimer();

            // Raise the mouse buttons that we currently have down
            if (confirmedLongPress) {
                conn.sendMouseButtonUp(buttonSecondary);
            }
            else if (confirmedTap) {
                conn.sendMouseButtonUp(buttonPrimary);
            }
            else {
                // If we get here, this means that the tap completed before a long press without
                // moving away. We'll need to send the touch down and up events now at the
                // original touch down position.
                tapConfirmed();

                // Release the left mouse button in 100ms to allow for apps that use polling
                // to detect mouse button presses.
                handler.removeCallbacks(leftButtonUpRunnable);
                handler.postDelayed(leftButtonUpRunnable, 100);
            }
        }

        lastTouchLocationX = lastTouchUpX = eventX;
        lastTouchLocationY = lastTouchUpY = eventY;
        lastTouchUpTime = eventTime;
    }

    private void startLongPressTimer() {
        cancelLongPressTimer();
        handler.postDelayed(longPressRunnable, LONG_PRESS_TIME_THRESHOLD);
    }

    private void cancelLongPressTimer() {
        handler.removeCallbacks(longPressRunnable);
    }

    private void updatePositionToTouchDown() {
        if (lastTouchDownTime - lastTouchUpTime > DOUBLE_TAP_TIME_THRESHOLD ||
                distanceExceeds(lastTouchDownX - lastTouchUpX, lastTouchDownY - lastTouchUpY, DOUBLE_TAP_DISTANCE_THRESHOLD)) {
            // Don't reposition for finger down events within the deadzone. This makes double-clicking easier.
            updatePosition(lastTouchDownX, lastTouchDownY);
        }
    }

    private void tapConfirmed() {
        if (confirmedTap || confirmedLongPress) {
            return;
        }

        confirmedTap = true;

        // Left button down at original position
        updatePositionToTouchDown();
        conn.sendMouseButtonDown(buttonPrimary);
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return true;
        }

        if (actionIndex == 0) {
            if (distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, LONG_PRESS_DISTANCE_THRESHOLD)) {
                // Moved too far since touch down. Cancel the long press timer, and start a drag
                // at the original position unless the long press came first.
                cancelLongPressTimer();
                tapConfirmed();
            }

            // Ignore motion within the deadzone after a long press. Once the finger leaves it,
            // the cursor follows the finger as in a drag.
            if (confirmedLongPress &&
                    distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD)) {
                confirmedLongPressDrag = true;
            }

            // Ignore motion until we're dragging
            if (confirmedTap || confirmedLongPressDrag) {
                updatePosition(eventX, eventY);
            }
        }
        else if (actionIndex == 1) {
            conn.sendMouseHighResScroll((short)((eventY - lastTouchLocationY) * SCROLL_SPEED_FACTOR));
        }

        lastTouchLocationX = eventX;
        lastTouchLocationY = eventY;

        return true;
    }

    @Override
    public void cancelTouch() {
        cancelled = true;

        // Cancel the timer
        cancelLongPressTimer();

        // Raise the mouse buttons
        if (confirmedLongPress) {
            conn.sendMouseButtonUp(buttonSecondary);
        }
        else if (confirmedTap) {
            conn.sendMouseButtonUp(buttonPrimary);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        if (actionIndex == 0 && pointerCount > 1) {
            cancelTouch();
        }
    }
}
