package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.testing.IgnorePcUpdates;
import com.limelight.testing.TestFiles;
import com.limelight.utils.CacheHelper;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.OutputStream;

// Opens the real app list for a PC whose list is already cached, the way it opens after pairing
@RunWith(AndroidJUnit4.class)
public class AppViewTest {
    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    private static final String APP_LIST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<root status_code=\"200\">"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Steam Big Picture</AppTitle><ID>1093255277</ID></App>"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Cyberpunk 2077</AppTitle><ID>1428765823</ID></App>"
            + "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Aniimo</AppTitle><ID>203145821</ID></App>"
            + "</root>";

    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    // The test PC's address never answers, so after three failed polls the screen closes itself
    // with "lost connection": 12.5 s after launch on the RedMagic tablet. A test that slept and
    // drew full-size screenshots for a few seconds could run into that, and failed 1 run in 3.
    // The screen ignores the PC's updates from the moment it's created, so the tests don't race
    // the network.
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

    // A PC whose app list is already cached, and the intent that opens its game screen
    private Intent cachedPc() throws Exception {
        ComputerDetails details = new ComputerDetails();
        details.uuid = UUID;
        details.name = "TEST-PC";
        // Unroutable, so the PC never answers; the game screen ignores that (IgnorePcUpdates)
        details.manualAddress = new ComputerDetails.AddressTuple("10.255.255.1", 47989);
        ComputerDatabaseManager db = new ComputerDatabaseManager(target);
        db.updateComputer(details);
        db.close();

        try (OutputStream out = CacheHelper.openCacheFileForOutput(target.getCacheDir(), "applist", UUID)) {
            CacheHelper.writeStringToOutputStream(out, APP_LIST);
        }

        return new Intent(target, AppView.class)
                .putExtra(AppView.NAME_EXTRA, details.name)
                .putExtra(AppView.UUID_EXTRA, UUID);
    }

    @Test
    public void tileMenuOpensOnIconTiles() throws Exception {
        // Tapping the selected tile while a game runs opens this menu (from Reddit: it crashed on
        // Desktop). Icon tiles are drawn, not bitmaps, and the "create shortcut" check cast them.
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            for (int i = 0; i < 3; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(AppViewTest::drawOffscreen);
            }
            scenario.onActivity(activity -> {
                android.widget.AbsListView grid = findGrid(activity.getWindow().getDecorView());
                assertNotNull("no game grid", grid);
                int opened = 0;
                for (int i = 0; i < grid.getChildCount(); i++) {
                    android.view.View tile = grid.getChildAt(i);
                    Object item = grid.getItemAtPosition(grid.getFirstVisiblePosition() + i);
                    String name = ((AppView.AppObject) item).app.getAppName();
                    if (!name.equals("Desktop") && !name.equals("Steam Big Picture")) {
                        continue;
                    }
                    activity.openContextMenu(tile);
                    activity.closeContextMenu();
                    opened++;
                    // And the shortcut gets the tile's picture
                    android.widget.ImageView image = tile.findViewById(R.id.grid_image);
                    android.graphics.Bitmap picture = AppView.shortcutBitmap(image);
                    assertNotNull(name + " tile has no shortcut picture", picture);
                    // (GPU bitmaps start with Android 8; Config.HARDWARE doesn't exist before)
                    assertFalse(name + " shortcut picture is a GPU bitmap",
                            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                                    picture.getConfig() == android.graphics.Bitmap.Config.HARDWARE);
                }
                assertEquals("Desktop and Steam Big Picture tiles", 2, opened);
                assertFalse("app list closed", activity.isFinishing());
            });
        }
    }

    @Test
    public void quitSitsBesideResumeWhileAGameRuns() throws Exception {
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            waitForHero(scenario);
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field selected = AppView.class.getDeclaredField("selectedApp");
                    selected.setAccessible(true);
                    AppView.AppObject app = (AppView.AppObject) selected.get(activity);
                    assertNotNull("hero picked no game", app);
                    java.lang.reflect.Field running = AppView.class.getDeclaredField("lastRunningAppId");
                    running.setAccessible(true);
                    java.lang.reflect.Method refresh = AppView.class.getDeclaredMethod("refreshHero", boolean.class);
                    refresh.setAccessible(true);
                    android.widget.Button quit = activity.findViewById(R.id.heroQuit);
                    android.widget.Button play = activity.findViewById(R.id.heroPlay);

                    // The PC reports the selected game running, as the poller would
                    running.setInt(activity, app.app.getAppId());
                    refresh.invoke(activity, false);
                    assertEquals(android.view.View.VISIBLE, quit.getVisibility());
                    assertEquals(activity.getString(R.string.cg_resume), play.getText().toString());
                    assertEquals(activity.getString(R.string.cg_quit), quit.getText().toString());
                    quit.animate().cancel();
                    quit.setAlpha(1f);
                    quit.setTranslationX(0);
                    drawOffscreen(activity, "app_view_running.png");

                    // And it goes once nothing runs
                    running.setInt(activity, 0);
                    refresh.invoke(activity, false);
                    assertEquals(android.view.View.GONE, quit.getVisibility());
                    assertEquals(activity.getString(R.string.cg_play), play.getText().toString());
                }
                catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    @Test
    public void tilesAndButtonsSayWhichGame() throws Exception {
        // Once a cover loads the tile shows no name, so TalkBack needs it spelled out
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            for (int i = 0; i < 3; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(AppViewTest::drawOffscreen);
            }
            scenario.onActivity(activity -> {
                android.widget.AbsListView grid = findGrid(activity.getWindow().getDecorView());
                assertNotNull("no game grid", grid);
                assertEquals("all four tiles laid out", 4, grid.getChildCount());
                String selectedSuffix = ", " + activity.getString(R.string.cg_a11y_selected);
                int selected = 0;
                for (int i = 0; i < grid.getChildCount(); i++) {
                    android.view.View tile = grid.getChildAt(i);
                    Object item = grid.getItemAtPosition(grid.getFirstVisiblePosition() + i);
                    String name = ((AppView.AppObject) item).app.getAppName();
                    CharSequence label = tile.getContentDescription();
                    assertNotNull(name + " tile has no label", label);
                    assertTrue(label.toString(), label.toString().startsWith(name));
                    if (label.toString().endsWith(selectedSuffix)) {
                        selected++;
                    }
                    // The picture and the name under it are left out, so the tile is read once
                    assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            tile.findViewById(R.id.grid_image).getImportantForAccessibility());
                    assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            tile.findViewById(R.id.grid_text).getImportantForAccessibility());
                }
                assertEquals("tiles read as selected", 1, selected);

                try {
                    java.lang.reflect.Field field = AppView.class.getDeclaredField("selectedApp");
                    field.setAccessible(true);
                    String name = ((AppView.AppObject) field.get(activity)).app.getAppName();
                    // Nothing runs on the unreachable test PC, so it's Play
                    assertEquals(activity.getString(R.string.cg_a11y_play_game, name),
                            activity.findViewById(R.id.heroPlay).getContentDescription().toString());
                    assertEquals(activity.getString(R.string.cg_a11y_play_game, name),
                            activity.findViewById(R.id.miniPlay).getContentDescription().toString());
                    assertEquals(activity.getString(R.string.cg_a11y_quit_game, name),
                            activity.findViewById(R.id.heroQuit).getContentDescription().toString());
                }
                catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    // Lays out and draws the screen (the tablet's screen may be off) until the hero has picked a
    // game from the cached list, instead of sleeping a set time
    private static void waitForHero(ActivityScenario<AppView> scenario) {
        boolean[] picked = new boolean[1];
        for (int i = 0; i < 100 && !picked[0]; i++) {
            if (i > 0) {
                SystemClock.sleep(100);
            }
            scenario.onActivity(activity -> {
                drawOffscreen(activity);
                picked[0] = field(activity, "selectedApp") != null;
            });
        }
        if (!picked[0]) {
            fail("the hero picked no game in 10 s");
        }
    }

    private static android.widget.AbsListView findGrid(android.view.View view) {
        if (view instanceof android.widget.AbsListView && ((android.widget.AbsListView) view).getAdapter() != null) {
            return (android.widget.AbsListView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.AbsListView found = findGrid(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void appListOpensWithoutClosing() throws Exception {
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            // The crash came a moment after the list showed, before any cover art. Lay out and draw
            // the screen by hand, so the covers load even when the tablet's screen is off.
            for (int i = 0; i < 5; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(AppViewTest::drawOffscreen);
            }

            // A host sends real box art, which arrives as a GPU (HARDWARE) bitmap from Android 8
            scenario.onActivity(activity -> {
                android.graphics.Bitmap soft = android.graphics.Bitmap.createBitmap(
                        120, 160, android.graphics.Bitmap.Config.ARGB_8888);
                soft.eraseColor(android.graphics.Color.rgb(40, 160, 90));
                ((android.widget.ImageView) activity.findViewById(R.id.heroCover)).setImageBitmap(
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O ?
                                soft.copy(android.graphics.Bitmap.Config.HARDWARE, false) : soft);
            });
            SystemClock.sleep(1000);
            scenario.onActivity(AppViewTest::drawOffscreen);
            scenario.onActivity(activity -> {
                assertFalse("app list closed", activity.isFinishing());
                try {
                    java.lang.reflect.Field adapter = AppView.class.getDeclaredField("appGridAdapter");
                    adapter.setAccessible(true);
                    assertEquals(4, ((android.widget.BaseAdapter) adapter.get(activity)).getCount());
                    java.lang.reflect.Field selected = AppView.class.getDeclaredField("selectedApp");
                    selected.setAccessible(true);
                    assertNotNull("hero picked no game", selected.get(activity));
                }
                catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    // When its PC drops off the network the game screen says "lost connection" and closes. The
    // other tests ignore the PC's updates, as the polls and the emulators' network pick when that
    // happens. This one closes the screen on purpose, the way the polls do: the PC is offline and
    // the app list poller passes that on. The main thread is asked to answer every quarter second
    // until the screen is gone. On fold-z-36 (2026-10-27) a closing game screen didn't take the
    // window's focus change for 5 s, Android called the app not responding, and nothing said
    // where the main thread was. If it doesn't answer in 2.5 s, this logs its stack.
    @Test
    public void aLostPcClosesTheScreen() throws Exception {
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            AppView[] screen = new AppView[1];
            scenario.onActivity(activity -> screen[0] = activity);

            // The screen's service connection gives it the PC and its app list poller
            boolean[] connected = new boolean[1];
            long deadline = SystemClock.uptimeMillis() + 30_000;
            while (true) {
                onMain(() -> connected[0] = field(screen[0], "computer") != null
                        && field(screen[0], "poller") != null);
                if (connected[0]) {
                    break;
                }
                assertTrue("the game screen didn't get its PC in 30 s", SystemClock.uptimeMillis() < deadline);
                SystemClock.sleep(250);
            }

            // A network change can set the PC back to checking in between, so this goes on until
            // the screen starts to close
            boolean[] closing = new boolean[1];
            deadline = SystemClock.uptimeMillis() + 30_000;
            while (true) {
                onMain(() -> {
                    closing[0] = screen[0].isFinishing();
                    if (!closing[0]) {
                        IgnorePcUpdates.setIgnoring(screen[0], false);
                        ((ComputerDetails) field(screen[0], "computer")).state = ComputerDetails.State.OFFLINE;
                        ((ComputerManagerService.ApplistPoller) field(screen[0], "poller")).pollNow();
                    }
                });
                if (closing[0]) {
                    break;
                }
                assertTrue("the game screen stayed open 30 s with its PC offline", SystemClock.uptimeMillis() < deadline);
                SystemClock.sleep(250);
            }

            // Paused, stopped and destroyed, with the main thread answering all the while
            boolean[] destroyed = new boolean[1];
            deadline = SystemClock.uptimeMillis() + 30_000;
            while (true) {
                onMain(() -> destroyed[0] = screen[0].isDestroyed());
                if (destroyed[0]) {
                    break;
                }
                assertTrue("the game screen wasn't gone 30 s after it started to close",
                        SystemClock.uptimeMillis() < deadline);
                SystemClock.sleep(250);
            }
        }
    }

    // Runs this on the main thread and waits for it, without waiting for the main thread to go
    // idle first as ActivityScenario does. If the main thread hasn't run it in 2.5 s this logs
    // where the main thread is: Android calls an app that hasn't taken its input in 5 s not
    // responding, and can end the run before a test fails. Anything it throws is thrown here, as
    // thrown on the main thread it would end the app.
    private static void onMain(Runnable task) throws InterruptedException {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Throwable[] thrown = new Throwable[1];
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                task.run();
            }
            catch (Throwable t) {
                thrown[0] = t;
            }
            finally {
                done.countDown();
            }
        });
        if (!done.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            String stack = mainThreadStack();
            android.util.Log.w("AppViewTest", "the main thread hasn't answered for 2.5 s. It's at:" + stack);
            if (!done.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                fail("the main thread hasn't answered for a minute. It was at:" + stack);
            }
        }
        if (thrown[0] != null) {
            throw new AssertionError(thrown[0]);
        }
    }

    // The main thread's stack, its first 100 frames
    private static String mainThreadStack() {
        Thread main = android.os.Looper.getMainLooper().getThread();
        StringBuilder stack = new StringBuilder(" (").append(main.getState()).append(")");
        StackTraceElement[] frames = main.getStackTrace();
        for (int i = 0; i < Math.min(100, frames.length); i++) {
            stack.append("\n    at ").append(frames[i]);
        }
        if (frames.length > 100) {
            stack.append("\n    (").append(frames.length - 100).append(" more)");
        }
        return stack.toString();
    }

    @Test
    public void offscreenDrawingShowsGpuBitmaps() {
        // The tests above draw the screen by hand, and box art can be a GPU bitmap, which a
        // canvas made on a bitmap refuses: appListOpensWithoutClosing failed that way on every
        // emulator
        org.junit.Assume.assumeTrue("GPU bitmaps start with Android 8",
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O);
        int green = android.graphics.Color.rgb(40, 160, 90);
        android.graphics.Bitmap[] drawn = new android.graphics.Bitmap[1];
        RuntimeException[] failed = new RuntimeException[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            android.graphics.Bitmap soft = android.graphics.Bitmap.createBitmap(
                    120, 160, android.graphics.Bitmap.Config.ARGB_8888);
            soft.eraseColor(green);
            android.widget.ImageView image = new android.widget.ImageView(target);
            image.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
            image.setImageBitmap(soft.copy(android.graphics.Bitmap.Config.HARDWARE, false));
            image.measure(android.view.View.MeasureSpec.makeMeasureSpec(240, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY));
            image.layout(0, 0, 240, 320);
            try {
                drawn[0] = drawToBitmap(image, 240, 320);
            }
            catch (RuntimeException e) {
                // Thrown here it would stop the whole run, not just this test
                failed[0] = e;
            }
        });
        if (failed[0] != null) {
            throw new AssertionError("drawing a GPU bitmap offscreen failed", failed[0]);
        }
        assertEquals(android.graphics.Bitmap.Config.ARGB_8888, drawn[0].getConfig());
        int middle = drawn[0].getPixel(120, 160);
        assertEquals("red", android.graphics.Color.red(green), android.graphics.Color.red(middle), 2);
        assertEquals("green", android.graphics.Color.green(green), android.graphics.Color.green(middle), 2);
        assertEquals("blue", android.graphics.Color.blue(green), android.graphics.Color.blue(middle), 2);
    }

    @Test
    public void newSizeClassRebuildsTheScreenInPlace() throws Exception {
        // Folding, unfolding, split screen or turning a phone can move the game screen into
        // another set of sizes (values-w480dp, values-w480dp-h520dp). It used to recreate itself
        // then, and on the RedMagic tablet the phone maker's game mode crashed the app in 3 of 4
        // runs when it did. The same screen now builds its layout again and keeps its game.
        try (ActivityScenario<AppView> scenario = ActivityScenario.launch(cachedPc())) {
            for (int i = 0; i < 3; i++) {
                SystemClock.sleep(1000);
                scenario.onActivity(AppViewTest::drawOffscreen);
            }
            AppView[] screen = new AppView[1];
            Object[] game = new Object[1];
            Object[] glow = new Object[1];
            android.view.View[] cover = new android.view.View[1];
            int[] coverWidth = new int[1];
            scenario.onActivity(activity -> {
                screen[0] = activity;
                game[0] = field(activity, "selectedApp");
                assertNotNull("hero picked no game", game[0]);
                glow[0] = field(activity, "glowAnimator");
                cover[0] = activity.findViewById(R.id.heroCover);
                coverWidth[0] = cover[0].getLayoutParams().width;
            });

            // A size from another set than the screen's own: a phone held upright (narrower than
            // 480 dp) when the screen starts wider, else an unfolded foldable's inner screen
            int[] otherSize = screen[0].getResources().getConfiguration().screenWidthDp >= 480 ?
                    new int[] {400, 860} : new int[] {840, 700};
            android.content.res.Configuration original = SizeChange.apply(screen[0], otherSize[0], otherSize[1]);
            try {
                SystemClock.sleep(1500);
                scenario.onActivity(AppViewTest::drawOffscreen);
                scenario.onActivity(activity -> {
                    assertSame("the screen was recreated instead of rebuilt", screen[0], activity);
                    assertFalse("app list closed", activity.isFinishing());
                    android.view.View newCover = activity.findViewById(R.id.heroCover);
                    assertNotSame("the layout wasn't built again", cover[0], newCover);
                    int newWidth = newCover.getLayoutParams().width;
                    assertEquals(activity.getResources().getDimensionPixelSize(R.dimen.cg_hero_cover_width), newWidth);
                    assertNotEquals("the new size's cover size didn't apply", coverWidth[0], newWidth);
                    checkStillShowing(activity, game[0]);
                    // The old views' endless animations stop with them
                    assertFalse("the old glow still runs", ((android.animation.Animator) glow[0]).isStarted());
                });
            }
            finally {
                SizeChange.restore(screen[0], original);
            }

            // And back to the screen's own sizes
            SystemClock.sleep(1500);
            scenario.onActivity(AppViewTest::drawOffscreen);
            scenario.onActivity(activity -> {
                assertSame("the screen was recreated instead of rebuilt", screen[0], activity);
                assertEquals(coverWidth[0], activity.findViewById(R.id.heroCover).getLayoutParams().width);
                checkStillShowing(activity, game[0]);
            });
        }
    }

    // The rebuilt screen has its four tiles and the same game in the hero
    private static void checkStillShowing(AppView activity, Object game) {
        assertSame("the selected game changed", game, field(activity, "selectedApp"));
        android.widget.AbsListView grid = findGrid(activity.getWindow().getDecorView());
        assertNotNull("no game grid", grid);
        assertEquals("all four tiles", 4, grid.getAdapter().getCount());
        assertEquals(((AppView.AppObject) game).app.getAppName(),
                ((android.widget.TextView) activity.findViewById(R.id.heroTitle)).getText().toString());
    }

    private static Object field(AppView activity, String name) {
        try {
            java.lang.reflect.Field field = AppView.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void drawOffscreen(AppView activity) {
        drawOffscreen(activity, "app_view.png");
    }

    private static void drawOffscreen(AppView activity, String fileName) {
        android.view.View root = activity.getWindow().getDecorView();
        int width = 2400, height = 1504;
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        android.graphics.Bitmap bitmap = drawToBitmap(root, width, height);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                TestFiles.file(activity, fileName))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    // A canvas made on a bitmap draws in software, and Android never lets software drawing touch
    // a GPU (HARDWARE) bitmap, such as the box art appListOpensWithoutClosing hands the hero.
    // From Android 9 the screen is recorded instead and the recording drawn by the GPU, as the
    // real screen is. Android 8 has no public way, so it gets the canvas's hidden switch (not
    // tried on an Android 8 device).
    private static android.graphics.Bitmap drawToBitmap(android.view.View root, int width, int height) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.graphics.Picture picture = new android.graphics.Picture();
            root.draw(picture.beginRecording(width, height));
            picture.endRecording();
            return android.graphics.Bitmap.createBitmap(picture, width, height, android.graphics.Bitmap.Config.ARGB_8888);
        }
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                android.graphics.Canvas.class.getMethod("setHwBitmapsInSwModeEnabled", boolean.class).invoke(canvas, true);
            }
            catch (ReflectiveOperationException e) {
                // Then a GPU bitmap fails the draw, as it did before
            }
        }
        root.draw(canvas);
        return bitmap;
    }
}
