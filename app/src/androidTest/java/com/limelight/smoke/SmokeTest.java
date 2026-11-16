package com.limelight.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.UiAutomation;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.limelight.AppView;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.SizeChange;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.StreamSettings;
import com.limelight.testing.Automation;
import com.limelight.testing.ZteGameMode;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * The screens a person goes through, driven the way they'd drive them (UI Automator): the app
 * opens to the PC list, every settings section opens from its pill, the screens survive rotating,
 * changing size and being recreated, and a paired PC shows its games. Nothing here streams or
 * changes a setting. The step that needs the PC skips when no paired PC is online.
 * device-test.sh runs these and pulls the screenshots.
 */
@RunWith(AndroidJUnit4.class)
public class SmokeTest {
    private final Smoke smoke = new Smoke();

    @Before
    public void setUp() {
        smoke.checkScreen();
        smoke.saveSettings();
    }

    @After
    public void tearDown() {
        smoke.restoreSettings();
    }

    @Test
    public void appOpensToThePcList() {
        smoke.launchApp();
        assertTrue("neither the PC grid nor the searching message showed",
                smoke.device.wait(Until.hasObject(smoke.id("fragmentView")), Smoke.WAIT_MS) ||
                        smoke.device.hasObject(smoke.id("no_pc_found_layout")));
        // Every saved PC gets a card, whether or not it's online
        ComputerDatabaseManager db = new ComputerDatabaseManager(smoke.target);
        List<ComputerDetails> saved;
        try {
            saved = db.getAllComputers();
        }
        finally {
            db.close();
        }
        for (ComputerDetails pc : saved) {
            assertTrue("a saved PC has no card",
                    smoke.device.wait(Until.hasObject(By.descStartsWith(pc.name + ", ")), Smoke.WAIT_MS));
        }
        smoke.shot("01-pc-list");
    }

    @Test
    public void everySettingsSectionOpensFromItsPill() {
        smoke.launchApp();
        HashMap<String, HashMap<String, Object>> before = smoke.readSettings();

        UiObject2 settings = smoke.device.wait(Until.findObject(smoke.id("settingsButton")), Smoke.WAIT_MS);
        assertNotNull("no settings button", settings);
        settings.click();
        assertTrue("settings didn't open", smoke.device.wait(Until.hasObject(smoke.id("stream_settings")), Smoke.WAIT_MS));

        List<String> pills = pillLabels();
        assertTrue("only " + pills.size() + " section pills", pills.size() >= 2);
        List<String> missed = new ArrayList<>();
        for (int i = 0; i < pills.size(); i++) {
            UiObject2 pill = findPill(pills.get(i));
            assertNotNull("can't reach the " + pills.get(i) + " pill", pill);
            pill.click();
            assertTrue("the " + pills.get(i) + " pill didn't light up", pill.wait(Until.selected(true), 3000));
            String stopped = waitForSection(i);
            if (stopped != null) {
                missed.add(pills.get(i) + ": " + stopped);
            }
            smoke.shot(String.format("02-settings-%02d", i));
        }
        assertTrue("these pills didn't bring their section to the top: " + missed, missed.isEmpty());

        // And the list goes to its very end. Moved in code, not swiped, so no swipe can catch a
        // slider or a switch on the way.
        Activity settingsScreen = Smoke.resumedActivity();
        assertNotNull("settings isn't in front", settingsScreen);
        int[] positions = new int[2];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ListView list = settingsScreen.findViewById(android.R.id.list);
            positions[1] = list.getCount() - 1;
            list.setSelection(positions[1]);
        });
        SystemClock.sleep(500);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ListView list = settingsScreen.findViewById(android.R.id.list);
            positions[0] = list.getLastVisiblePosition();
        });
        assertEquals("the last setting isn't on screen", positions[1], positions[0]);
        smoke.shot("03-settings-end");

        smoke.backToPcList();
        // Every setting there before is still there with its value. (A screen may save the
        // default of a setting it shows for the first time, so new ones are allowed.)
        HashMap<String, HashMap<String, Object>> after = smoke.readSettings();
        for (String file : before.keySet()) {
            for (Map.Entry<String, Object> setting : before.get(file).entrySet()) {
                assertEquals("looking through the settings changed " + setting.getKey(),
                        setting.getValue(), after.get(file).get(setting.getKey()));
            }
        }
    }

    // Null once the pill's section heading is at the top of the list, or showing with the list as
    // far down as it goes (the last sections are too short to reach the top); otherwise where
    // the list stopped
    private String waitForSection(int index) {
        Activity screen = Smoke.resumedActivity();
        assertNotNull("settings isn't in front", screen);
        String[] stopped = {"not checked"};
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (stopped[0] != null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(200);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ListView list = screen.findViewById(android.R.id.list);
                // The pills are the settings' top-level headings that have a title, in order
                PreferenceScreen top = ((PreferenceFragment) screen.getFragmentManager()
                        .findFragmentById(R.id.stream_settings)).getPreferenceScreen();
                Preference section = null;
                for (int i = 0, seen = -1; i < top.getPreferenceCount() && section == null; i++) {
                    Preference preference = top.getPreference(i);
                    if (preference instanceof PreferenceCategory && preference.getTitle() != null &&
                            ++seen == index) {
                        section = preference;
                    }
                }
                int heading = -1;
                for (int p = 0; p < list.getCount() && heading < 0; p++) {
                    if (list.getItemAtPosition(p) == section) {
                        heading = p;
                    }
                }
                int first = list.getFirstVisiblePosition();
                View row = heading >= first && heading <= list.getLastVisiblePosition() ?
                        list.getChildAt(heading - first) : null;
                int fromTop = row == null ? Integer.MIN_VALUE : row.getTop() - list.getPaddingTop();
                stopped[0] = row != null && (Math.abs(fromTop) <= 2 || !list.canScrollList(1)) ? null
                        : "its heading is row " + heading + ", the list shows rows " + first + " to " +
                        list.getLastVisiblePosition() + (row == null ? "" : ", the heading " + fromTop + " px down");
            });
        }
        return stopped[0];
    }

    // The pills' labels, read from the screen itself (they're made from the settings' sections)
    private List<String> pillLabels() {
        List<String> labels = new ArrayList<>();
        long deadline = SystemClock.uptimeMillis() + Smoke.WAIT_MS;
        while (labels.size() < 2 && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(200);
            labels.clear();
            Activity activity = Smoke.resumedActivity();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                LinearLayout row = activity == null ? null : activity.findViewById(R.id.settingsSections);
                for (int i = 0; row != null && i < row.getChildCount(); i++) {
                    labels.add(((TextView) row.getChildAt(i)).getText().toString());
                }
            });
        }
        return labels;
    }

    // A pill by its label, scrolling the row of pills sideways if it's off screen
    private UiObject2 findPill(String label) {
        for (int tries = 0; tries < 8; tries++) {
            UiObject2 row = smoke.device.findObject(smoke.id("settingsSections"));
            UiObject2 pill = row == null ? null : row.findObject(By.text(label));
            if (pill != null) {
                return pill;
            }
            UiObject2 scroll = smoke.device.findObject(smoke.id("settingsSectionsScroll"));
            if (scroll == null) {
                return null;
            }
            scroll.scroll(Direction.RIGHT, 0.6f);
        }
        return null;
    }

    // Through the app, not the device's rotation setting, which is left alone. Then each screen
    // is handed a phone's size and its own back, as folding, unfolding or split screen would
    // hand them, and has to rebuild in place: the same screen, still open, with its content.
    // No screen recreates itself any more. The games screen used to when its set of sizes
    // changed, and this test recreated each screen the same way; on the RedMagic tablet that
    // crashed the app in 3 of 4 runs, in the phone maker's game mode (see the next test).
    @Test
    public void screensSurviveRotationAndSizeChanges() {
        smoke.launchApp();
        checkResized(PcView.class, R.id.pcFragmentContainer, "04-resized-pc-list");

        openSettings();
        checkResized(StreamSettings.class, R.id.stream_settings, "04-resized-settings");
        smoke.backToPcList();
    }

    // Android itself still recreates a screen for a change the app doesn't take on, such as a new
    // display size or language. That's skipped where the phone maker's game mode runs inside the
    // app, as on the RedMagic tablet: it crashes recreated screens there (ZteGameMode).
    @Test
    public void screensSurviveBeingRecreated() {
        ZteGameMode.assumeScreensCanBeRecreated();
        smoke.launchApp();
        checkRecreated(PcView.class, R.id.pcFragmentContainer, "04-recreated-pc-list");

        openSettings();
        checkRecreated(StreamSettings.class, R.id.stream_settings, "04-recreated-settings");
        smoke.backToPcList();
    }

    // The first time a big screen letterboxes the app (it asked for an orientation the screen won't
    // turn to), Android lays a one-time "See and do more" card over it, and a tap anywhere
    // closes the card without reaching the app. On the Android 15 tablet emulator that
    // tap was the one on the settings button, so settings never opened in the first pass after a
    // fresh start, and did in every later one. A tap counts once the app reports the click; one
    // the system took is made again, at most three in all.
    private void openSettings() {
        UiObject2 settings = smoke.device.wait(Until.findObject(smoke.id("settingsButton")), Smoke.WAIT_MS);
        assertNotNull("no settings button", settings);
        for (int taps = 1; !tapReachesApp(settings); taps++) {
            if (Smoke.resumedActivity() instanceof StreamSettings) {
                break;
            }
            assertTrue("a window over the PC list took " + taps + " taps on the settings button", taps < 3);
            android.util.Log.i("SmokeTest", "A window over the PC list took the tap on the settings button; tapping again");
        }
        assertTrue("settings didn't open", Smoke.waitFor(() -> Smoke.resumedActivity() instanceof StreamSettings, Smoke.WAIT_MS));
    }

    // Taps the view and waits for the app to report the click (every view's click sends one to
    // accessibility, which UI Automator turns on). False when something else took the tap.
    private boolean tapReachesApp(UiObject2 view) {
        UiAutomation automation = Automation.uiAutomation();
        try {
            automation.executeAndWaitForEvent(view::click,
                    event -> event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                            && smoke.pkg.equals(String.valueOf(event.getPackageName())),
                    Smoke.WAIT_MS);
            return true;
        }
        catch (TimeoutException e) {
            return false;
        }
    }

    private void checkResized(Class<? extends Activity> screen, int mainView, String shotName) {
        Activity before = Smoke.resumedActivity();
        assertTrue(screen.getSimpleName() + " isn't in front", screen.isInstance(before));
        for (int orientation : new int[] {ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> before.setRequestedOrientation(orientation));
            SystemClock.sleep(700);
        }
        checkStillOpen(screen, before, mainView);

        // A phone held upright, narrower than 480 dp, then the screen's own size again
        Configuration own = SizeChange.apply(before, 400, 860);
        try {
            SystemClock.sleep(700);
            checkStillOpen(screen, before, mainView);
            smoke.shot(shotName + "-phone");
        }
        finally {
            SizeChange.restore(before, own);
        }
        SystemClock.sleep(700);
        checkStillOpen(screen, before, mainView);
        smoke.shot(shotName);
    }

    // The same screen is in front, not closing, with its content
    private static void checkStillOpen(Class<? extends Activity> screen, Activity expected, int mainView) {
        Activity now = Smoke.resumedActivity();
        assertSame(screen.getSimpleName() + " was replaced", expected, now);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(screen.getSimpleName() + " is closing", now.isFinishing());
            assertNotNull(screen.getSimpleName() + " lost its content", now.findViewById(mainView));
        });
    }

    private void checkRecreated(Class<? extends Activity> screen, int mainView, String shotName) {
        Activity before = Smoke.resumedActivity();
        assertTrue(screen.getSimpleName() + " isn't in front", screen.isInstance(before));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(before::recreate);
        assertTrue(screen.getSimpleName() + " didn't come back", Smoke.waitFor(() -> {
            Activity now = Smoke.resumedActivity();
            return screen.isInstance(now) && now != before;
        }, Smoke.WAIT_MS));
        Activity after = Smoke.resumedActivity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertFalse(screen.getSimpleName() + " is closing", after.isFinishing());
            assertNotNull(screen.getSimpleName() + " lost its content", after.findViewById(mainView));
        });
        smoke.shot(shotName);
    }

    @Test
    public void pairedPcShowsItsGames() {
        ComputerDetails pc = smoke.pairedPc();
        Assume.assumeTrue("no paired PC on this device", pc != null);

        smoke.launchApp();
        // From the cached list straight away, or from the PC a moment later
        smoke.openGamesWithTiles(pc);
        smoke.shot("05-games");

        if (Smoke.alive(AppView.class)) {
            smoke.device.pressBack();
            boolean pcList = smoke.device.wait(Until.hasObject(smoke.id("pcFragmentContainer")), Smoke.WAIT_MS);
            if (!pcList && Smoke.resumedActivity() == null) {
                // It closed by itself just before Back, so Back left the app
                smoke.noteClosedByItself(pc, "just before Back");
            }
            else {
                assertTrue("Back didn't return to the PC list", pcList);
            }
        }
        else {
            smoke.noteClosedByItself(pc, "after its games showed");
            assertTrue("the PC list didn't come back",
                    smoke.device.wait(Until.hasObject(smoke.id("pcFragmentContainer")), Smoke.WAIT_MS));
        }
    }
}
