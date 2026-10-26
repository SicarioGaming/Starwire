package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The performance stats box on the stream screen has rounded corners (Moonlight X 9b9a9499).
 * With no game in its Intent, Game.onCreate sets up the overlays and then finishes before
 * anything connects. Artemide's Lite stats keep their own pill.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class GameStatsBoxTest {
    @Test
    public void theStatsBoxHasRoundedCorners() {
        Context context = RuntimeEnvironment.getApplication();
        Game game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().get();
        View stats = game.findViewById(R.id.performanceOverlay);

        Drawable background = stats.getBackground();
        assertTrue("the stats box is a plain square: " + background, background instanceof GradientDrawable);
        GradientDrawable box = (GradientDrawable) background;
        float density = context.getResources().getDisplayMetrics().density;
        assertEquals(4 * density, box.getCornerRadius(), 0.01f);
        // As see-through as before
        assertEquals(0x60000000, box.getColor().getDefaultColor());
    }
}
