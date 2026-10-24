package com.limelight.screenshots;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Starwire wordmark at the top of the game screen fits beside the PC name at every size in
 * RoboDevices and two more: it doesn't touch the PC name or the selected game, its letters aren't
 * cut off, and it doesn't push the game down. It runs the real AppView, rainbow and all.
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

    // A 2400 x 1504 px tablet at 360 dpi, the size docs/images/cover_glow_home.png was taken at,
    // and a 960 x 540 px handheld at 240 dpi, as big as a 360 dp phone held sideways
    private static final Object[][] MORE_SCREENS = {
            {"TABLET_2400x1504", "w1067dp-h668dp-xlarge-notlong-land-360dpi", 1f},
            {"HANDHELD_960x540", "w640dp-h360dp-normal-long-land-hdpi", 1f},
    };

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static List<Object[]> screens() {
        List<Object[]> list = new ArrayList<>();
        for (RoboDevices device : RoboDevices.values()) {
            list.add(new Object[] {device.name(), device.qualifiers, device.fontScale});
        }
        list.addAll(Arrays.asList(MORE_SCREENS));
        return list;
    }

    private final String qualifiers;
    private final float fontScale;

    public GameScreenHeaderTest(String name, String qualifiers, float fontScale) {
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
        assertEquals(SHORT_NAME, Collections.emptyList(), problems(open(SHORT_NAME)));
        // With Android's largest common font size, a name this long already ran into the
        // wordmark on a narrow screen when it was smaller. Only cutting the name short would
        // make room there.
        if (fontScale == 1f) {
            assertEquals(LONG_NAME, Collections.emptyList(), problems(open(LONG_NAME)));
        }
    }

    // The check catches a PC name that runs into the wordmark, and a wordmark too big for its row
    @Test
    public void theCheckCatchesAnOverlap() {
        AppView screen = open(SHORT_NAME);
        TextView name = screen.findViewById(R.id.appListText);
        // Wider than the widest screen, so it wraps and fills the row
        name.setText(String.join(" ", Collections.nCopies(10, LONG_NAME)));
        shadowOf(Looper.getMainLooper()).idle();
        String found = problems(screen).toString();
        assertTrue(found, found.contains("overlaps the PC name"));

        name.setText(SHORT_NAME);
        ((TextView) screen.findViewById(R.id.cgWordmark)).setTextSize(TypedValue.COMPLEX_UNIT_SP, 80);
        shadowOf(Looper.getMainLooper()).idle();
        found = problems(screen).toString();
        assertTrue(found, found.contains("cut off at the top or bottom") || found.contains("the game below it moved"));
    }

    private static AppView open(String pcName) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), AppView.class)
                .putExtra(AppView.NAME_EXTRA, pcName)
                .putExtra(AppView.UUID_EXTRA, "00000000-0000-0000-0000-000000000001");
        AppView screen = Robolectric.buildActivity(AppView.class, intent).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        return screen;
    }

    // What's wrong with the wordmark's place, or nothing
    private static List<String> problems(AppView screen) {
        List<String> problems = new ArrayList<>();
        TextView mark = screen.findViewById(R.id.cgWordmark);
        View name = screen.findViewById(R.id.appListText);
        View row = (View) mark.getParent();
        Rect markBox = box(mark);
        Rect nameBox = box(name);
        Rect rowBox = box(row);
        if (Rect.intersects(markBox, nameBox)) {
            problems.add("the wordmark " + markBox + " overlaps the PC name " + nameBox);
        }
        if (markBox.left < rowBox.left || markBox.right > rowBox.right) {
            problems.add("the wordmark " + markBox + " is cut off at the side of " + rowBox);
        }
        Rect ink = ink(mark);
        if (ink.top < rowBox.top || ink.bottom > rowBox.bottom) {
            problems.add("the wordmark's letters " + ink + " are cut off at the top or bottom of " + rowBox);
        }
        if (row.getHeight() != name.getHeight()) {
            problems.add("the top row is " + row.getHeight() + " px tall, not the PC name's "
                    + name.getHeight() + ", so the game below it moved");
        }
        // Nothing of the selected game (its cover, title, chips or buttons) is under the wordmark
        for (View view : shown(screen.findViewById(R.id.heroSection))) {
            if (Rect.intersects(markBox, box(view))) {
                problems.add("the wordmark " + markBox + " overlaps " + describe(view) + " " + box(view));
            }
        }
        return problems;
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
