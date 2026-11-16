package com.limelight.testing;

import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.limelight.AppView;

import org.junit.rules.ExternalResource;

import java.lang.reflect.Field;

/**
 * Keeps a test's game screen open whatever its PC does. While the test runs, every game screen
 * (AppView) ignores its PC's updates from the moment it's created, as it does while a game quits.
 * The game list still comes from the cache.
 *
 * The device tests' PC has an address that never answers. After three failed polls the PC list
 * service calls it offline, and the game screen says "lost connection" and closes, as it should
 * for a real PC: 12.5 s after launch on the RedMagic tablet, 16.4 s on the fold-z-36 emulator. It
 * closes at once when the phone's network drops, which the emulators' network does now and then:
 * 5.8 s after launch on fold-z-36. A test that sleeps and draws full-size pictures for a few seconds
 * races that. AppViewTest failed 1 run in 3 that way (2026-10-23). On fold-z-36 (2026-10-27)
 * ScreenWidthTest found its screen gone, and the next screen closed with the test still running.
 * Its main thread didn't take the window's focus change for 5 s, and Android ended the run as not
 * responding. The run kept no stack, so what held the main thread isn't known: the test drawing
 * a full-size picture is likely, but not shown.
 *
 * It lives in src/sharedTest so the JVM tests check it too (IgnorePcUpdatesTest).
 * AppViewTest#aLostPcClosesTheScreen closes a screen on purpose, so the closing is still tested.
 */
public class IgnorePcUpdates extends ExternalResource {
    private final ActivityLifecycleCallback ignore = (activity, stage) -> {
        if (activity instanceof AppView && stage == Stage.PRE_ON_CREATE) {
            setIgnoring((AppView) activity, true);
        }
    };

    @Override
    protected void before() {
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(ignore);
    }

    @Override
    protected void after() {
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(ignore);
    }

    /** Makes the game screen ignore its PC's updates, or take them again. On the main thread. */
    public static void setIgnoring(AppView screen, boolean ignoring) {
        try {
            Field suspend = AppView.class.getDeclaredField("suspendGridUpdates");
            suspend.setAccessible(true);
            suspend.setBoolean(screen, ignoring);
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
