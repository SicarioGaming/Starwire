package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The Starwire wordmark at the top of the game screen. It takes a text size like any TextView,
 * and WordmarkRow may show it smaller to keep it clear of the PC name. The size it was given is
 * the one it starts from each time, so it grows back when there's room again.
 */
public class Wordmark extends TextView {
    // The size it was given, by its layout or by code, in pixels
    private float wanted;

    public Wordmark(Context context) {
        this(context, null);
    }

    public Wordmark(Context context, AttributeSet attrs) {
        super(context, attrs);
        wanted = getTextSize();
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        wanted = getTextSize();
    }

    /** The size it was given, in pixels, before WordmarkRow fitted it. */
    float getWantedSize() {
        return wanted;
    }

    /** Shows it at this size, in pixels, and keeps the size it was given. */
    void showAt(float size) {
        super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }
}
