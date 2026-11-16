package com.limelight.testing;

import org.junit.Assume;

/**
 * The phone maker's game mode, where it runs inside the app, as on the RedMagic tablet. When a
 * screen is paused and destroyed back to back, as recreating it does, its com.zte.gameassist
 * GameActivityStub stops an observer it has already cleared, and the app crashes: in 3 of 4 runs
 * there. That code is the phone's, not Starwire's, so a test that recreates a screen skips there:
 *
 * <pre>
 *   ZteGameMode.assumeScreensCanBeRecreated();
 * </pre>
 */
public final class ZteGameMode {
    private ZteGameMode() {}

    /** Skips the test where the phone maker's game mode would crash a recreated screen. */
    public static void assumeScreensCanBeRecreated() {
        Assume.assumeFalse("the phone maker's game mode crashes recreated screens on this device (com.zte.gameassist)",
                present());
    }

    // The class that crashes is part of the system on those devices, so the app can load it there
    private static boolean present() {
        try {
            Class.forName("com.zte.gameassist.app.GameActivityStub");
            return true;
        }
        catch (ClassNotFoundException e) {
            return false;
        }
    }
}
