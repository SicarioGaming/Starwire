package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.testing.IgnorePcUpdates;
import com.limelight.testing.TestFiles;
import com.limelight.utils.CacheHelper;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;

/**
 * The PC list and the game screen fit whatever screen they're on. Run with the display set to a
 * phone or foldable size (adb shell wm size) to check those; the pictures are saved next to the
 * app's files for a look.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenWidthTest {
    private static final String UUID = "22222222-3333-4444-5555-666666666666";

    private static final String APP_LIST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<root status_code=\"200\">"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Steam Big Picture</AppTitle><ID>1093255277</ID></App>"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Cyberpunk 2077</AppTitle><ID>1428765823</ID></App>"
            + "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Aniimo</AppTitle><ID>203145821</ID></App>"
            + "</root>";

    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    // The test PC never answers, so the game screen would close itself with "lost connection"
    // after three failed polls, or at once if the network dropped, part way through a test. On
    // fold-z-36 (2026-10-27) both happened: one test found its screen gone, and in the next the
    // screen closed while the test drew on the main thread and Android called it not responding.
    @Rule
    public final IgnorePcUpdates ignorePcUpdates = new IgnorePcUpdates();

    @After
    public void removeTestPc() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(target);
        ComputerDetails details = db.getComputerByUUID(UUID);
        if (details != null) {
            db.deleteComputer(details);
        }
        db.close();
        CacheHelper.deleteCacheFile(target.getCacheDir(), "applist", UUID);
    }

    private ComputerDetails addTestPc() throws Exception {
        return addTestPc(APP_LIST);
    }

    // Enough games for about three screens, so the header can scroll away on any screen (30 weren't
    // enough on a 1280x800 dp tablet). Sized for the smallest tiles (100x133 dp), with a column
    // and a row to spare.
    private String longAppList() {
        android.content.res.Configuration config = target.getResources().getConfiguration();
        int columns = config.screenWidthDp / 100 + 1;
        int rows = config.screenHeightDp * 3 / 133 + 1;
        int games = Math.max(30, columns * rows);
        StringBuilder list = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\">");
        for (int i = 1; i <= games; i++) {
            list.append("<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Game ").append(i)
                    .append("</AppTitle><ID>").append(500000 + i).append("</ID></App>");
        }
        return list.append("</root>").toString();
    }

    private ComputerDetails addTestPc(String appList) throws Exception {
        ComputerDetails details = new ComputerDetails();
        details.uuid = UUID;
        details.name = "TEST-PC";
        // Unroutable, so the PC never answers; the game screen ignores that (IgnorePcUpdates)
        details.manualAddress = new ComputerDetails.AddressTuple("10.255.255.1", 47989);
        ComputerDatabaseManager db = new ComputerDatabaseManager(target);
        db.updateComputer(details);
        db.close();
        try (OutputStream out = CacheHelper.openCacheFileForOutput(target.getCacheDir(), "applist", UUID)) {
            CacheHelper.writeStringToOutputStream(out, appList);
        }
        return details;
    }

    @Test
    public void pcListFits() throws Exception {
        addTestPc();
        try (ActivityScenario<PcView> scenario = ActivityScenario.launch(new Intent(target, PcView.class))) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                String name = layOutAndSave(activity, "pc_list");
                assertNothingOffScreen(activity, name);
            });
        }
    }

    @Test
    public void gameScreenFits() throws Exception {
        ComputerDetails details = addTestPc();
        Intent intent = new Intent(target, AppView.class)
                .putExtra(AppView.NAME_EXTRA, details.name)
                .putExtra(AppView.UUID_EXTRA, UUID);
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(intent)) {
            for (int i = 0; i < 3; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(activity -> layOutAndSave(activity, "game_screen"));
            }
            scenario.onActivity(activity -> {
                String name = layOutAndSave(activity, "game_screen");
                assertFalse("game screen closed", activity.isFinishing());
                assertNothingOffScreen(activity, name);
            });
        }
    }

    // From Reddit: on a phone held sideways the header took most of the screen and didn't scroll,
    // leaving half a row of games. The games start just under the header, the header scrolls away
    // with them, and the slim bar with Play takes over once the hero's gone.
    @Test
    public void gameScreenHeaderScrollsAway() throws Exception {
        ComputerDetails details = addTestPc(longAppList());
        Intent intent = new Intent(target, AppView.class)
                .putExtra(AppView.NAME_EXTRA, details.name)
                .putExtra(AppView.UUID_EXTRA, UUID);
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(intent)) {
            for (int i = 0; i < 3; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(activity -> layOutAndSave(activity, "game_screen_top"));
            }
            scenario.onActivity(activity -> {
                View header = activity.findViewById(R.id.appHeader);
                View miniBar = activity.findViewById(R.id.miniBar);
                android.widget.GridView grid = activity.findViewById(R.id.fragmentView);
                assertTrue("no games in the grid", grid.getChildCount() > 0);

                // At the top: the whole header shows, the first row starts below it, no bar
                String state = "h=" + header.getHeight() + " pad=" + grid.getPaddingTop() + " top=" + grid.getChildAt(0).getTop() + " first=" + grid.getFirstVisiblePosition() + " cols=" + grid.getNumColumns() + " childH=" + grid.getChildAt(0).getHeight() + " count=" + grid.getChildCount() + " ty=" + header.getTranslationY();
                assertEquals(state, 0f, header.getTranslationY(), 0.5f);
                assertEquals(header.getHeight(), grid.getPaddingTop());
                assertTrue("first row starts under the header",
                        grid.getChildAt(0).getTop() >= header.getHeight());
                assertEquals(View.INVISIBLE, miniBar.getVisibility());

                // A finger dragging up on the header (not just on the covers) scrolls too
                int[] at = new int[2];
                header.getLocationInWindow(at);
                dragUp(activity.getWindow().getDecorView(), at[0] + header.getWidth() * 0.85f,
                        at[1] + header.getHeight() * 0.5f, header.getHeight() * 0.3f);
                assertTrue("dragging on the header didn't scroll", header.getTranslationY() < -10);
                scrollInSteps(grid, -grid.getHeight());
                assertEquals(0f, header.getTranslationY(), 0.5f);

                // Scrolled past the hero: the header has gone up with the games and the bar shows
                scrollInSteps(grid, header.getHeight() + 40);
                layOutAndSave(activity, "game_screen_scrolled");
                assertTrue("header didn't move", header.getTranslationY() <= -header.getHeight() + 1);
                assertEquals(View.VISIBLE, miniBar.getVisibility());
                assertEquals(1f, miniBar.getAlpha(), 0.01f);
                // The space the header left is filled with more games
                assertTrue("only " + grid.getChildCount() + " of " + grid.getCount() + " games shown in "
                                + grid.getNumColumns() + " columns, grid " + grid.getHeight() + " px tall",
                        grid.getChildCount() > grid.getNumColumns());
                assertEquals(((android.widget.TextView) activity.findViewById(R.id.heroTitle)).getText(),
                        ((android.widget.TextView) activity.findViewById(R.id.miniTitle)).getText());

                // Back at the top it's as it was
                scrollInSteps(grid, -(header.getHeight() + 40));
                layOutAndSave(activity, "game_screen_top");
                assertEquals(0f, header.getTranslationY(), 0.5f);
                assertEquals(View.INVISIBLE, miniBar.getVisibility());
            });
        }
    }

    // A slow drag, so it scrolls without flinging
    private static void dragUp(View root, float x, float y, float distance) {
        long down = SystemClock.uptimeMillis();
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down,
                android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        root.dispatchTouchEvent(event);
        event.recycle();
        int steps = 20;
        for (int i = 1; i <= steps; i++) {
            event = android.view.MotionEvent.obtain(down, down + i * 40L,
                    android.view.MotionEvent.ACTION_MOVE, x, y - distance * i / steps, 0);
            root.dispatchTouchEvent(event);
            event.recycle();
        }
        event = android.view.MotionEvent.obtain(down, down + (steps + 10) * 40L,
                android.view.MotionEvent.ACTION_UP, x, y - distance, 0);
        root.dispatchTouchEvent(event);
        event.recycle();
    }

    // A list only moves so far in one go, so scroll the way a finger does, a little at a time
    private static void scrollInSteps(android.widget.AbsListView list, int pixels) {
        int step = pixels > 0 ? 60 : -60;
        for (int done = 0; Math.abs(done) < Math.abs(pixels); done += step) {
            list.scrollListBy(Math.abs(pixels - done) < Math.abs(step) ? pixels - done : step);
        }
    }

    // Lays the screen out by hand at the display's size (works with the screen off) and saves it
    private static String layOutAndSave(Activity activity, String what) {
        long start = SystemClock.uptimeMillis();
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = metrics.widthPixels, height = metrics.heightPixels;
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        String name = what + "_" + activity.getResources().getConfiguration().screenWidthDp + "dp";
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height,
                android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                TestFiles.file(activity, name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        // This runs on the main thread, which takes nothing else meanwhile. On fold-z-36
        // (2026-10-27) a game screen closed while the test was drawing and didn't take its focus
        // change for 5 s. The log says how long a picture holds the main thread on each device.
        android.util.Log.i("ScreenWidthTest", name + ": laid out, drawn and saved in "
                + (SystemClock.uptimeMillis() - start) + " ms");
        return name;
    }

    // No visible text or button may start past the right edge of the screen
    private static void assertNothingOffScreen(Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        checkTree(activity, root, root.getWidth(), name);
    }

    private static void checkTree(Activity activity, View view, int screenWidth, String name) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean meaningful = view.isClickable() || view instanceof android.widget.TextView;
        if (meaningful && view.getWidth() > 0 && !isInScroller(view)) {
            int[] at = new int[2];
            view.getLocationInWindow(at);
            assertTrue(name + ": " + describe(activity, view) + " starts off screen at x=" + at[0],
                    at[0] < screenWidth);
            assertTrue(name + ": " + describe(activity, view) + " is squeezed to " + view.getWidth() + " px",
                    !(view instanceof android.widget.TextView)
                            || ((android.widget.TextView) view).getText().length() == 0
                            || view.getWidth() > 8);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                checkTree(activity, group.getChildAt(i), screenWidth, name);
            }
        }
    }

    // Rows that scroll sideways (game row, settings pills) are allowed past the edge
    private static boolean isInScroller(View view) {
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (p instanceof android.widget.HorizontalScrollView
                    || p instanceof android.widget.AbsListView
                    || p.getClass().getName().contains("RecyclerView")) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Activity activity, View view) {
        String id = view.getId() != View.NO_ID ? activity.getResources().getResourceEntryName(view.getId()) : "";
        String text = view instanceof android.widget.TextView
                ? " \"" + ((android.widget.TextView) view).getText() + "\"" : "";
        return view.getClass().getSimpleName() + (id.isEmpty() ? "" : " #" + id) + text;
    }
}
