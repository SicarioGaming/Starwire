package com.limelight.screenshots;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Looper;
import android.text.Layout;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.R;
import com.limelight.ui.Wordmark;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The Starwire wordmark at the top of the game screen fits beside the PC name at every size in
 * RoboDevices and a few more, with a short PC name and a long one. It runs the real AppView,
 * rainbow and all, and checks that the wordmark:
 * - is 8 dp clear of the PC name, or at the size it had before it got bigger and not touching
 *   the name, or at its smallest size;
 * - doesn't touch the PC name, apart from the screens in MEETS_THE_LONG_NAME;
 * - doesn't touch the selected game, isn't cut off, and doesn't push the game down.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// AppView's settings read MoonBridge's constants; instrumenting it skips loading the native library
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class GameScreenHeaderTest {
    // The name in the screenshots, and one as long as the names Windows gives a PC: DESKTOP- and
    // seven letters and digits
    private static final String SHORT_NAME = "GAMING-PC";
    private static final String LONG_NAME = "DESKTOP-WMQ7K2H";
    private static final String[] NAMES = {SHORT_NAME, LONG_NAME};
    private static final float GAP_DP = 8;
    // The wordmark's smallest size, the one it has always had on narrow screens
    private static final float SMALLEST_SP = 26;

    private static final Object[][] MORE_SCREENS = {
            // A 2400 x 1504 px tablet at 360 dpi, the size docs/images/cover_glow_home.png was
            // taken at, and a 960 x 540 px handheld at 240 dpi, as big as a 360 dp phone sideways
            {"TABLET_2400x1504", "w1067dp-h668dp-xlarge-notlong-land-360dpi", 1f},
            {"HANDHELD_960x540", "w640dp-h360dp-normal-long-land-hdpi", 1f},
            // 7 and 8 inch tablets held upright: 1200 x 1920 px at 320 dpi, 800 x 1280 px at 240 dpi
            {"TABLET_600x960", "w600dp-h960dp-large-notlong-port-xhdpi", 1f},
            {"TABLET_533x853", "w533dp-h853dp-large-notlong-port-hdpi", 1f},
            // A 360 dp phone with Android's Large and Largest font sizes
            {"SMALL_PHONE_FONT_115", RoboDevices.SMALL_PHONE.qualifiers, 1.15f},
            {"SMALL_PHONE_FONT_130", RoboDevices.SMALL_PHONE.qualifiers, 1.3f},
            // An old 480 x 854 px phone at 240 dpi held sideways
            {"PHONE_854_SIDEWAYS", "w569dp-h320dp-normal-long-land-hdpi", 1f},
    };

    // Where even the smallest wordmark meets the long name. It met it on these screens before it
    // got bigger too, as far in or further; only cutting the name short would make room.
    private static final List<String> MEETS_THE_LONG_NAME =
            Arrays.asList("FOLD_COVER_FONT_130", "SMALL_PHONE_FONT_130", "TABLET_533x853");

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static List<Object[]> screens() {
        List<Object[]> list = new ArrayList<>();
        for (RoboDevices device : RoboDevices.values()) {
            list.add(new Object[] {device.name(), device.qualifiers, device.fontScale});
        }
        list.addAll(Arrays.asList(MORE_SCREENS));
        return list;
    }

    private final String screenName;
    private final String qualifiers;
    private final float fontScale;

    public GameScreenHeaderTest(String name, String qualifiers, float fontScale) {
        this.screenName = name;
        this.qualifiers = qualifiers;
        this.fontScale = fontScale;
    }

    @Before
    public void configure() {
        RuntimeEnvironment.setQualifiers(qualifiers);
        RuntimeEnvironment.setFontScale(fontScale);
    }

    @Test
    public void theWordmarkFitsBesideThePcName() {
        List<String> problems = new ArrayList<>();
        for (String pc : NAMES) {
            AppView screen = open(pc);
            problems.addAll(crowding(screen, pc));
            problems.addAll(clipping(screen, pc));
        }
        assertEquals(Collections.emptyList(), problems);
    }

    // Android 13 and older scale every sp size evenly with the font size, where newer versions
    // scale big text less. This gives the PC name and the wordmark the sizes Android 13 gives
    // them at its largest standard font size, 1.3x.
    @Test
    public void theLettersFitAtAndroid13sLargestFontSize() throws Exception {
        assertEquals(Collections.emptyList(), clippingScaledEvenly(1.3f));
    }

    // Scaled evenly to 1.6x, past Android 13's largest font size, the 40 sp wordmark's letters
    // would be taller than the row. Centring can't keep them inside, so they only fit because the
    // wordmark steps down.
    @Test
    public void theLettersFitWhenTheyWouldBeTallerThanTheRow() throws Exception {
        assertEquals(Collections.emptyList(), clippingScaledEvenly(1.6f));
    }

    // What's cut off with the PC name and the wordmark this much bigger, the same for both, as
    // Android 13 scales them
    private List<String> clippingScaledEvenly(float scale) throws Exception {
        assumeTrue("this screen has a font size of its own", fontScale == 1f);
        List<String> problems = new ArrayList<>();
        for (String pc : NAMES) {
            AppView screen = open(pc);
            TextView mark = screen.findViewById(R.id.cgWordmark);
            TextView name = screen.findViewById(R.id.appListText);
            float size = screen.getResources().getDimension(R.dimen.cg_wordmark_size) * scale;
            mark.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, name.getTextSize() * scale);
            idle();
            // WordmarkRow fits the wordmark from the size it was given. If that weren't the
            // scaled size, the check below would only see the wordmark at its usual size.
            assertEquals(pc + ": the wordmark wasn't fitted from its scaled size",
                    size, wantedSize(mark), 0f);
            problems.addAll(clipping(screen, pc));
        }
        return problems;
    }

    // The size the wordmark was given, the one WordmarkRow fits it from. Wordmark keeps this to
    // its own package, so it's read the way the app can't.
    private static float wantedSize(TextView mark) throws Exception {
        Method method = Wordmark.class.getDeclaredMethod("getWantedSize");
        method.setAccessible(true);
        return (float) method.invoke(mark);
    }

    // The checks catch a PC name that runs into the wordmark, a wordmark left too close to the
    // PC name, letters cut off and a row that grew
    @Test
    public void theChecksCatchProblems() {
        AppView screen = open(SHORT_NAME);
        TextView name = screen.findViewById(R.id.appListText);
        TextView mark = screen.findViewById(R.id.cgWordmark);
        View row = (View) mark.getParent();
        // Wider than the widest screen, so it wraps and fills the row
        name.setText(String.join(" ", Collections.nCopies(10, LONG_NAME)));
        idle();
        String found = crowding(screen, SHORT_NAME).toString();
        assertTrue(found, found.contains("overlaps the PC name"));

        name.setText(SHORT_NAME);
        idle();
        // Moved to 4 dp from the PC name. Only a wordmark bigger than it used to be has to keep
        // 8 dp away; at the old size it may sit as close as it always did.
        DisplayMetrics metrics = screen.getResources().getDisplayMetrics();
        if (mark.getTextSize() > sp(oldSizeSp(screen.getResources().getConfiguration()), metrics) + 0.01f) {
            float move = gapDp(mark, name) * metrics.density - 4 * metrics.density;
            mark.setTranslationX(isRtl(mark) ? move : -move);
            found = crowding(screen, SHORT_NAME).toString();
            assertTrue(found, found.contains("dp from the PC name"));
            mark.setTranslationX(0);
        }

        // Moved down by half the row's height
        mark.setTranslationY(row.getHeight() / 2f);
        found = clipping(screen, SHORT_NAME).toString();
        assertTrue(found, found.contains("cut off at the top or bottom"));
        mark.setTranslationY(0);

        row.getLayoutParams().height = name.getHeight() + 10;
        row.requestLayout();
        idle();
        found = clipping(screen, SHORT_NAME).toString();
        assertTrue(found, found.contains("so the game below it moved"));
    }

    private static AppView open(String pcName) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), AppView.class)
                .putExtra(AppView.NAME_EXTRA, pcName)
                .putExtra(AppView.UUID_EXTRA, "00000000-0000-0000-0000-000000000001");
        AppView screen = Robolectric.buildActivity(AppView.class, intent).setup().get();
        idle();
        return screen;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    // What crowds the wordmark: the PC name or the selected game
    private List<String> crowding(AppView screen, String pc) {
        List<String> problems = new ArrayList<>();
        TextView mark = screen.findViewById(R.id.cgWordmark);
        View name = screen.findViewById(R.id.appListText);
        DisplayMetrics metrics = screen.getResources().getDisplayMetrics();
        Rect markBox = box(mark);
        Rect nameBox = box(name);
        float gap = gapDp(mark, name);
        float size = mark.getTextSize();
        boolean oldSize = same(size, sp(oldSizeSp(screen.getResources().getConfiguration()), metrics));
        boolean smallest = same(size, sp(SMALLEST_SP, metrics));
        if (gap < GAP_DP && !(oldSize && gap >= 0) && !smallest) {
            problems.add(String.format(Locale.ROOT,
                    "%s: the wordmark is %.1f dp from the PC name at %.1f dp, where it could be smaller",
                    pc, gap, size / metrics.density));
        }
        if (Rect.intersects(markBox, nameBox)
                && !(pc.equals(LONG_NAME) && MEETS_THE_LONG_NAME.contains(screenName))) {
            problems.add(pc + ": the wordmark " + markBox + " overlaps the PC name " + nameBox);
        }
        // Nothing of the selected game (its cover, title, chips or buttons) is under the letters
        Rect ink = ink(mark);
        for (View view : shown(screen.findViewById(R.id.heroSection))) {
            if (Rect.intersects(ink, box(view))) {
                problems.add(pc + ": the wordmark's letters " + ink + " overlap " + describe(view) + " " + box(view));
            }
        }
        return problems;
    }

    // What's cut off or moved: the wordmark at the row's edges, or the game under the row
    private static List<String> clipping(AppView screen, String pc) {
        List<String> problems = new ArrayList<>();
        TextView mark = screen.findViewById(R.id.cgWordmark);
        View name = screen.findViewById(R.id.appListText);
        View row = (View) mark.getParent();
        Rect markBox = box(mark);
        Rect rowBox = box(row);
        if (markBox.left < rowBox.left || markBox.right > rowBox.right) {
            problems.add(pc + ": the wordmark " + markBox + " is cut off at the side of " + rowBox);
        }
        Rect ink = ink(mark);
        if (ink.top < rowBox.top || ink.bottom > rowBox.bottom) {
            problems.add(pc + ": the wordmark's letters " + ink + " are cut off at the top or bottom of " + rowBox);
        }
        if (row.getHeight() != name.getHeight()) {
            problems.add(pc + ": the top row is " + row.getHeight() + " px tall, not the PC name's "
                    + name.getHeight() + ", so the game below it moved");
        }
        return problems;
    }

    // The wordmark's size before it got bigger, for this screen's set of sizes
    // (cover_glow_dimens.xml)
    private static float oldSizeSp(Configuration config) {
        if (config.screenWidthDp < 480) {
            return 26;
        }
        return config.screenHeightDp < 520 ? 28 : 34;
    }

    // In whole pixels, as TextView reads a text size from a layout
    private static float sp(float size, DisplayMetrics metrics) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics));
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    private static boolean isRtl(View view) {
        return view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    // How far the wordmark is from the PC name, in dp; under 0 when they overlap
    private static float gapDp(View mark, View name) {
        Rect markBox = box(mark);
        Rect nameBox = box(name);
        int gap = isRtl(mark) ? nameBox.left - markBox.right : markBox.left - nameBox.right;
        return gap / mark.getResources().getDisplayMetrics().density;
    }

    // The views showing in the group, without the groups that hold them
    private static List<View> shown(View view) {
        List<View> views = new ArrayList<>();
        if (!view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) {
            return views;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                views.addAll(shown(group.getChildAt(i)));
            }
            return views;
        }
        views.add(view);
        return views;
    }

    private static String describe(View view) {
        return view.getId() != View.NO_ID
                ? view.getResources().getResourceEntryName(view.getId())
                : view.getClass().getSimpleName();
    }

    // Where the view is in the window
    private static Rect box(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    // Where the wordmark's letters are drawn in the window, without the blank space above and
    // below them in its text box
    private static Rect ink(TextView mark) {
        String text = mark.getText().toString();
        Rect ink = new Rect();
        mark.getPaint().getTextBounds(text, 0, text.length(), ink);
        Layout layout = mark.getLayout();
        int[] at = new int[2];
        mark.getLocationInWindow(at);
        ink.offset(at[0] + mark.getTotalPaddingLeft() + Math.round(layout.getLineLeft(0)),
                at[1] + mark.getTotalPaddingTop() + layout.getLineBaseline(0));
        return ink;
    }
}
