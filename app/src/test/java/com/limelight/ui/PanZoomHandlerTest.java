package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The stream view as a touch gesture found it: extra fingers arriving mean the gesture was
 * never a pinch, so the view goes back the way it was — including a zoom an earlier, real
 * pinch left behind.
 */
@RunWith(RobolectricTestRunner.class)
public class PanZoomHandlerTest {
    private View streamView;
    private PanZoomHandler handler;

    @Before
    public void setUp() {
        streamView = new View(RuntimeEnvironment.getApplication());
        FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication());
        parent.addView(streamView);
        parent.layout(0, 0, 400, 300);
        handler = new PanZoomHandler(RuntimeEnvironment.getApplication(), streamView);
    }

    private static MotionEvent event(int action, float... xys) {
        int count = xys.length / 2;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xys[i * 2];
            coords[i].y = xys[i * 2 + 1];
        }
        return MotionEvent.obtain(0, 0, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
    }

    // Two fingers down, then spread or squeeze through each given half-span. The first move
    // starts the scale gesture; the ones after give it a real factor.
    private void pinch(float... halfSpans) {
        float start = halfSpans[0];
        handler.handleTouchEvent(event(MotionEvent.ACTION_DOWN, 200 - start, 150));
        handler.handleTouchEvent(event(
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                200 - start, 150, 200 + start, 150));
        for (int i = 1; i < halfSpans.length; i++) {
            float span = halfSpans[i];
            handler.handleTouchEvent(event(MotionEvent.ACTION_MOVE, 200 - span, 150, 200 + span, 150));
        }
    }

    @Test
    public void extraFingersUndoThePinchTheyInterrupted() {
        handler.rememberGestureStart();

        // The first fingers of a would-be tap spread like a pinch before the rest land
        pinch(50, 150, 200);
        assertTrue("the pinch zoomed", streamView.getScaleX() > 1.0f);

        handler.revertToGestureStart();
        assertEquals(1.0f, streamView.getScaleX(), 0.001f);
        assertEquals(1.0f, streamView.getScaleY(), 0.001f);
    }

    @Test
    public void aZoomFromAnEarlierGestureSurvives() {
        // An earlier gesture left the view zoomed; the next one remembers that as its start
        pinch(50, 150, 200);
        assertTrue(streamView.getScaleX() > 1.0f);
        float zoomed = streamView.getScaleX();

        handler.rememberGestureStart();
        pinch(50, 250, 300);
        handler.revertToGestureStart();

        assertEquals(zoomed, streamView.getScaleX(), 0.001f);
    }

    @Test
    public void revertingWithoutAGestureDoesNothing() {
        pinch(50, 150, 200);
        float zoomed = streamView.getScaleX();

        handler.revertToGestureStart();
        handler.revertToGestureStart();

        assertEquals(zoomed, streamView.getScaleX(), 0.001f);
    }
}
