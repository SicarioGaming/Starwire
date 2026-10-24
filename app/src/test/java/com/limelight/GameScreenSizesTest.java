package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The game screen is built again when its window changes to another set of sizes
 * (cover_glow_dimens.xml) or the font size changes. AppView handles these changes itself instead
 * of being recreated, so any change it doesn't build the screen again for leaves the old sizes
 * on screen.
 */
@RunWith(RobolectricTestRunner.class)
// AppView's settings read MoonBridge's constants; instrumenting it skips loading the native library
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class GameScreenSizesTest {
    @Test
    public void theWordmarkChangesSizeAt360dpWide() {
        // A phone held upright, then a window under 360 dp wide (split screen, a pop-up window
        // or a flip phone's cover screen), then the phone again
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-normal-long-port-xhdpi");
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), AppView.class)
                .putExtra(AppView.NAME_EXTRA, "GAMING-PC")
                .putExtra(AppView.UUID_EXTRA, "00000000-0000-0000-0000-000000000001");
        ActivityController<AppView> controller = Robolectric.buildActivity(AppView.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();
        float phone = wordmarkSize(controller);

        float narrow = resize(controller, "w340dp-h800dp-normal-long-port-xhdpi");
        assertTrue("a phone's wordmark should be bigger than one under 360 dp", phone > narrow);

        assertEquals(phone, resize(controller, "w400dp-h800dp-normal-long-port-xhdpi"), 0f);
    }

    @Test
    public void theHeaderFollowsANewFontSize() {
        // The font size changed in Android's settings while the game screen was open
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-normal-long-port-xhdpi");
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), AppView.class)
                .putExtra(AppView.NAME_EXTRA, "GAMING-PC")
                .putExtra(AppView.UUID_EXTRA, "00000000-0000-0000-0000-000000000001");
        ActivityController<AppView> controller = Robolectric.buildActivity(AppView.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();

        RuntimeEnvironment.setFontScale(1.3f);
        controller.configurationChange(
                new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration()));
        shadowOf(Looper.getMainLooper()).idle();
        AppView screen = controller.get();
        assertEquals(1.3f, screen.getResources().getConfiguration().fontScale, 0f);
        // In whole pixels, as TextView reads a text size from a layout
        TextView name = screen.findViewById(R.id.appListText);
        assertEquals("the PC name kept the size it had before the font size changed",
                Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16,
                        screen.getResources().getDisplayMetrics())), name.getTextSize(), 0f);
        assertEquals("the wordmark kept the size it had before the font size changed",
                screen.getResources().getDimensionPixelSize(R.dimen.cg_wordmark_size), wordmarkSize(controller), 0f);
    }

    // The wordmark's size after the window changes, checked against the size for that width
    private static float resize(ActivityController<AppView> controller, String qualifiers) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        controller.configurationChange(
                new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration()));
        shadowOf(Looper.getMainLooper()).idle();
        float size = wordmarkSize(controller);
        assertEquals("the wordmark kept the size it had before the window changed",
                controller.get().getResources().getDimension(R.dimen.cg_wordmark_size), size, 0f);
        return size;
    }

    private static float wordmarkSize(ActivityController<AppView> controller) {
        return ((TextView) controller.get().findViewById(R.id.cgWordmark)).getTextSize();
    }
}
