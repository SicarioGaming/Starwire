package com.limelight.screenshots;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.res.Configuration;
import android.text.Layout;
import android.os.Looper;
import android.preference.Preference;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.takahirom.roborazzi.RoborazziKt;
import com.github.takahirom.roborazzi.RoborazziOptions;
import com.limelight.R;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.AppIcons;
import com.limelight.ui.CollapsingHeader;
import com.limelight.ui.CoverImageView;
import com.limelight.ui.overlay.OverlayMenuView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * Starwire's screens run by Robolectric (the real framework on the JVM, no device or emulator)
 * at every size in RoboDevices, and saved as pictures by Roborazzi.
 *
 * <ul>
 * <li>Check: gradlew verifyRoborazziNonRootDebug. It runs every JVM test and fails on any pixel
 * that differs from src/test/screenshots. app/build/outputs/roborazzi then has each changed
 * screen as reference, difference and new picture side by side.
 * <li>Look without failing: gradlew compareRoborazziNonRootDebug.
 * <li>After changing a screen on purpose: gradlew recordRoborazziNonRootDebug, look at every
 * picture that changed, and commit them with the change.
 * <li>A plain gradlew testNonRootDebugUnitTest runs these tests and their layout checks but saves
 * and compares no pictures.
 * </ul>
 *
 * The comparison stays exact: a settings screen with an empty list differed by less than 5% of
 * its pixels. Another screen size is another RoboDevices entry; another screen is another @Test.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// MoonBridge loads the native streaming library in its static block, and the settings read its
// audio constants. Instrumenting it lets Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class ActivityScreensTest {
    private static final String[] GAMES = {
            "Cyberpunk 2077", "Elden Ring", "Desktop", "Forza Horizon 5", "Hades II",
            "Steam Big Picture", "Baldur's Gate 3", "Hollow Knight: Silksong",
    };

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static List<Object[]> devices() {
        List<Object[]> list = new ArrayList<>();
        for (RoboDevices device : RoboDevices.values()) {
            list.add(new Object[] {device});
        }
        return list;
    }

    private final RoboDevices device;

    public ActivityScreensTest(RoboDevices device) {
        this.device = device;
    }

    @Before
    public void configure() {
        RuntimeEnvironment.setQualifiers(device.qualifiers);
        RuntimeEnvironment.setFontScale(device.fontScale);
    }

    /** Saves (record) or checks (verify) src/test/screenshots/screen[DEVICE].png */
    private void capture(View view, String screen) {
        shadowOf(Looper.getMainLooper()).idle();
        String file = System.getProperty("roborazzi.output.dir", "src/test/screenshots")
                + "/" + screen + "[" + device.name() + "].png";
        // Looked up by exact signature: RoborazziKt also has Compose overloads of captureRoboImage,
        // and neither javac nor Class.getMethod copes without Compose's test classes present
        try {
            MethodHandles.publicLookup().findStatic(RoborazziKt.class, "captureRoboImage",
                            MethodType.methodType(void.class, View.class, String.class, RoborazziOptions.class))
                    .invoke(view, file, new RoborazziOptions());
        }
        catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    /** The real settings Activity: its fragment, preferences.xml, section pills and impact panel. */
    @Test
    public void settings() {
        ActivityController<StreamSettings> controller =
                Robolectric.buildActivity(StreamSettings.class).setup();
        capture(controller.get().getWindow().getDecorView(), "settings");
    }

    /**
     * Settings scrolled to Controllers > DualSense (Experimental), as it is the first time: the
     * switch off, so the status line says so and nothing looks for a pad.
     */
    @Test
    public void dualSenseSettings() {
        ActivityController<StreamSettings> controller =
                Robolectric.buildActivity(StreamSettings.class).setup();
        shadowOf(Looper.getMainLooper()).idle();
        Activity activity = controller.get();
        ListView list = activity.findViewById(android.R.id.list);
        int group = -1;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            Object item = list.getAdapter().getItem(i);
            if (item instanceof Preference && "dualsense_heading".equals(((Preference) item).getKey())) {
                group = i;
                break;
            }
        }
        assertTrue("no DualSense group in the settings list", group >= 0);
        // Its rows, in order, right under its heading
        String[] rows = {"checkbox_dualsense_effects", "dualsense_status", "dualsense_test",
                "list_dualsense_trigger_preset", "list_dualsense_trigger_strength"};
        for (int i = 0; i < rows.length; i++) {
            assertEquals(rows[i], ((Preference) list.getAdapter().getItem(group + 1 + i)).getKey());
        }
        Preference status = (Preference) list.getAdapter().getItem(group + 2);
        assertEquals(activity.getString(R.string.dualsense_status_off), String.valueOf(status.getSummary()));
        list.setSelection(group);
        shadowOf(Looper.getMainLooper()).idle();
        capture(activity.getWindow().getDecorView(), "dualSenseSettings");
    }

    /**
     * The game screen layout with AppView's real CollapsingHeader (AppView itself needs a PC).
     * Same order as AppView: the screen is laid out first, the cover grid arrives afterwards
     * (AdapterFragment, once the app list has loaded).
     */
    @Test
    public void gameScreen() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(R.layout.activity_app_view);
        String selected = GAMES[0];
        ((TextView) activity.findViewById(R.id.appListText)).setText("GAMING-PC");
        ((TextView) activity.findViewById(R.id.heroTitle)).setText(selected);
        TextView label = activity.findViewById(R.id.heroLabel);
        label.setText(R.string.cg_last_played);
        label.setVisibility(View.VISIBLE);
        CoverImageView cover = activity.findViewById(R.id.heroCover);
        cover.setImageDrawable(AppIcons.tile(activity, selected));
        cover.setVisibility(View.VISIBLE);
        ((TextView) activity.findViewById(R.id.coverRowLabel))
                .setText(activity.getString(R.string.cg_on_pc, "GAMING-PC"));
        // The chips AppView shows for a game streamed in HEVC at 1080p and 120 FPS with HDR
        ViewGroup chips = activity.findViewById(R.id.heroChips);
        for (String chip : new String[] {activity.getString(R.string.cg_chip_hevc),
                activity.getString(R.string.cg_chip_resolution, 1080, 120), activity.getString(R.string.cg_chip_hdr)}) {
            TextView view = (TextView) LayoutInflater.from(activity).inflate(R.layout.cg_hero_chip, chips, false);
            view.setText(chip);
            chips.addView(view);
        }
        CollapsingHeader header = new CollapsingHeader(activity.findViewById(R.id.appHeader),
                activity.findViewById(R.id.heroSection), activity.findViewById(R.id.miniBar));
        shadowOf(Looper.getMainLooper()).idle();

        ViewGroup container = activity.findViewById(R.id.appFragmentContainer);
        GridView grid = (GridView) LayoutInflater.from(activity)
                .inflate(R.layout.app_grid_view, container, false);
        grid.setAdapter(new CoverAdapter(activity));
        container.addView(grid);
        // Attached after the header's first layout, as in AppView: attaching it in the same frame
        // leaves the header half scrolled away on phones and handhelds
        header.attach(grid);
        shadowOf(Looper.getMainLooper()).idle();

        // On a wide, short screen (a phone held sideways, a small handheld) the header leaves a
        // whole row of covers showing, 375 dp high and up
        Configuration config = activity.getResources().getConfiguration();
        if (config.screenWidthDp >= 480 && config.screenHeightDp < 520) {
            View decor = activity.getWindow().getDecorView();
            View firstCover = grid.getChildAt(0);
            int[] at = new int[2];
            firstCover.getLocationInWindow(at);
            int bottom = at[1] + firstCover.getHeight();
            assertTrue("the first row of covers ends at " + bottom + " px, below the screen's "
                    + decor.getHeight(), bottom <= decor.getHeight());
        }
        capture(activity.getWindow().getDecorView(), "gameScreen");
    }

    /** The PC list layout while it looks for PCs (PcView itself starts network discovery). */
    @Test
    public void pcList() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(R.layout.activity_pc_view);
        // The spinner doesn't show: Robolectric doesn't run its animated drawable
        capture(activity.getWindow().getDecorView(), "pcList");
    }

    /** The in-stream menu sheet over a stream, as it is when it has just opened. */
    @Test
    public void streamMenu() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create();
        Activity activity = controller.get();
        activity.setTheme(R.style.StreamTheme);
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackgroundColor(0xFF203040); // stands in for the video
        LayoutInflater.from(activity).inflate(R.layout.activity_game, frame, true);
        activity.setContentView(frame);
        controller.start().resume().visible();
        OverlayMenuView menu = frame.findViewById(R.id.overlayMenuView);
        menu.setGameInfo(GAMES[0], "1920 x 1080, 120 FPS", AppIcons.accentFor(GAMES[0]));
        menu.setCover(AppIcons.tile(activity, GAMES[0]));
        menu.buildMenu();
        menu.setVisibility(View.VISIBLE);
        shadowOf(Looper.getMainLooper()).idle();
        // The tiles (the sheet's scrolling part). The bar's pills aren't checked: on narrow
        // screens they still cut long labels short
        for (int i = 0; i < menu.getChildCount(); i++) {
            if (menu.getChildAt(i) instanceof ScrollView) {
                assertNoWordBroken(menu.getChildAt(i));
            }
        }
        capture(frame, "streamMenu");
        for (int i = 0; i < menu.getChildCount(); i++) {
            if (menu.getChildAt(i) instanceof ScrollView) {
                assertScrollsToEveryRow((ScrollView) menu.getChildAt(i));
            }
        }
    }

    // On a short screen the tools take more room than the sheet has (the emulator matrix found it
    // on a small phone and a Fold's cover screen). The grid then fades out at the bottom, so the
    // cut-off row reads as more to scroll to, and it scrolls far enough to show its last row.
    private static void assertScrollsToEveryRow(ScrollView grid) {
        ViewGroup rows = (ViewGroup) grid.getChildAt(0);
        View last = rows.getChildAt(rows.getChildCount() - 1);
        assertTrue("the last row of tools isn't laid out", last.getHeight() > 0);
        if (rows.getHeight() <= grid.getHeight()) {
            assertTrue("the last row of tools is cut off", last.getBottom() <= grid.getHeight());
            return;
        }
        assertTrue("nothing shows there are more tools below", grid.getVerticalFadingEdgeLength() > 0);
        grid.scrollTo(0, rows.getHeight());
        assertTrue("the last row of tools can't be scrolled into view", last.getTop() >= grid.getScrollY()
                && last.getBottom() <= grid.getScrollY() + grid.getHeight());
        grid.scrollTo(0, 0);
    }

    // Every label breaks only between words (or after "-" or "/") and shows whole
    private static void assertNoWordBroken(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assertNoWordBroken(group.getChildAt(i));
            }
            return;
        }
        if (!(view instanceof TextView) || view.getVisibility() != View.VISIBLE || view.getWidth() == 0) {
            return;
        }
        TextView text = (TextView) view;
        Layout layout = text.getLayout();
        CharSequence label = text.getText();
        if (layout == null || label.length() == 0) {
            return;
        }
        assertTrue("\"" + label + "\" needs " + layout.getLineCount() + " lines, more than it shows",
                layout.getLineCount() <= text.getMaxLines());
        for (int line = 0; line < layout.getLineCount() - 1; line++) {
            char before = label.charAt(layout.getLineEnd(line) - 1);
            assertTrue("\"" + label + "\" breaks inside a word",
                    Character.isWhitespace(before) || before == '-' || before == '/');
        }
    }

    private static final class CoverAdapter extends BaseAdapter {
        private final Activity activity;

        CoverAdapter(Activity activity) {
            this.activity = activity;
        }

        @Override
        public int getCount() {
            return GAMES.length;
        }

        @Override
        public Object getItem(int position) {
            return GAMES[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View item = LayoutInflater.from(activity).inflate(R.layout.app_grid_item, parent, false);
            ImageView image = item.findViewById(R.id.grid_image);
            image.setImageDrawable(AppIcons.tile(activity, GAMES[position]));
            ((TextView) item.findViewById(R.id.grid_text)).setText(GAMES[position]);
            return item;
        }
    }
}
