package com.limelight.ui;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Pinch to zoom and drag to pan the stream view while pan/zoom mode is on (from Artemide).
 * The view is scaled around its top-left corner and kept within its parent's bounds.
 * Double-tap resets to the unzoomed view.
 */
public class PanZoomHandler {
    private static final float MAX_SCALE = 10.0f;

    private final View streamView;
    private final ScaleGestureDetector scaleGestureDetector;
    private final GestureDetector gestureDetector;
    private float scaleFactor = 1.0f;
    private float childX, childY;
    private float parentWidth, parentHeight;
    private float childWidth, childHeight;

    // The view as the current touch gesture found it, so a gesture that turns out not
    // to be a pinch can put it back
    private float gestureScale = 1.0f;
    private float gestureX, gestureY;
    private boolean gestureStateSaved;

    public PanZoomHandler(Context context, View streamView) {
        this.streamView = streamView;
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    public void handleTouchEvent(MotionEvent motionEvent) {
        // Everything gets easier with 0,0 as the pivot point
        streamView.setPivotX(0);
        streamView.setPivotY(0);

        scaleGestureDetector.onTouchEvent(motionEvent);
        gestureDetector.onTouchEvent(motionEvent);
    }

    public boolean isZoomed() {
        return scaleFactor > 1.0f;
    }

    /**
     * Called when a new touch gesture starts, before its fingers can pinch or pan.
     */
    public void rememberGestureStart() {
        gestureScale = scaleFactor;
        gestureX = streamView.getX();
        gestureY = streamView.getY();
        gestureStateSaved = true;
    }

    /**
     * Undoes whatever the current gesture did to the view: extra fingers arriving
     * mean it was never meant to be a pinch. A zoom from an earlier gesture stays.
     */
    public void revertToGestureStart() {
        if (!gestureStateSaved) {
            return;
        }
        scaleFactor = gestureScale;
        streamView.setScaleX(scaleFactor);
        streamView.setScaleY(scaleFactor);
        streamView.setX(gestureX);
        streamView.setY(gestureY);
        gestureStateSaved = false;
    }

    public void reset() {
        scaleFactor = 1.0f;
        streamView.setScaleX(1.0f);
        streamView.setScaleY(1.0f);
        constrainToBounds();
    }

    /**
     * Keep the same part of the stream in view after the view or its parent is resized.
     */
    public void handleSurfaceChange() {
        if (childWidth == 0 || childHeight == 0) {
            return;
        }

        float prevChildWidth = childWidth;
        float prevChildHeight = childHeight;
        float prevParentWidth = parentWidth;
        float prevParentHeight = parentHeight;

        updateDimensions();

        float dPivotX = (childX - prevParentWidth / 2) * (childWidth / prevChildWidth);
        float dPivotY = (childY - prevParentHeight / 2) * (childHeight / prevChildHeight);

        childX = dPivotX + parentWidth / 2;
        childY = dPivotY + parentHeight / 2;

        constrainToBounds();
    }

    private void updateDimensions() {
        View parent = (View) streamView.getParent();
        childWidth = streamView.getWidth() * scaleFactor;
        childHeight = streamView.getHeight() * scaleFactor;
        parentWidth = parent.getWidth();
        parentHeight = parent.getHeight();
    }

    private void constrainToBounds() {
        updateDimensions();

        if (parentWidth >= childWidth) {
            childX = (parentWidth - childWidth) / 2;
        } else {
            childX = Math.max(parentWidth - childWidth, Math.min(childX, 0));
        }

        if (parentHeight >= childHeight) {
            childY = (parentHeight - childHeight) / 2;
        } else {
            childY = Math.max(parentHeight - childHeight, Math.min(childY, 0));
        }

        streamView.setX(childX);
        streamView.setY(childY);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float newScaleFactor = Math.max(1, Math.min(scaleFactor * detector.getScaleFactor(), MAX_SCALE));

            // Zoom around the point between the fingers
            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();
            childX = focusX + (streamView.getX() - focusX) / scaleFactor * newScaleFactor;
            childY = focusY + (streamView.getY() - focusY) / scaleFactor * newScaleFactor;

            scaleFactor = newScaleFactor;
            streamView.setScaleX(scaleFactor);
            streamView.setScaleY(scaleFactor);

            constrainToBounds();
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            childX = streamView.getX() - distanceX;
            childY = streamView.getY() - distanceY;
            constrainToBounds();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            reset();
            return true;
        }
    }
}
