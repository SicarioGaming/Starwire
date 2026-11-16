package com.limelight.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.R;

/**
 * The game screen's top row: the PC name at the start and the Starwire wordmark, centred or at
 * the end, both centred on the row's fixed height.
 *
 * The wordmark starts at the size it was given and steps down a dp at a time until it's at least
 * 8 dp clear of the PC name and its letters fit inside the row. It stops at cg_wordmark_min_size,
 * the size it had before it got bigger. Only if it would still run into the name there does it
 * go on down, to cg_wordmark_smallest_size at the least, which a very long name can still reach.
 *
 * It's fitted while the row is measured, so a new PC name, window size or font size fits it
 * again, and nothing runs while the screen only redraws (the rainbow, the header scrolling away).
 * The wordmark's text box can be taller than the row. It's centred on the row, so only the blank
 * space above and below the letters spills over, and the row and the game under it stay put.
 */
public class WordmarkRow extends FrameLayout {
    private final float gap;
    private final float step;
    private final float usual;
    private final float smallest;
    private final TextPaint measuring = new TextPaint();
    private final Rect ink = new Rect();
    private View name;
    private Wordmark mark;

    public WordmarkRow(Context context) {
        this(context, null);
    }

    public WordmarkRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        gap = 8 * density;
        step = density;
        // In whole pixels, as TextView reads a text size from a layout
        usual = getResources().getDimensionPixelSize(R.dimen.cg_wordmark_min_size);
        smallest = getResources().getDimensionPixelSize(R.dimen.cg_wordmark_smallest_size);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        name = findViewById(R.id.appListText);
        mark = findViewById(R.id.cgWordmark);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        float size = stepDown(mark.getWantedSize(), usual);
        if (!fits(size, 0)) {
            size = stepDown(size, smallest);
        }
        if (size != mark.getTextSize()) {
            // The row is being laid out already. Marking it so keeps the new size from asking
            // the whole window for another layout pass.
            forceLayout();
            mark.showAt(size);
        }
        // Measured without the row's height limit, so FrameLayout centres a text box taller than
        // the row instead of cutting the bottom of the letters off
        LayoutParams params = (LayoutParams) mark.getLayoutParams();
        mark.measure(getChildMeasureSpec(widthMeasureSpec,
                        getPaddingLeft() + getPaddingRight() + params.leftMargin + params.rightMargin, params.width),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    // The biggest size from this one down to the floor, a dp at a time, that's 8 dp clear of the
    // PC name and inside the row; the floor if none is
    private float stepDown(float size, float floor) {
        while (size > floor && !fits(size, gap)) {
            size = Math.max(floor, size - step);
        }
        return size;
    }

    // Whether the wordmark at this text size, in pixels, is this far from the PC name and inside
    // the row, where FrameLayout puts it
    private boolean fits(float size, float clearance) {
        CharSequence text = mark.getText();
        measuring.set(mark.getPaint());
        measuring.setTextSize(size);

        // Across, counted from the row's start: the PC name's side, left or right to left
        int width = (int) Math.ceil(Layout.getDesiredWidth(text, measuring))
                + mark.getCompoundPaddingLeft() + mark.getCompoundPaddingRight();
        int room = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        int gravity = ((LayoutParams) mark.getLayoutParams()).gravity;
        boolean centred = (Gravity.getAbsoluteGravity(gravity, getLayoutDirection())
                & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL;
        int start = centred ? (room - width) / 2 : room - width;
        int nameEnd = 0;
        if (name.getVisibility() != GONE) {
            LayoutParams params = (LayoutParams) name.getLayoutParams();
            nameEnd = params.leftMargin + name.getMeasuredWidth() + params.rightMargin;
        }
        if (start < 0 || start - nameEnd < clearance) {
            return false;
        }

        // Down, with the text box centred on the row
        Paint.FontMetricsInt metrics = measuring.getFontMetricsInt();
        boolean padded = mark.getIncludeFontPadding();
        int top = padded ? metrics.top : metrics.ascent;
        int bottom = padded ? metrics.bottom : metrics.descent;
        int height = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        int box = bottom - top + mark.getCompoundPaddingTop() + mark.getCompoundPaddingBottom();
        int baseline = (height - box) / 2 + mark.getCompoundPaddingTop() - top;
        measuring.getTextBounds(text.toString(), 0, text.length(), ink);
        return insideRow(height, baseline + ink.top, baseline + ink.bottom);
    }

    // Whether letters from top to bottom, in pixels down from the top of a row this tall, are
    // inside it
    static boolean insideRow(int height, int top, int bottom) {
        return top >= 0 && bottom <= height;
    }
}
