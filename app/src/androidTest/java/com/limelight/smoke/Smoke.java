package com.limelight.smoke;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.limelight.AppView;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.IdentityManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.testing.TestFiles;
import com.limelight.utils.SessionResumeManager;

import org.junit.Assume;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the smoke tests share: opening the app the way the launcher does, the paired PC, the
 * screenshots device-test.sh pulls (Android/data/<package>/files/smoke), and putting the app's
 * settings back the way they were.
 */
final class Smoke {
    static final long WAIT_MS = 10_000;
    // The PC list polls each PC, so a PC can take a few seconds to show as online
    static final long PC_ONLINE_MS = 30_000;

    final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    final String pkg = target.getPackageName();
    final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private final File shots = TestFiles.file(target, "smoke");

    Smoke() {
        shots.mkdirs();
        // UI Automator waits for the screen to go idle before every look, 10 s by default, and
        // the drifting glow and stars never let it, so each look took 10 s
        Configurator.getInstance().setWaitForIdleTimeout(1000);
    }

    BySelector id(String name) {
        return By.res(pkg, name);
    }

    void shot(String name) {
        device.waitForIdle();
        device.takeScreenshot(new File(shots, name + ".png"));
    }

    /** A line for device-test.sh's results, next to the screenshots. */
    void record(String file, String line) {
        try (FileOutputStream out = new FileOutputStream(new File(shots, file), true)) {
            out.write((line + "\n").getBytes("UTF-8"));
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The screen has to be on and unlocked (device-test.sh checks first, and never unlocks it),
     * and opening the PC list mustn't resume a stream by itself.
     */
    void checkScreen() {
        PowerManager power = (PowerManager) target.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) target.getSystemService(Context.KEYGUARD_SERVICE);
        assertTrue("the screen is off", power.isInteractive());
        assertFalse("the screen is locked", keyguard.isKeyguardLocked());
        // Auto-resume: a stream cut off by the screen going off starts again from the PC list
        Assume.assumeFalse("a stream is waiting to resume, and opening the PC list would start it",
                SessionResumeManager.hasPendingSession(target));
    }

    /** Opens the app from scratch, as the launcher does, and waits for the PC list. */
    void launchApp() {
        Intent launch = target.getPackageManager().getLaunchIntentForPackage(pkg);
        assertNotNull("no launcher entry", launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        target.startActivity(launch);
        assertTrue("the PC list didn't open", device.wait(Until.hasObject(id("pcFragmentContainer")), WAIT_MS));
    }

    /** The first saved PC this app is paired with, or null. */
    ComputerDetails pairedPc() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(target);
        try {
            for (ComputerDetails pc : db.getAllComputers()) {
                if (pc.serverCert != null) {
                    return pc;
                }
            }
            return null;
        }
        finally {
            db.close();
        }
    }

    /**
     * Waits for the PC's card to say it's online and paired, then taps it to open its games.
     * Only then: an offline card opens a menu (with Delete) and an unpaired one starts pairing.
     */
    void openGames(ComputerDetails pc) {
        String online = pc.name + ", " + target.getString(R.string.pcview_menu_header_online);
        UiObject2 card = device.wait(Until.findObject(By.desc(online)), PC_ONLINE_MS);
        Assume.assumeTrue("the paired PC isn't online, or isn't paired any more", card != null);
        // The card turns online as a poll refreshes the grid, and a grid drops a tap that lands
        // while it's being refreshed, so the tap is repeated while the card is still there
        for (int tries = 0; tries < 3; tries++) {
            device.waitForIdle();
            card = device.findObject(By.desc(online));
            // Not again once the games screen is on its way, or there'd be two of it
            if (card == null || alive(AppView.class)) {
                break;
            }
            card.click();
            if (waitFor(() -> alive(AppView.class), 5000)) {
                return;
            }
        }
        if (waitFor(() -> alive(AppView.class), WAIT_MS)) {
            return;
        }
        shot("failed-open-games");
        fail("the PC's games didn't open");
    }

    interface Check {
        boolean done();
    }

    /** Checks every 100 ms, through the app itself rather than UI Automator, so it's quick. */
    static boolean waitFor(Check check, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (!check.done()) {
            if (SystemClock.uptimeMillis() > deadline) {
                return false;
            }
            SystemClock.sleep(100);
        }
        return true;
    }

    /**
     * Opens the PC's games and waits for their tiles. The games screen closes by itself, saying
     * "PC not paired", when one of its polls of the PC says so, and on the tablet some did while
     * the PC answered "paired" to every request made to it directly. So it's opened again, twice
     * at most, and each time goes in notes.txt with what the PC said right after.
     */
    void openGamesWithTiles(ComputerDetails pc) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            openGames(pc);
            // From the cached list straight away, or from the PC a moment later
            waitFor(() -> gameTiles() > 0 || !alive(AppView.class), 20_000);
            if (gameTiles() > 0) {
                return;
            }
            if (alive(AppView.class)) {
                shot("failed-games-empty");
                fail("the PC's game grid is empty");
            }
            noteClosedByItself(pc, "before its games showed");
            assertTrue("the PC list didn't come back", device.wait(Until.hasObject(id("pcFragmentContainer")), WAIT_MS));
        }
        fail("the games screen closed by itself three times in a row");
    }

    void noteClosedByItself(ComputerDetails pc, String when) {
        Host host = askPc(pc, null);
        record("notes.txt", "The games screen closed by itself " + when + "; asked right after, the PC said " +
                (host == null ? "nothing (no answer)" : host.paired ? "it was paired" : "it wasn't paired"));
    }

    /** How many tiles the games screen's grid has (0 when it isn't in front). */
    int gameTiles() {
        int[] tiles = new int[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                View grid = activity instanceof AppView ? activity.findViewById(R.id.fragmentView) : null;
                if (grid instanceof ViewGroup) {
                    tiles[0] = ((ViewGroup) grid).getChildCount();
                }
            }
        });
        return tiles[0];
    }

    /** What the PC itself says, from the same read-only requests the PC list makes. */
    static final class Host {
        boolean paired;
        int runningAppId;
        // The app asked for by name, when paired
        NvApp app;
    }

    /** Asks the PC at each of its addresses in turn; null if none answers as this PC. */
    Host askPc(ComputerDetails pc, String appName) {
        String uniqueId = new IdentityManager(target).getUniqueId();
        for (ComputerDetails.AddressTuple address : new ComputerDetails.AddressTuple[] {
                pc.localAddress, pc.manualAddress, pc.remoteAddress, pc.ipv6Address}) {
            if (address == null) {
                continue;
            }
            try {
                NvHTTP http = new NvHTTP(address, 0, uniqueId, pc.serverCert,
                        PlatformBinding.getCryptoProvider(target));
                ComputerDetails details = http.getComputerDetails(true);
                if (!pc.uuid.equals(details.uuid)) {
                    // Another PC answers at that address now
                    continue;
                }
                Host host = new Host();
                host.paired = details.pairState == PairingManager.PairState.PAIRED;
                host.runningAppId = details.runningGameId;
                if (host.paired && appName != null) {
                    for (NvApp app : http.getAppList()) {
                        if (appName.equals(app.getAppName())) {
                            host.app = app;
                        }
                    }
                }
                return host;
            }
            catch (IOException | XmlPullParserException e) {
                // Try the PC's next address
            }
        }
        return null;
    }

    /** Back, and the PC list is in front again. */
    void backToPcList() {
        device.pressBack();
        if (!device.wait(Until.hasObject(id("pcFragmentContainer")), WAIT_MS)) {
            shot("failed-back");
            fail("Back didn't return to the PC list");
        }
    }

    /** The app's activity in front, or null. */
    static Activity resumedActivity() {
        Activity[] found = new Activity[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> found[0] = resumedActivityOnMain());
        return found[0];
    }

    /** The same, from code already on the main thread. */
    static Activity resumedActivityOnMain() {
        Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
        return resumed.isEmpty() ? null : resumed.iterator().next();
    }

    /** Whether any activity of this class is still alive. */
    static boolean alive(Class<? extends Activity> type) {
        boolean[] found = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Stage stage : new Stage[] {Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED,
                    Stage.RESUMED, Stage.PAUSED, Stage.STOPPED}) {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)) {
                    found[0] |= type.isInstance(activity);
                }
            }
        });
        return found[0];
    }

    // Settings: the main ones and the remembered covers and tints (AppView's "CoverGlow")
    private static final String[] SETTINGS_FILES = {null, "CoverGlow"};
    // Kept in a file, so a crash or a killed run can't lose them: the next smoke test puts them back
    private static final String BACKUP_FILE = "smoke-settings-backup";

    private SharedPreferences settings(String name) {
        return name == null ? PreferenceManager.getDefaultSharedPreferences(target)
                : target.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    /** Every setting as it is now, by file (the main settings under ""). */
    HashMap<String, HashMap<String, Object>> readSettings() {
        HashMap<String, HashMap<String, Object>> all = new HashMap<>();
        for (String name : SETTINGS_FILES) {
            all.put(name == null ? "" : name, new HashMap<>(settings(name).getAll()));
        }
        return all;
    }

    /** Keeps the settings until restoreSettings(), putting back any an earlier run left changed. */
    void saveSettings() {
        restoreSettings();
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(backupFile()))) {
            out.writeObject(readSettings());
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    void restoreSettings() {
        File file = backupFile();
        if (!file.exists()) {
            return;
        }
        HashMap<String, HashMap<String, Object>> saved;
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
            saved = (HashMap<String, HashMap<String, Object>>) in.readObject();
        }
        catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        for (String name : SETTINGS_FILES) {
            Map<String, Object> values = saved.get(name == null ? "" : name);
            if (values.equals(settings(name).getAll())) {
                continue;
            }
            SharedPreferences.Editor editor = settings(name).edit().clear();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
                else if (v instanceof Integer) editor.putInt(e.getKey(), (Integer) v);
                else if (v instanceof Long) editor.putLong(e.getKey(), (Long) v);
                else if (v instanceof Float) editor.putFloat(e.getKey(), (Float) v);
                else if (v instanceof String) editor.putString(e.getKey(), (String) v);
                else if (v instanceof Set) editor.putStringSet(e.getKey(), (Set<String>) v);
            }
            assertTrue("couldn't put the settings back", editor.commit());
        }
        assertTrue("couldn't remove " + file, file.delete());
    }

    private File backupFile() {
        return new File(target.getNoBackupFilesDir(), BACKUP_FILE);
    }
}
