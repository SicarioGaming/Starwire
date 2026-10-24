package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;

import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.AppPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.ui.AppIcons;
import com.limelight.ui.CollapsingHeader;
import com.limelight.ui.CoverGlow;
import com.limelight.ui.Rainbow;
import com.limelight.ui.CoverImageView;
import com.limelight.ui.Loops;
import com.limelight.utils.QuickLaunchManager;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Service;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends Activity implements AdapterFragmentCallbacks {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;
    private QuickLaunchManager quickLaunchManager;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    // Cover Glow home: the hero shows the selected game, and the screen takes its cover's colour
    private static final String COVER_GLOW_PREF_FILENAME = "CoverGlow";
    private View appRoot, coverGlow, heroDetails;
    // The game screen has four sets of sizes (cover_glow_dimens.xml): narrow below 480 dp wide,
    // the same with a smaller wordmark below 360 dp wide, a smaller header on wide screens under
    // 520 dp tall (a phone held sideways), and the full one
    private static final int SMALL_WIDTH_DP = 360;
    private static final int NARROW_WIDTH_DP = 480;
    private static final int SHORT_HEIGHT_DP = 520;
    private int builtSizes;

    private CoverImageView heroCover;
    private TextView heroLoaderText, heroLabel, heroTitle, miniTitle;
    private LinearLayout heroChips;
    private Button heroPlay, heroQuit, miniPlay;
    private CollapsingHeader collapsingHeader;
    private AppObject selectedApp;
    private int currentTint = CoverGlow.DEFAULT_TINT;
    private ValueAnimator tintAnimator;
    private ObjectAnimator glowAnimator;
    private ObjectAnimator coverFloat;
    private ValueAnimator wordmarkRainbow;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int HIDE_APP_ID = 7;
    private final static int APP_SETTINGS_ID = 8;
    private final static int ADD_QUICK_LAUNCH_ID = 9;
    private final static int START_VIRTUAL_DISPLAY_ID = 10;
    private final static int START_PRIMARY_DISPLAY_ID = 11;
    
    private final static int REQUEST_APP_SETTINGS = 1001;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Folding, unfolding, split screen or turning the phone into another set of sizes needs
        // the layout built again. It's built again on this same screen, not with recreate(): on
        // the RedMagic tablet recreating crashed the app in 3 of 4 tries, inside the phone
        // maker's game mode (com.zte.gameassist GameActivityStub stops an observer it has already
        // cleared when a screen is paused and destroyed back to back).
        boolean newSizes = sizesFor(newConfig) != builtSizes;
        if (newSizes) {
            buildScreen(newConfig);
        }

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }

        // The new hero shows the game that was selected, with its cover, chips and Play button
        if (newSizes && selectedApp != null) {
            selectApp(selectedApp, false);
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details, boolean isFreshPoll) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, getResources().getText(R.string.lost_connection), Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, getResources().getText(R.string.scut_not_paired), Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);
        quickLaunchManager = QuickLaunchManager.getInstance(this);

        UiHelper.setLocale(this);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        setTitle(getIntent().getStringExtra(NAME_EXTRA));
        buildScreen(getResources().getConfiguration());

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs(this);

        cancelAnimations();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();
        setLoopsRunning(true);
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
        setLoopsRunning(false);
    }

    // The screen's endless animations stop while it's out of sight (during a stream, say), so they
    // don't keep waking the CPU every frame
    private void setLoopsRunning(boolean running) {
        for (ValueAnimator loop : new ValueAnimator[] {glowAnimator, coverFloat, wordmarkRainbow}) {
            if (loop == null) {
                continue;
            }
            if (running) {
                Loops.run(this, loop);
            }
            else {
                Loops.hold(loop);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_APP_SETTINGS) {
            // Refresh the grid adapter to update settings indicators
            if (appGridAdapter != null) {
                appGridAdapter.notifyDataSetChanged();
            }
            // The hero's chips show the game's settings
            refreshHero(false);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        if (!(menuInfo instanceof AdapterContextMenuInfo)) {
            return;
        }
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId != 0) {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
            }
        }
        else {
            // Pick the host display for this launch (Apollo/Vibepollo; from Artemide)
            menu.add(Menu.NONE, START_VIRTUAL_DISPLAY_ID, 1, getResources().getString(R.string.applist_menu_start_vdisplay));
            menu.add(Menu.NONE, START_PRIMARY_DISPLAY_ID, 1, getResources().getString(R.string.applist_menu_start_primarydisplay));
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));
        menu.add(Menu.NONE, APP_SETTINGS_ID, 5, "App Settings");
        menu.add(Menu.NONE, ADD_QUICK_LAUNCH_ID, 6, getResources().getString(R.string.applist_menu_add_quick_launch));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.targetView != null) {
            // Only add an option to create shortcut if the tile's picture is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
            if (appImageView != null && shortcutBitmap(appImageView) != null) {
                menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 6, getResources().getString(R.string.applist_menu_scut));
            }
        }
    }

    // The tile's picture as a bitmap a pinned shortcut can use. Desktop and the other icon tiles are
    // drawn, not bitmaps (casting them to BitmapDrawable crashed the menu), and box art can be a GPU
    // (HARDWARE) bitmap, which shortcut icons can't take, so both are redrawn into a normal one.
    // Null while there's nothing to show.
    static Bitmap shortcutBitmap(ImageView view) {
        Drawable drawable = view == null ? null : view.getDrawable();
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            return bitmap;
        }
        int width = view.getWidth() > 0 ? view.getWidth() : drawable.getIntrinsicWidth();
        int height = view.getHeight() > 0 ? view.getHeight() : drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Rect bounds = drawable.copyBounds();
        drawable.setBounds(0, 0, width, height);
        drawable.draw(new Canvas(bitmap));
        drawable.setBounds(bounds);
        return bitmap;
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (!(item.getMenuInfo() instanceof AdapterContextMenuInfo)) {
            return super.onContextItemSelected(item);
        }
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final AppObject app = (AppObject) appGridAdapter.getItem(info.position);
        switch (item.getItemId()) {
            case START_WITH_QUIT:
            case START_OR_RESUME_ID:
            case START_VIRTUAL_DISPLAY_ID:
            case START_PRIMARY_DISPLAY_ID:
                // Cover Glow: the home screen's hero shows the last game played
                rememberLastPlayed(app);
                break;
            default:
                break;
        }
        switch (item.getItemId()) {
            case START_WITH_QUIT:
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doStart(AppView.this, app.app, computer, managerBinder);
                    }
                }, null);
                return true;

            case START_OR_RESUME_ID:
                // Resume is the same as start for us
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder);
                return true;

            case START_VIRTUAL_DISPLAY_ID:
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder,
                        StreamConfiguration.VIRTUAL_DISPLAY_ON);
                return true;

            case START_PRIMARY_DISPLAY_ID:
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder,
                        StreamConfiguration.VIRTUAL_DISPLAY_OFF);
                return true;

            case QUIT_ID:
                quitApp(app);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;

            case APP_SETTINGS_ID:
                openAppSettings(app);
                return true;

            case HIDE_APP_ID:
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                }
                else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;

            case CREATE_SHORTCUT_ID:
                Bitmap appBits = shortcutBitmap((ImageView) info.targetView.findViewById(R.id.grid_image));
                if (appBits == null || !shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;

            case ADD_QUICK_LAUNCH_ID:
                addToQuickLaunch(computer, app.app);
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }

    // Ends the running game on the PC, after asking (from the tile's menu or the hero's Quit)
    private void quitApp(final AppObject app) {
        UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
            @Override
            public void run() {
                suspendGridUpdates = true;
                ServerHelper.doQuit(AppView.this, computer,
                        app.app, managerBinder, new Runnable() {
                    @Override
                    public void run() {
                        // Trigger a poll immediately
                        suspendGridUpdates = false;
                        if (poller != null) {
                            poller.pollNow();
                        }
                    }
                });
            }
        }, null);
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // The hero says Resume for the running game
                ensureHeroSelection();
                refreshHero(false);

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, "App removed from PC");
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
                ensureHeroSelection();
            }
        });
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(appGridAdapter);
        // The covers arrive one after another the first time the list lays out
        listView.setLayoutAnimation(new android.view.animation.LayoutAnimationController(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.cg_tile_in), 0.07f));
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                AppObject app = (AppObject) appGridAdapter.getItem(pos);

                // Cover Glow: the first tap shows a game in the hero, a second tap plays it
                if (app != selectedApp) {
                    selectApp(app, true);
                    return;
                }

                // Only open the context menu if something is running, otherwise start it
                if (lastRunningAppId != 0) {
                    openContextMenu(arg1);
                } else {
                    startApp(app);
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        if (collapsingHeader != null) {
            collapsingHeader.attach(listView);
        }
        registerForContextMenu(listView);
        listView.requestFocus();
    }

    // Inflates the screen for the current set of sizes and wires it up. It runs again on the same
    // screen when the sizes change; the games, the selected game and the PC stay as they are.
    private void buildScreen(Configuration config) {
        // The endless animations move the views being replaced
        cancelAnimations();

        setContentView(R.layout.activity_app_view);
        builtSizes = sizesFor(config);
        UiHelper.notifyNewRootView(this);

        String computerName = getIntent().getStringExtra(NAME_EXTRA);
        TextView label = findViewById(R.id.appListText);
        label.setText(computerName);
        setUpHero(computerName);

        // Built while out of sight, the new loops wait for onResume
        setLoopsRunning(inForeground);
    }

    private void cancelAnimations() {
        for (android.animation.Animator animator : new android.animation.Animator[] {tintAnimator, glowAnimator, coverFloat, wordmarkRainbow}) {
            if (animator != null) {
                animator.cancel();
            }
        }
    }

    private void setUpHero(String computerName) {
        appRoot = findViewById(R.id.appRoot);
        coverGlow = findViewById(R.id.coverGlow);
        heroDetails = findViewById(R.id.heroDetails);
        heroCover = findViewById(R.id.heroCover);
        heroLoaderText = findViewById(R.id.heroLoaderText);
        heroLabel = findViewById(R.id.heroLabel);
        heroTitle = findViewById(R.id.heroTitle);
        heroChips = findViewById(R.id.heroChips);
        heroPlay = findViewById(R.id.heroPlay);
        heroQuit = findViewById(R.id.heroQuit);
        // Rounded corners before Android 12, which ignores the XML attribute
        heroCover.setClipToOutline(true);
        miniTitle = findViewById(R.id.miniTitle);
        miniPlay = findViewById(R.id.miniPlay);
        collapsingHeader = new CollapsingHeader(findViewById(R.id.appHeader),
                findViewById(R.id.heroSection), findViewById(R.id.miniBar));
        collapsingHeader.expandOnFocus(heroPlay, heroQuit, findViewById(R.id.heroSettings), findViewById(R.id.appListText));
        heroQuit.setOnClickListener(v -> {
            if (selectedApp != null && computer != null) {
                quitApp(selectedApp);
            }
        });
        miniPlay.setOnClickListener(v -> playSelectedApp());

        TextView coverRowLabel = findViewById(R.id.coverRowLabel);
        coverRowLabel.setText(getString(R.string.cg_on_pc, computerName));
        heroTitle.setText(R.string.cg_pick_a_game);

        heroCover.setOnCoverLoadedListener(new CoverImageView.OnCoverLoadedListener() {
            @Override
            public void onCoverLoaded(Bitmap cover) {
                int tint = CoverGlow.tintFromBitmap(cover);
                animateTint(tint);
                if (selectedApp != null) {
                    CoverGlow.rememberTint(AppView.this, uuidString, selectedApp.app.getAppId(), tint);
                }
            }
        });
        heroPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playSelectedApp();
            }
        });
        findViewById(R.id.heroSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedApp != null && computer != null) {
                    openAppSettings(selectedApp);
                }
            }
        });

        applyTint(currentTint);

        // The glow drifts slowly behind everything. Like the loops below, it stays still when
        // the user has animations off (Loops).
        float drift = dp(30);
        glowAnimator = ObjectAnimator.ofPropertyValuesHolder(coverGlow,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0, -drift),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0, drift * 0.7f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f));
        glowAnimator.setDuration(4500);
        glowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        glowAnimator.setRepeatMode(ValueAnimator.REVERSE);
        Loops.run(this, glowAnimator);

        // The hero's cover floats gently (moving a view redraws nothing, so it costs little)
        coverFloat = ObjectAnimator.ofFloat(heroCover, View.TRANSLATION_Y, 0, -dp(5));
        coverFloat.setDuration(3200);
        coverFloat.setRepeatCount(ValueAnimator.INFINITE);
        coverFloat.setRepeatMode(ValueAnimator.REVERSE);
        coverFloat.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        Loops.run(this, coverFloat);

        // The wordmark slowly flows through the rainbow
        wordmarkRainbow = Rainbow.applyTo((TextView) findViewById(R.id.cgWordmark));
    }

    private static int sizesFor(Configuration config) {
        if (config.screenWidthDp < NARROW_WIDTH_DP) {
            return config.screenWidthDp < SMALL_WIDTH_DP ? 3 : 0;
        }
        return config.screenHeightDp < SHORT_HEIGHT_DP ? 1 : 2;
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // Picks the hero's game when there is none yet, or its game left the list: the running game,
    // else the one last played from this device, else the first
    private void ensureHeroSelection() {
        if (appGridAdapter == null || appGridAdapter.getCount() == 0) {
            return;
        }
        if (selectedApp != null) {
            for (int i = 0; i < appGridAdapter.getCount(); i++) {
                if (appGridAdapter.getItem(i) == selectedApp) {
                    return;
                }
            }
        }

        int lastPlayedId = getSharedPreferences(COVER_GLOW_PREF_FILENAME, MODE_PRIVATE)
                .getInt("last_" + uuidString, 0);
        AppObject pick = (AppObject) appGridAdapter.getItem(0);
        for (int i = 0; i < appGridAdapter.getCount(); i++) {
            AppObject app = (AppObject) appGridAdapter.getItem(i);
            if (lastRunningAppId != 0 && app.app.getAppId() == lastRunningAppId) {
                pick = app;
                break;
            }
            if (app.app.getAppId() == lastPlayedId) {
                pick = app;
            }
        }
        selectApp(pick, false);
    }

    private void selectApp(AppObject app, boolean animate) {
        selectedApp = app;
        appGridAdapter.setSelectedAppId(app.app.getAppId());
        appGridAdapter.loadCover(app, heroCover, heroLoaderText);
        // The loader hides the cover while it loads; the hero keeps its frame and colour
        heroCover.setVisibility(View.VISIBLE);
        if (AppIcons.useIcon(this, app.app.getAppName())) {
            // Icon tiles never load a cover, so the screen takes the tile's colour
            int tint = AppIcons.accentFor(app.app.getAppName());
            animateTint(tint);
            CoverGlow.rememberTint(this, uuidString, app.app.getAppId(), tint);
        }
        refreshHero(animate);
    }

    // The hero's title, label, chips and Play/Resume for the selected game
    private void refreshHero(boolean animate) {
        if (selectedApp == null || heroTitle == null) {
            return;
        }
        int appId = selectedApp.app.getAppId();
        boolean running = lastRunningAppId != 0 && lastRunningAppId == appId;
        int lastPlayedId = getSharedPreferences(COVER_GLOW_PREF_FILENAME, MODE_PRIVATE)
                .getInt("last_" + uuidString, 0);

        heroTitle.setText(selectedApp.app.getAppName());
        heroPlay.setText(running ? R.string.cg_resume : R.string.cg_play);
        // Quit right beside Resume, instead of only in the tile's long-press menu
        if (running && heroQuit.getVisibility() != View.VISIBLE) {
            heroQuit.setVisibility(View.VISIBLE);
            heroQuit.setAlpha(0f);
            heroQuit.setTranslationX(-dp(10));
            heroQuit.animate().alpha(1f).translationX(0).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        else if (!running) {
            heroQuit.animate().cancel();
            heroQuit.setVisibility(View.GONE);
        }
        miniTitle.setText(selectedApp.app.getAppName());
        miniPlay.setText(running ? R.string.cg_resume : R.string.cg_play);
        // TalkBack says which game the buttons are for ("Resume Cyberpunk 2077")
        String playLabel = getString(running ? R.string.cg_a11y_resume_game : R.string.cg_a11y_play_game,
                selectedApp.app.getAppName());
        heroPlay.setContentDescription(playLabel);
        miniPlay.setContentDescription(playLabel);
        heroQuit.setContentDescription(getString(R.string.cg_a11y_quit_game, selectedApp.app.getAppName()));
        if (running) {
            heroLabel.setText(R.string.cg_running);
            heroLabel.setVisibility(View.VISIBLE);
        }
        else if (appId == lastPlayedId) {
            heroLabel.setText(R.string.cg_last_played);
            heroLabel.setVisibility(View.VISIBLE);
        }
        else {
            heroLabel.setVisibility(View.INVISIBLE);
        }
        showChips();

        if (animate) {
            heroDetails.setAlpha(0f);
            heroDetails.setTranslationY(dp(16));
            heroDetails.animate().alpha(1f).translationY(0)
                    .setDuration(380).setInterpolator(new DecelerateInterpolator(2f)).start();
            heroCover.setScaleX(0.94f);
            heroCover.setScaleY(0.94f);
            heroCover.animate().scaleX(1f).scaleY(1f)
                    .setDuration(420).setInterpolator(new DecelerateInterpolator(2f)).start();
            // With the header scrolled away, the bar shows the change instead
            miniTitle.setAlpha(0f);
            miniTitle.animate().alpha(1f).setDuration(300).start();
        }
    }

    // The selected game's stream settings as small labels (its own settings where it has them)
    private void showChips() {
        heroChips.removeAllViews();
        if (computer == null) {
            return;
        }
        String appKey = computer.uuid + ":" + selectedApp.app.getAppId();
        PreferenceConfiguration config = AppPreferences.getEffectivePreferences(this, appKey);

        java.util.ArrayList<String> chips = new java.util.ArrayList<>();
        switch (config.videoFormat) {
            case FORCE_PYROWAVE:
                chips.add(getString(R.string.cg_chip_pyrowave));
                break;
            case FORCE_AV1:
                chips.add(getString(R.string.cg_chip_av1));
                break;
            case FORCE_HEVC:
                chips.add(getString(R.string.cg_chip_hevc));
                break;
            case FORCE_H264:
                chips.add(getString(R.string.cg_chip_h264));
                break;
            default:
                break;
        }
        chips.add(getString(R.string.cg_chip_resolution, config.height, config.fps));
        if (config.warpFactor == 2) {
            chips.add(getString(R.string.cg_chip_warp));
        }
        else if (config.warpFactor == 4) {
            chips.add(getString(R.string.cg_chip_warp2));
        }
        if (config.vsyncMode == PreferenceConfiguration.VSYNC_FAST) {
            chips.add(getString(R.string.cg_chip_fast_vsync));
        }
        else if (config.vsyncMode == PreferenceConfiguration.VSYNC_ON) {
            chips.add(getString(R.string.cg_chip_vsync));
        }
        if (config.enableHdr) {
            chips.add(getString(R.string.cg_chip_hdr));
        }
        if (config.audioHapticsOutputs != 0) {
            chips.add(getString(R.string.cg_chip_haptics));
        }
        if (!AppPreferences.getAppSettings(this, appKey).useGlobalSettings) {
            chips.add(getString(R.string.cg_chip_custom));
        }

        for (String chip : chips) {
            TextView view = (TextView) getLayoutInflater().inflate(R.layout.cg_hero_chip, heroChips, false);
            view.setText(chip);
            heroChips.addView(view);
        }
    }

    private void playSelectedApp() {
        if (selectedApp == null || computer == null || managerBinder == null) {
            return;
        }
        final AppObject app = selectedApp;
        if (lastRunningAppId != 0 && lastRunningAppId != app.app.getAppId()) {
            // Another game is running on the PC: same as Quit and start in the long-press menu
            UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                @Override
                public void run() {
                    startApp(app);
                }
            }, null);
        }
        else {
            startApp(app);
        }
    }

    private void startApp(AppObject app) {
        rememberLastPlayed(app);
        ServerHelper.doStart(AppView.this, app.app, computer, managerBinder);
    }

    private void rememberLastPlayed(AppObject app) {
        getSharedPreferences(COVER_GLOW_PREF_FILENAME, MODE_PRIVATE).edit()
                .putInt("last_" + uuidString, app.app.getAppId())
                .apply();
    }

    private void openAppSettings(AppObject app) {
        Intent settingsIntent = new Intent(AppView.this, com.limelight.preferences.AppStreamSettings.class);
        settingsIntent.putExtra(com.limelight.preferences.AppStreamSettings.EXTRA_APP_KEY, computer.uuid + ":" + app.app.getAppId());
        settingsIntent.putExtra(com.limelight.preferences.AppStreamSettings.EXTRA_APP_NAME, app.app.getAppName());
        startActivityForResult(settingsIntent, REQUEST_APP_SETTINGS);
    }

    // Fades the background and glow to a new cover's colour
    private void animateTint(int tint) {
        if (tint == currentTint) {
            return;
        }
        if (tintAnimator != null) {
            tintAnimator.cancel();
        }
        tintAnimator = ValueAnimator.ofArgb(currentTint, tint);
        tintAnimator.setDuration(600);
        tintAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                applyTint((Integer) animation.getAnimatedValue());
            }
        });
        tintAnimator.start();
    }

    private void applyTint(int tint) {
        currentTint = tint;
        appRoot.setBackgroundColor(CoverGlow.deep(tint));
        coverGlow.setBackgroundTintList(ColorStateList.valueOf(tint));
        getWindow().setNavigationBarColor(CoverGlow.deep(tint));
        getWindow().setStatusBarColor(CoverGlow.deep(tint));
    }

    private void addToQuickLaunch(ComputerDetails computer, NvApp app) {
        quickLaunchManager.addQuickLaunchItem(computer, app);
        Toast.makeText(this, getResources().getString(R.string.quick_launch_added), Toast.LENGTH_SHORT).show();
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
