package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
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
import org.robolectric.annotation.GraphicsMode;

/**
 * The game screen is built again when its window changes to another set of sizes
 * (cover_glow_dimens.xml) or the font size changes. AppView handles these changes itself instead
 * of being recreated, so any change it doesn't build the screen again for leaves the old sizes
 * on screen. Within a set of sizes it isn't built again, and the wordmark grows back by itself
 * when the window has room for it.
 */
@RunWith(RobolectricTestRunner.class)
// Real text measurement, so WordmarkRow fits the wordmark. Without it every letter is 1 px wide.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// AppView's settings read MoonBridge's constants; instrumenting it skips loading the native library
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class GameScreenSizesTest {
    @Test
    public void theWordmarkChangesSizeAt360dpWide() {
        // A phone held upright, then a window under 360 dp wide (split screen, a pop-up window
        // or a flip phone's cover screen), then the phone again
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-normal-long-port-xhdpi");
        ActivityController<AppView> controller = open("GAMING-PC");
        float phone = wordmarkSize(controller);

        float narrow = resize(controller, "w340dp-h800dp-normal-long-port-xhdpi");
        assertTrue("a phone's wordmark should be bigger than one under 360 dp", phone > narrow);

        assertEquals(phone, resize(controller, "w400dp-h800dp-normal-long-port-xhdpi"), 0f);
    }

    @Test
    public void theWordmarkGrowsBackInAWiderWindow() {
        // A 600 dp tablet held upright, where the wordmark shrinks to clear a PC name as long as
        // the ones Windows gives, then a 900 dp window with the same set of sizes. The screen
        // isn't built again for that, so the same wordmark has to grow back by itself.
        RuntimeEnvironment.setQualifiers("w600dp-h960dp-large-notlong-port-xhdpi");
        ActivityController<AppView> controller = open("DESKTOP-WMQ7K2H");
        TextView mark = controller.get().findViewById(R.id.cgWordmark);
        // 40 sp (values-w480dp-h520dp), in whole pixels, as TextView reads a text size from a layout
        float full = controller.get().getResources().getDimensionPixelSize(R.dimen.cg_wordmark_size);
        assertTrue("the wordmark should shrink beside the long name at 600 dp", mark.getTextSize() < full);

        RuntimeEnvironment.setQualifiers("w900dp-h960dp-large-notlong-port-xhdpi");
        controller.configurationChange(
                new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration()));
        shadowOf(Looper.getMainLooper()).idle();
        assertSame("the screen was built again", mark, controller.get().findViewById(R.id.cgWordmark));
        assertEquals("the wordmark stayed small with room to grow back", full, mark.getTextSize(), 0f);
    }

    @Test
    public void theHeaderFollowsANewFontSize() {
        // The font size changed in Android's settings while the game screen was open, to Android
        // 14's largest, 2.0x. Android 14 and newer scale big text less: the wordmark's 30 sp is
        // still 30 dp at 1.3x, so only a bigger font size shows whether the wordmark follows.
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-normal-long-port-xhdpi");
        ActivityController<AppView> controller = open("GAMING-PC");
        float before = wordmarkSize(controller);

        RuntimeEnvironment.setFontScale(2f);
        controller.configurationChange(
                new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration()));
        shadowOf(Looper.getMainLooper()).idle();
        AppView screen = controller.get();
        Resources resources = screen.getResources();
        assertEquals(2f, resources.getConfiguration().fontScale, 0f);
        // In whole pixels, as TextView reads a text size from a layout
        TextView name = screen.findViewById(R.id.appListText);
        assertEquals("the PC name kept the size it had before the font size changed",
                Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16,
                        resources.getDisplayMetrics())), name.getTextSize(), 0f);
        // The wordmark can be fitted smaller to clear the PC name, but not below its smallest
        // size, and at 2.0x that's bigger than its full size was before
        float smallest = resources.getDimensionPixelSize(R.dimen.cg_wordmark_smallest_size);
        assertTrue("at 2.0x the smallest wordmark should be bigger than it was at 1.0x", smallest > before);
        float size = wordmarkSize(controller);
        assertTrue("the wordmark kept the size it had before the font size changed: " + size + " px",
                size >= smallest && size <= resources.getDimensionPixelSize(R.dimen.cg_wordmark_size));
    }

    private static ActivityController<AppView> open(String pcName) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), AppView.class)
                .putExtra(AppView.NAME_EXTRA, pcName)
                .putExtra(AppView.UUID_EXTRA, "00000000-0000-0000-0000-000000000001");
        ActivityController<AppView> controller = Robolectric.buildActivity(AppView.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();
        return controller;
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
