package com.limelight.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Looper;

import com.limelight.AppView;
import com.limelight.R;
import com.limelight.RepoFiles;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The game screen closes when its PC goes offline, and IgnorePcUpdates (src/sharedTest), which the
 * device tests that open it use, keeps it open. It runs the real AppView. The PC list service
 * isn't started: the screen gets a binder that keeps its listener instead of polling a network,
 * and the test tells it what the polls would.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// AppView's settings read MoonBridge's constants; instrumenting it skips loading the native library
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class IgnorePcUpdatesTest {
    private static final String UUID = "22222222-3333-4444-5555-666666666666";

    // What the device tests ran into: three failed polls, or the network dropping, and the screen
    // says "lost connection" and closes, all the way to destroyed
    @Test
    public void aPcThatGoesOfflineClosesTheGameScreen() {
        ActivityController<AppView> controller = open();
        AppView screen = controller.get();
        updatesOf(screen).notifyComputerUpdated(offlinePc(), true);
        idle();
        assertTrue("the game screen stayed open", screen.isFinishing());
        assertEquals(screen.getString(R.string.lost_connection), ShadowToast.getTextOfLatestToast());

        controller.pause().stop().destroy();
        assertTrue(screen.isDestroyed());
    }

    @Test
    public void theGameScreenStaysOpenWhileItIgnoresThePc() {
        IgnorePcUpdates ignoring = new IgnorePcUpdates();
        ignoring.before();
        try {
            AppView screen = open().get();
            ComputerManagerListener updates = updatesOf(screen);
            updates.notifyComputerUpdated(offlinePc(), true);
            idle();
            assertFalse("the game screen closed", screen.isFinishing());
            assertNull("it said something", ShadowToast.getTextOfLatestToast());
        }
        finally {
            ignoring.after();
        }
    }

    // A screen opened after the test has its PC's updates again
    @Test
    public void itStopsWhenTheTestEnds() {
        IgnorePcUpdates ignoring = new IgnorePcUpdates();
        ignoring.before();
        ignoring.after();
        AppView screen = open().get();
        updatesOf(screen).notifyComputerUpdated(offlinePc(), true);
        idle();
        assertTrue("the game screen stayed open", screen.isFinishing());
    }

    // AppViewTest ignored the PC's updates from 2026-10-23, ScreenWidthTest didn't, and it failed
    // on fold-z-36 that way. A device test that opens the game screen for a PC of its own uses
    // IgnorePcUpdates. The smoke tests open it from the PC list for a paired PC, if there is one.
    @Test
    public void everyDeviceTestThatOpensTheGameScreenIgnoresItsPcsUpdates() throws IOException {
        List<String> opening = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (File file : javaFiles(RepoFiles.file("app/src/androidTest/java"))) {
            String code = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (code.contains("AppView.UUID_EXTRA")) {
                opening.add(file.getName());
                if (!code.contains("new IgnorePcUpdates()")) {
                    missing.add(file.getName());
                }
            }
        }
        assertTrue("found none, so the check checks nothing", opening.contains("ScreenWidthTest.java"));
        assertEquals("open the game screen and don't use IgnorePcUpdates", new ArrayList<String>(), missing);
    }

    private static List<File> javaFiles(File dir) {
        List<File> found = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) {
            throw new IllegalStateException("not a folder: " + dir);
        }
        for (File child : children) {
            if (child.isDirectory()) {
                found.addAll(javaFiles(child));
            }
            else if (child.getName().endsWith(".java")) {
                found.add(child);
            }
        }
        return found;
    }

    private static ActivityController<AppView> open() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), AppView.class)
                .putExtra(AppView.NAME_EXTRA, "TEST-PC")
                .putExtra(AppView.UUID_EXTRA, UUID);
        ActivityController<AppView> controller = Robolectric.buildActivity(AppView.class, intent).setup();
        idle();
        return controller;
    }

    // What the screen's service connection does once the PC list service is ready, on a thread
    // of its own (so it's done by hand here): it takes the PC and starts the PC's updates. The
    // binder keeps the screen's listener and gives it an app list poller that doesn't run.
    private static ComputerManagerListener updatesOf(AppView screen) {
        ComputerManagerService service = new ComputerManagerService();
        ComputerManagerListener[] listener = new ComputerManagerListener[1];
        ComputerManagerService.ComputerManagerBinder binder = service.new ComputerManagerBinder() {
            @Override
            public void startPolling(ComputerManagerListener updates) {
                listener[0] = updates;
            }

            @Override
            public ComputerManagerService.ApplistPoller createAppListPoller(ComputerDetails computer) {
                return service.new ApplistPoller(computer) {
                    @Override
                    public void start() {
                    }

                    @Override
                    public void stop() {
                    }
                };
            }
        };
        ComputerDetails pc = new ComputerDetails();
        pc.uuid = UUID;
        pc.name = "TEST-PC";
        set(screen, "computer", pc);
        set(screen, "managerBinder", binder);
        try {
            Method start = AppView.class.getDeclaredMethod("startComputerUpdates");
            start.setAccessible(true);
            start.invoke(screen);
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        assertNotNull("the game screen didn't start its PC's updates", listener[0]);
        return listener[0];
    }

    // The PC as the polls leave it after the third failed one, or after the network drops
    private static ComputerDetails offlinePc() {
        ComputerDetails pc = new ComputerDetails();
        pc.uuid = UUID;
        pc.name = "TEST-PC";
        pc.state = ComputerDetails.State.OFFLINE;
        return pc;
    }

    private static void set(AppView screen, String name, Object value) {
        try {
            Field field = AppView.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(screen, value);
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }
}
