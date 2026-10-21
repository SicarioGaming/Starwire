package com.limelight.ui.overlay;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.CoverGlow;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-stream menu, as a Cover Glow side sheet: the game's name and stream on top, a grid of
 * tools, then Close, Quit and Disconnect at the bottom. The sheet takes the game's cover colour.
 *
 * Controllers and keyboards move the selection with the d-pad or stick, A activates and B goes
 * back; X, Y, Start and RB stay as shortcuts.
 */
public class OverlayMenuView extends LinearLayout {
    private static final int BUTTON_SPACING_DP = 8;
    private static final int SHEET_WIDTH_DP = 440;
    // At most five across, so the built-in tools fit on a tablet without scrolling
    private static final int MAIN_COLUMNS = 5;
    // Fewer, wider tiles where five would break a label mid-word (a narrow screen, large text),
    // down to this many
    private static final int MIN_COLUMNS = 2;
    // Shortcut and monitor names are longer, so their pages use wider tiles
    private static final int PAGE_COLUMNS = 2;
    // How far the grid fades out where it has more to scroll to. A small handheld's grid still
    // has room for a whole row between a fade at each end.
    private static final int GRID_FADE_DP = 32;
    private static final float ANALOG_STICK_THRESHOLD = 0.5f;
    private static final long ANALOG_NAV_THROTTLE_MS = 200;

    public interface MenuActionListener {
        void onDisconnect();
        void onQuitSession();
        void onToggleStats();
        void onToggleMouseEmulation();
        void onShowKeyboard();
        void onSendGuideButton();
        void onShowBrightness();
        void onCustomCommand(CustomCommand command);
        void onMenuClosed();

        // Actions ported from Artemide's in-stream menu
        void onSendKeys(short[] keys);
        void onRotateScreen();
        void onToggleOnscreenController();
        void onToggleFloatingButton();
        void onTogglePanZoom();
        void onSelectMouseMode();
        void onToggleTouchSensitivity();
        void onUploadClipboard();
        void onFetchClipboard();
        void onShowServerCommands();
        void onToggleFullKeyboard();
        void onChangeBitrate();
        // Sends the PC cursor toggle once, for a cursor that ended up the wrong way round
        void onFixHostCursor();

        // Whether a toggle tile (TOGGLE_*) is on, for its switch
        boolean isToggleOn(int toggle);
    }

    // The tiles that switch something on and off; they show a small switch and keep the menu open
    public static final int TOGGLE_NONE = 0;
    public static final int TOGGLE_STATS = 1;
    public static final int TOGGLE_MOUSE_EMULATION = 2;
    public static final int TOGGLE_FULL_KEYBOARD = 3;
    public static final int TOGGLE_PAN_ZOOM = 4;
    public static final int TOGGLE_TOUCH_SENSITIVITY = 5;
    public static final int TOGGLE_ONSCREEN_CONTROLLER = 6;
    public static final int TOGGLE_FLOATING_BUTTON = 7;

    // Windows virtual key codes for Artemide's built-in shortcuts
    public static final short VK_TAB = 0x09;
    public static final short VK_RETURN = 0x0D;
    public static final short VK_ESCAPE = 0x1B;
    public static final short VK_LEFT = 0x25;
    public static final short VK_B = 0x42;
    public static final short VK_D = 0x44;
    public static final short VK_G = 0x47;
    public static final short VK_V = 0x56;
    public static final short VK_LWIN = 0x5B;
    public static final short VK_F1 = 0x70;
    public static final short VK_F4 = 0x73;
    public static final short VK_F11 = 0x7A;
    public static final short VK_F12 = 0x7B;
    public static final short VK_LSHIFT = 0xA0;
    public static final short VK_LCONTROL = 0xA2;
    public static final short VK_LMENU = 0xA4; // Left Alt

    private static class Shortcut {
        final int labelResId;
        final short[] keys;

        Shortcut(int labelResId, short... keys) {
            this.labelResId = labelResId;
            this.keys = keys;
        }
    }

    // Same set and order as Artemide's "Send keys" menu
    private static final Shortcut[] SHORTCUTS = {
        new Shortcut(R.string.overlay_menu_send_esc, VK_ESCAPE),
        new Shortcut(R.string.overlay_menu_send_f11, VK_F11),
        new Shortcut(R.string.overlay_menu_send_alt_f4, VK_LMENU, VK_F4),
        new Shortcut(R.string.overlay_menu_send_alt_enter, VK_LMENU, VK_RETURN),
        new Shortcut(R.string.overlay_menu_send_ctrl_v, VK_LCONTROL, VK_V),
        new Shortcut(R.string.overlay_menu_send_win, VK_LWIN),
        new Shortcut(R.string.overlay_menu_send_win_d, VK_LWIN, VK_D),
        new Shortcut(R.string.overlay_menu_send_win_g, VK_LWIN, VK_G),
        new Shortcut(R.string.overlay_menu_send_ctrl_alt_tab, VK_LCONTROL, VK_LMENU, VK_TAB),
        new Shortcut(R.string.overlay_menu_send_shift_tab, VK_LSHIFT, VK_TAB),
        new Shortcut(R.string.overlay_menu_send_win_shift_left, VK_LWIN, VK_LSHIFT, VK_LEFT),
        new Shortcut(R.string.overlay_menu_send_ctrl_alt_shift_f1, VK_LCONTROL, VK_LMENU, VK_LSHIFT, VK_F1),
        new Shortcut(R.string.overlay_menu_send_ctrl_alt_shift_f12, VK_LCONTROL, VK_LMENU, VK_LSHIFT, VK_F12),
        new Shortcut(R.string.overlay_menu_send_win_alt_b, VK_LWIN, VK_LMENU, VK_B),
    };

    private static final short[] TASK_MANAGER_KEYS = {VK_LCONTROL, VK_LSHIFT, VK_ESCAPE};

    // Which tools the grid shows: the main actions, or a sub-page (Shortcuts, Monitors)
    private static final int PAGE_MAIN = 0;
    private static final int PAGE_SHORTCUTS = 1;
    private static final int PAGE_MONITORS = 2;
    private int page = PAGE_MAIN;

    // Vibepollo (like Sunshine) switches the streamed display on Ctrl+Alt+Shift+F1..F13
    private static final int MONITOR_COUNT = 6;

    private GradientDrawable sheetBackground;
    private ImageView coverSwatch;
    private TextView titleView;
    private TextView detailView;
    private ScrollView gridScroll;
    private LinearLayout gridContainer;
    private LinearLayout barContainer;
    private int columns = MAIN_COLUMNS;

    // The bar: Close (index 0) on its own row, then Quit and Disconnect side by side; or all
    // three in one row on short screens
    private static final int COMPACT_HEIGHT_DP = 480;
    private boolean compactBar;
    private List<OverlayMenuButton> barButtons;
    private List<Integer> barActions;
    private List<OverlayMenuButton> gridButtons;
    private List<String> gridLabels;
    private List<Integer> gridActions;
    private List<Integer> gridToggles;

    private enum Region { BAR, GRID }
    private Region activeRegion = Region.BAR;
    private int barIndex = 0;
    private int gridIndex = 0;

    private MenuActionListener actionListener;
    private CustomCommandsManager commandsManager;
    private boolean fixHostCursorShown;
    private List<ImportedShortcuts.Shortcut> importedShortcuts = new ArrayList<>();

    private static final int ACTION_DISCONNECT = 0;
    private static final int ACTION_QUIT = 1;
    private static final int ACTION_TOGGLE_STATS = 2;
    private static final int ACTION_CLOSE = 3;
    private static final int ACTION_SHOW_KEYBOARD = 4;
    private static final int ACTION_TOGGLE_MOUSE_EMULATION = 5;
    private static final int ACTION_SEND_GUIDE = 6;
    private static final int ACTION_SHORTCUTS = 7;
    private static final int ACTION_BACK = 8;
    private static final int ACTION_TASK_MANAGER = 9;
    private static final int ACTION_ROTATE_SCREEN = 10;
    private static final int ACTION_ONSCREEN_CONTROLLER = 11;
    private static final int ACTION_FLOATING_BUTTON = 12;
    private static final int ACTION_PAN_ZOOM = 13;
    private static final int ACTION_MOUSE_MODE = 14;
    private static final int ACTION_TOUCH_SENSITIVITY = 15;
    private static final int ACTION_UPLOAD_CLIPBOARD = 16;
    private static final int ACTION_FETCH_CLIPBOARD = 17;
    private static final int ACTION_SERVER_COMMANDS = 18;
    private static final int ACTION_FULL_KEYBOARD = 19;
    private static final int ACTION_MONITORS = 20;
    private static final int ACTION_BITRATE = 21;
    private static final int ACTION_FIX_HOST_CURSOR = 22;
    private static final int ACTION_BRIGHTNESS = 23;
    private static final int ACTION_IMPORTED_BASE = 400;
    private static final int ACTION_MONITOR_BASE = 300;
    private static final int ACTION_CUSTOM_BASE = 100;
    private static final int ACTION_SHORTCUT_BASE = 200;

    private long lastAnalogNavTime = 0;
    private boolean flipFaceButtons = false;
    // Not on TVs, whose screen brightness the app can't change
    private boolean showBrightness = true;

    public OverlayMenuView(Context context) {
        super(context);
        init(context);
    }

    public OverlayMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OverlayMenuView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void init(Context context) {
        setOrientation(LinearLayout.VERTICAL);
        setFocusable(true);
        setFocusableInTouchMode(true);
        // Taps on the sheet itself shouldn't reach the game behind it
        setClickable(true);
        int padding = dp(20);
        setPadding(padding, padding, padding, padding);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }

        sheetBackground = new GradientDrawable();
        sheetBackground.setCornerRadius(dp(28));
        setBackground(sheetBackground);
        setElevation(dp(12));

        // Header: a swatch of the cover's colour, the game's name and the stream
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // The game's cover (or a swatch of its colour until one is set), with rounded corners
        coverSwatch = new ImageView(context);
        coverSwatch.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverSwatch.setClipToOutline(true);
        // The game's name sits right beside it, so TalkBack skips the picture
        coverSwatch.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(coverSwatch, new LinearLayout.LayoutParams(dp(42), dp(56)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(context);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(R.string.overlay_menu_title);
        texts.addView(titleView);
        detailView = new TextView(context);
        detailView.setTextColor(0xFFE2E8F0);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        detailView.setVisibility(GONE);
        texts.addView(detailView);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        textParams.setMarginStart(dp(14));
        header.addView(texts, textParams);
        addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // The tools grid scrolls when it doesn't fit (on a phone, a Fold's cover screen, or with
        // custom commands). It fades out at an edge with more tools beyond, so the cut-off row
        // reads as more to scroll to.
        gridScroll = new ScrollView(context);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setVerticalFadingEdgeEnabled(true);
        gridScroll.setFadingEdgeLength(dp(GRID_FADE_DP));
        gridScroll.setFocusable(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            gridScroll.setDefaultFocusHighlightEnabled(false);
        }
        gridContainer = new LinearLayout(context);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridScroll.addView(gridContainer);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1);
        gridParams.topMargin = dp(18);
        gridParams.bottomMargin = dp(16);
        addView(gridScroll, gridParams);

        barContainer = new LinearLayout(context);
        barContainer.setOrientation(LinearLayout.VERTICAL);
        addView(barContainer, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        barButtons = new ArrayList<>();
        barActions = new ArrayList<>();
        gridButtons = new ArrayList<>();
        gridLabels = new ArrayList<>();
        gridActions = new ArrayList<>();
        gridToggles = new ArrayList<>();

        activeRegion = Region.BAR;
        barIndex = 0;
        gridIndex = 0;

        commandsManager = new CustomCommandsManager(context);

        setGameInfo(null, null, CoverGlow.DEFAULT_TINT);
        setVisibility(GONE);
    }

    /** The game's name, a line about the stream, and its cover colour for the sheet. */
    public void setGameInfo(String title, String detail, int tint) {
        if (title != null) {
            titleView.setText(title);
        }
        detailView.setText(detail);
        detailView.setVisibility(detail != null && !detail.isEmpty() ? VISIBLE : GONE);

        sheetBackground.setColor(CoverGlow.panel(tint));
        GradientDrawable swatch = new GradientDrawable();
        swatch.setCornerRadius(dp(10));
        swatch.setColor(tint);
        coverSwatch.setBackground(swatch);
    }

    /** The game's cover for the header; null keeps the plain colour swatch. */
    public void setCover(Drawable cover) {
        coverSwatch.setImageDrawable(cover);
    }

    /** Whether the main page offers Fix PC Cursor; it shows from the next time the menu opens. */
    public void setFixHostCursorShown(boolean shown) {
        fixHostCursorShown = shown;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // A sheet, not the whole screen: at most SHEET_WIDTH_DP wide
        int max = dp(SHEET_WIDTH_DP);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                || MeasureSpec.getSize(widthMeasureSpec) > max) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void buildMenu() {
        activeRegion = Region.BAR;
        barIndex = 0;
        gridIndex = 0;
        clearFocus();

        gridContainer.removeAllViews();
        barContainer.removeAllViews();
        barButtons.clear();
        barActions.clear();
        gridButtons.clear();
        gridLabels.clear();
        gridActions.clear();
        gridToggles.clear();

        if (page == PAGE_SHORTCUTS) {
            // Artemide's built-in key shortcuts then Back
            columns = PAGE_COLUMNS;
            for (int i = 0; i < SHORTCUTS.length; i++) {
                addTile(0, getContext().getString(SHORTCUTS[i].labelResId), ACTION_SHORTCUT_BASE + i);
            }
            // Then any shortcuts imported from a file (Settings > In-stream menu)
            importedShortcuts = ImportedShortcuts.load(getContext());
            for (int i = 0; i < importedShortcuts.size(); i++) {
                addTile(0, importedShortcuts.get(i).name, ACTION_IMPORTED_BASE + i);
            }
            addTile(0, getContext().getString(R.string.overlay_menu_back), ACTION_BACK);
        } else if (page == PAGE_MONITORS) {
            // One tile per PC display, then Back
            columns = PAGE_COLUMNS;
            for (int i = 0; i < MONITOR_COUNT; i++) {
                addTile(R.drawable.ic_overlay_monitor,
                    getContext().getString(R.string.overlay_menu_monitor_n, i + 1), ACTION_MONITOR_BASE + i);
            }
            addTile(0, getContext().getString(R.string.overlay_menu_back), ACTION_BACK);
        } else {
            // The most used first: both keyboards, mouse, stats and the Guide button, then
            // Artemide's actions and custom commands
            addTile(R.drawable.ic_overlay_keyboard_toggle,
                getContext().getString(R.string.overlay_menu_keyboard), ACTION_SHOW_KEYBOARD);
            addTile(R.drawable.ic_overlay_keyboard,
                getContext().getString(R.string.overlay_menu_full_keyboard), ACTION_FULL_KEYBOARD, TOGGLE_FULL_KEYBOARD);
            addTile(R.drawable.ic_overlay_mouse,
                getContext().getString(R.string.overlay_menu_mouse_emulation), ACTION_TOGGLE_MOUSE_EMULATION, TOGGLE_MOUSE_EMULATION);
            addTile(R.drawable.ic_overlay_perf,
                getContext().getString(R.string.overlay_menu_toggle_stats), ACTION_TOGGLE_STATS, TOGGLE_STATS);
            addTile(R.drawable.ic_overlay_guide,
                getContext().getString(R.string.overlay_menu_guide), ACTION_SEND_GUIDE);
            addTile(R.drawable.ic_overlay_key_press,
                getContext().getString(R.string.overlay_menu_shortcuts), ACTION_SHORTCUTS);
            addTile(R.drawable.ic_overlay_monitor,
                getContext().getString(R.string.overlay_menu_monitors), ACTION_MONITORS);
            addTile(R.drawable.ic_overlay_lightning,
                getContext().getString(R.string.overlay_menu_bitrate), ACTION_BITRATE);
            if (showBrightness) {
                addTile(R.drawable.ic_overlay_settings_brightness,
                    getContext().getString(R.string.overlay_menu_brightness), ACTION_BRIGHTNESS);
            }
            addTile(R.drawable.ic_overlay_mouse,
                getContext().getString(R.string.overlay_menu_mouse_mode), ACTION_MOUSE_MODE);
            if (fixHostCursorShown) {
                addTile(R.drawable.ic_overlay_mouse,
                    getContext().getString(R.string.overlay_menu_fix_host_cursor), ACTION_FIX_HOST_CURSOR);
            }
            addTile(R.drawable.ic_overlay_maximize,
                getContext().getString(R.string.overlay_menu_pan_zoom), ACTION_PAN_ZOOM, TOGGLE_PAN_ZOOM);
            addTile(R.drawable.ic_overlay_settings,
                getContext().getString(R.string.overlay_menu_touch_sensitivity), ACTION_TOUCH_SENSITIVITY, TOGGLE_TOUCH_SENSITIVITY);
            addTile(R.drawable.ic_overlay_save,
                getContext().getString(R.string.overlay_menu_upload_clipboard), ACTION_UPLOAD_CLIPBOARD);
            addTile(R.drawable.ic_overlay_folder,
                getContext().getString(R.string.overlay_menu_fetch_clipboard), ACTION_FETCH_CLIPBOARD);
            addTile(R.drawable.ic_overlay_desktop,
                getContext().getString(R.string.overlay_menu_server_commands), ACTION_SERVER_COMMANDS);
            addTile(R.drawable.ic_overlay_task_manager,
                getContext().getString(R.string.overlay_menu_task_manager), ACTION_TASK_MANAGER);
            addTile(R.drawable.ic_overlay_restart,
                getContext().getString(R.string.overlay_menu_rotate_screen), ACTION_ROTATE_SCREEN);
            addTile(R.drawable.ic_overlay_gamepad,
                getContext().getString(R.string.overlay_menu_onscreen_controller), ACTION_ONSCREEN_CONTROLLER, TOGGLE_ONSCREEN_CONTROLLER);
            addTile(R.drawable.ic_overlay_window_menu,
                getContext().getString(R.string.overlay_menu_floating_button), ACTION_FLOATING_BUTTON, TOGGLE_FLOATING_BUTTON);

            List<CustomCommand> customCommands = commandsManager.getCommands();
            for (int i = 0; i < customCommands.size(); i++) {
                CustomCommand command = customCommands.get(i);
                addTile(command.getIconResId(), command.getName(), ACTION_CUSTOM_BASE + i);
            }
            columns = mainColumns();
        }
        arrangeGrid();

        // The bar: back to the game, then the two ways out. On short screens (a phone held
        // sideways for a stream) all three share one row, leaving room for more tools.
        compactBar = getResources().getConfiguration().screenHeightDp < COMPACT_HEIGHT_DP;
        if (compactBar) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            barContainer.addView(row, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            addBarButton(OverlayMenuButton.STYLE_PRIMARY, 0,
                getContext().getString(R.string.overlay_menu_close), ACTION_CLOSE, row, dp(BUTTON_SPACING_DP));
            // Without icons, so the full words fit a third of the row
            addBarButton(OverlayMenuButton.STYLE_PILL, 0,
                getContext().getString(R.string.overlay_menu_quit_session), ACTION_QUIT, row, dp(BUTTON_SPACING_DP));
            addBarButton(OverlayMenuButton.STYLE_PILL, 0,
                getContext().getString(R.string.overlay_menu_disconnect), ACTION_DISCONNECT, row, 0);
            gridContainer.requestLayout();
            barContainer.requestLayout();
            return;
        }
        addBarButton(OverlayMenuButton.STYLE_PRIMARY, 0,
            getContext().getString(R.string.overlay_menu_close), ACTION_CLOSE, barContainer, 0);
        LinearLayout exits = new LinearLayout(getContext());
        exits.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams exitsParams = new LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        exitsParams.topMargin = dp(BUTTON_SPACING_DP);
        barContainer.addView(exits, exitsParams);
        addBarButton(OverlayMenuButton.STYLE_PILL, R.drawable.ic_overlay_power,
            getContext().getString(R.string.overlay_menu_quit_session), ACTION_QUIT, exits, dp(BUTTON_SPACING_DP));
        addBarButton(OverlayMenuButton.STYLE_PILL, R.drawable.ic_overlay_monitor,
            getContext().getString(R.string.overlay_menu_disconnect), ACTION_DISCONNECT, exits, 0);

        gridContainer.requestLayout();
        barContainer.requestLayout();
    }

    private void addTile(int iconResId, String label, int action) {
        addTile(iconResId, label, action, TOGGLE_NONE);
    }

    // Makes the tile; arrangeGrid puts the tiles into rows once they're all made
    private void addTile(int iconResId, String label, int action, int toggle) {
        OverlayMenuButton button = OverlayMenuButton.create(getContext(), iconResId, label,
            OverlayMenuButton.STYLE_TILE);

        gridButtons.add(button);
        gridLabels.add(label != null ? label : "");
        gridActions.add(action);
        gridToggles.add(toggle);
        if (toggle != TOGGLE_NONE) {
            button.setToggle(actionListener != null && actionListener.isToggleOn(toggle));
        }

        final int index = gridButtons.size() - 1;
        button.setOnClickListener(v -> selectAndActivate(Region.GRID, index));
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                activeRegion = Region.GRID;
                gridIndex = index;
            }
            button.setSelected(hasFocus);
        });
    }

    // Five across where every label fits whole, else fewer. The sheet's width comes from the
    // screen, as in onMeasure, so it's known before the sheet is first laid out.
    private int mainColumns() {
        int margins = 0;
        if (getLayoutParams() instanceof MarginLayoutParams) {
            MarginLayoutParams params = (MarginLayoutParams) getLayoutParams();
            margins = params.leftMargin + params.rightMargin;
        }
        float density = getResources().getDisplayMetrics().density;
        int screen = (int) (getResources().getConfiguration().screenWidthDp * density);
        int sheet = Math.min(dp(SHEET_WIDTH_DP), screen - margins);
        Paint paint = OverlayMenuButton.tileLabelPaint(getContext());
        return TileColumns.fit(gridLabels, paint::measureText, sheet - getPaddingLeft() - getPaddingRight(),
            dp(BUTTON_SPACING_DP), dp(OverlayMenuButton.TILE_SIDE_PADDING_DP),
            OverlayMenuButton.TILE_LABEL_LINES, MAIN_COLUMNS, MIN_COLUMNS);
    }

    // Puts the tiles into rows of `columns`, and keeps the last row's tiles the same width as
    // the others
    private void arrangeGrid() {
        LinearLayout row = null;
        for (OverlayMenuButton button : gridButtons) {
            if (row == null || row.getChildCount() >= columns) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                if (gridContainer.getChildCount() > 0) {
                    rowParams.topMargin = dp(BUTTON_SPACING_DP);
                }
                gridContainer.addView(row, rowParams);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
            if (row.getChildCount() > 0) {
                params.setMarginStart(dp(BUTTON_SPACING_DP));
            }
            row.addView(button, params);
        }
        if (row != null) {
            while (row.getChildCount() < columns) {
                // MATCH_PARENT like the tiles: a fixed-height filler would make the row measure
                // itself from the fillers and collapse to nothing
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
                params.setMarginStart(dp(BUTTON_SPACING_DP));
                row.addView(new Space(getContext()), params);
            }
        }
    }

    private void addBarButton(int style, int iconResId, String label, int action,
                              LinearLayout parent, int endMarginPx) {
        OverlayMenuButton button = OverlayMenuButton.create(getContext(), iconResId, label, style);
        LinearLayout.LayoutParams params = parent == barContainer
            ? new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            : new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        params.setMarginEnd(endMarginPx);
        parent.addView(button, params);

        barButtons.add(button);
        barActions.add(action);

        final int index = barButtons.size() - 1;
        button.setOnClickListener(v -> selectAndActivate(Region.BAR, index));
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                activeRegion = Region.BAR;
                barIndex = index;
            }
            button.setSelected(hasFocus);
        });
    }

    public void setMenuActionListener(MenuActionListener listener) {
        this.actionListener = listener;
    }

    public void setFlipFaceButtons(boolean flip) {
        this.flipFaceButtons = flip;
    }

    public void setShowBrightness(boolean show) {
        this.showBrightness = show;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!isFullGamepadEvent(event) && event.getRepeatCount() == 0) {
                closeOrGoBack();
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (flipFaceButtons) {
                keyCode = handleFlipFaceButtons(keyCode);
            }

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    navigateLeft();
                    return true;

                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    navigateRight();
                    return true;

                case KeyEvent.KEYCODE_DPAD_UP:
                    navigateUp();
                    return true;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    navigateDown();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    activateSelected();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_B:
                    closeOrGoBack();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_X:
                    if (event.getRepeatCount() == 0) {
                        activateMouseEmulation();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_Y:
                    if (event.getRepeatCount() == 0) {
                        activateKeyboard();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_START:
                case KeyEvent.KEYCODE_MENU:
                    if (event.getRepeatCount() == 0) {
                        activateGuideButton();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_R1:
                    if (event.getRepeatCount() == 0) {
                        activateToggleStats();
                    }
                    return true;
            }
        }

        if (isGamepadEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!isFullGamepadEvent(event) && event.getRepeatCount() == 0) {
                closeOrGoBack();
            }
            return true;
        }

        if (isGamepadEvent(event)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isGamepadMotionEvent(event)) {
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);

            float combinedX = Math.abs(x) > Math.abs(hatX) ? x : hatX;
            float combinedY = Math.abs(y) > Math.abs(hatY) ? y : hatY;

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAnalogNavTime >= ANALOG_NAV_THROTTLE_MS) {
                // Dominant axis wins to prevent cross-region jumps on diagonal inputs
                if (Math.abs(combinedX) >= Math.abs(combinedY)) {
                    if (combinedX < -ANALOG_STICK_THRESHOLD) {
                        navigateLeft();
                        lastAnalogNavTime = currentTime;
                    } else if (combinedX > ANALOG_STICK_THRESHOLD) {
                        navigateRight();
                        lastAnalogNavTime = currentTime;
                    }
                } else {
                    if (combinedY < -ANALOG_STICK_THRESHOLD) {
                        navigateUp();
                        lastAnalogNavTime = currentTime;
                    } else if (combinedY > ANALOG_STICK_THRESHOLD) {
                        navigateDown();
                        lastAnalogNavTime = currentTime;
                    }
                }
            }

            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean isGamepadEvent(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
               (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private boolean isFullGamepadEvent(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private int handleFlipFaceButtons(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_B: return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_X: return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_Y: return KeyEvent.KEYCODE_BUTTON_X;
            default: return keyCode;
        }
    }

    private boolean isGamepadMotionEvent(MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }
    // The grid moves by rows and columns; the bar is Close above Quit and Disconnect
    private void navigateUp() {
        if (activeRegion == Region.GRID) {
            if (gridIndex >= columns) {
                setGridIndex(gridIndex - columns);
            }
        } else if (barIndex > 0 && !compactBar) {
            setBarIndex(0);
        } else if (!gridButtons.isEmpty()) {
            // From Close up into the last tile
            clearBarSelection();
            activeRegion = Region.GRID;
            setGridIndex(gridButtons.size() - 1);
        }
    }

    private void navigateDown() {
        if (activeRegion == Region.GRID) {
            int lastRow = (gridButtons.size() - 1) / columns;
            if (gridIndex / columns < lastRow) {
                setGridIndex(Math.min(gridIndex + columns, gridButtons.size() - 1));
            } else {
                clearGridSelection();
                activeRegion = Region.BAR;
                setBarIndex(0);
            }
        } else if (!compactBar && barIndex == 0 && barButtons.size() > 1) {
            setBarIndex(1);
        }
    }

    private void navigateLeft() {
        if (activeRegion == Region.GRID) {
            if (gridIndex % columns > 0) {
                setGridIndex(gridIndex - 1);
            }
        } else if (compactBar ? barIndex > 0 : barIndex == 2) {
            setBarIndex(barIndex - 1);
        }
    }

    private void navigateRight() {
        if (activeRegion == Region.GRID) {
            if (gridIndex % columns < columns - 1 && gridIndex + 1 < gridButtons.size()) {
                setGridIndex(gridIndex + 1);
            }
        } else if (compactBar ? barIndex < barButtons.size() - 1 : (barIndex == 1 && barButtons.size() > 2)) {
            setBarIndex(barIndex + 1);
        }
    }

    private void setBarIndex(int index) {
        if (index < 0 || index >= barButtons.size()) return;

        for (OverlayMenuButton b : barButtons) {
            b.setSelected(false);
        }

        barIndex = index;
        barButtons.get(index).setSelected(true);
        barButtons.get(index).requestFocus();
    }

    private void setGridIndex(int index) {
        if (index < 0 || index >= gridButtons.size()) return;

        for (OverlayMenuButton b : gridButtons) {
            b.setSelected(false);
        }

        gridIndex = index;
        OverlayMenuButton tile = gridButtons.get(index);
        tile.setSelected(true);
        tile.requestFocus();
        // Focus doesn't scroll in touch mode (after the screen was touched), so bring the tile's
        // row into view explicitly
        View row = (View) tile.getParent();
        int scrollY = scrollToShow(row.getTop(), row.getBottom(), gridScroll.getScrollY(),
            gridScroll.getHeight(), gridContainer.getHeight(), gridScroll.getVerticalFadingEdgeLength());
        if (scrollY != gridScroll.getScrollY()) {
            gridScroll.smoothScrollTo(0, scrollY);
        }
    }

    // Where the grid scrolls to show the row from top to bottom: clear of the fade at an edge
    // with more tools beyond, as ScrollView keeps a focused view clear of it. scrollY when the
    // row already shows that way.
    static int scrollToShow(int top, int bottom, int scrollY, int height, int contentHeight, int fade) {
        if (top > 0) {
            top -= fade;
        }
        if (bottom < contentHeight) {
            bottom += fade;
        }
        int target = scrollY;
        if (bottom > scrollY + height) {
            target = bottom - height;
        }
        else if (top < scrollY) {
            target = top;
        }
        return Math.max(0, Math.min(target, contentHeight - height));
    }

    private void clearBarSelection() {
        for (OverlayMenuButton b : barButtons) {
            b.setSelected(false);
        }
    }

    private void clearGridSelection() {
        for (OverlayMenuButton b : gridButtons) {
            b.setSelected(false);
        }
    }

    private void selectAndActivate(Region region, int index) {
        activeRegion = region;
        if (region == Region.BAR) {
            setBarIndex(index);
        } else {
            setGridIndex(index);
        }
        activateSelected();
    }

    private void activateSelected() {
        int action;
        if (activeRegion == Region.BAR) {
            if (barIndex < 0 || barIndex >= barActions.size()) return;
            action = barActions.get(barIndex);
        } else {
            if (gridIndex < 0 || gridIndex >= gridActions.size()) return;
            action = gridActions.get(gridIndex);
        }

        boolean shouldCloseMenu = false;

        if (actionListener != null) {
            if (action == ACTION_DISCONNECT) {
                actionListener.onDisconnect();
                shouldCloseMenu = true;
            } else if (action == ACTION_QUIT) {
                actionListener.onQuitSession();
                shouldCloseMenu = true;
            } else if (action == ACTION_TOGGLE_STATS) {
                actionListener.onToggleStats();
            } else if (action == ACTION_TOGGLE_MOUSE_EMULATION) {
                actionListener.onToggleMouseEmulation();
            } else if (action == ACTION_SHOW_KEYBOARD) {
                activateKeyboard();
                return;
            } else if (action == ACTION_SEND_GUIDE) {
                actionListener.onSendGuideButton();
                shouldCloseMenu = true;
            } else if (action == ACTION_BRIGHTNESS) {
                // Closed first, so the slider gets the controller and remote input
                closeMenu();
                actionListener.onShowBrightness();
                return;
            } else if (action == ACTION_CLOSE) {
                closeMenu();
                return;
            } else if (action == ACTION_SHORTCUTS) {
                switchPage(PAGE_SHORTCUTS);
                return;
            } else if (action == ACTION_MONITORS) {
                switchPage(PAGE_MONITORS);
                return;
            } else if (action == ACTION_BACK) {
                switchPage(PAGE_MAIN);
                return;
            } else if (action == ACTION_FIX_HOST_CURSOR) {
                actionListener.onFixHostCursor();
                shouldCloseMenu = true;
            } else if (action == ACTION_BITRATE) {
                // The bitrate picker is a dialog; close the menu behind it
                closeMenu();
                actionListener.onChangeBitrate();
                return;
            } else if (action >= ACTION_IMPORTED_BASE) {
                int index = action - ACTION_IMPORTED_BASE;
                if (index < importedShortcuts.size()) {
                    actionListener.onSendKeys(importedShortcuts.get(index).keys);
                }
                shouldCloseMenu = true;
            } else if (action >= ACTION_MONITOR_BASE) {
                int monitor = action - ACTION_MONITOR_BASE;
                actionListener.onSendKeys(new short[]{VK_LCONTROL, VK_LMENU, VK_LSHIFT, (short) (VK_F1 + monitor)});
                shouldCloseMenu = true;
            } else if (action == ACTION_TASK_MANAGER) {
                actionListener.onSendKeys(TASK_MANAGER_KEYS);
                shouldCloseMenu = true;
            } else if (action == ACTION_ROTATE_SCREEN) {
                actionListener.onRotateScreen();
                shouldCloseMenu = true;
            } else if (action == ACTION_ONSCREEN_CONTROLLER) {
                actionListener.onToggleOnscreenController();
            } else if (action == ACTION_FLOATING_BUTTON) {
                actionListener.onToggleFloatingButton();
            } else if (action == ACTION_PAN_ZOOM) {
                actionListener.onTogglePanZoom();
            } else if (action == ACTION_MOUSE_MODE) {
                // The picker dialog takes over; close the menu behind it
                closeMenu();
                actionListener.onSelectMouseMode();
                return;
            } else if (action == ACTION_TOUCH_SENSITIVITY) {
                actionListener.onToggleTouchSensitivity();
            } else if (action == ACTION_UPLOAD_CLIPBOARD) {
                actionListener.onUploadClipboard();
                shouldCloseMenu = true;
            } else if (action == ACTION_FETCH_CLIPBOARD) {
                actionListener.onFetchClipboard();
                shouldCloseMenu = true;
            } else if (action == ACTION_FULL_KEYBOARD) {
                actionListener.onToggleFullKeyboard();
            } else if (action == ACTION_SERVER_COMMANDS) {
                closeMenu();
                actionListener.onShowServerCommands();
                return;
            } else if (action >= ACTION_SHORTCUT_BASE) {
                int shortcutIndex = action - ACTION_SHORTCUT_BASE;
                if (shortcutIndex >= 0 && shortcutIndex < SHORTCUTS.length) {
                    actionListener.onSendKeys(SHORTCUTS[shortcutIndex].keys);
                }
                shouldCloseMenu = true;
            } else if (action >= ACTION_CUSTOM_BASE) {
                int commandIndex = action - ACTION_CUSTOM_BASE;
                List<CustomCommand> commands = commandsManager.getCommands();
                if (commandIndex >= 0 && commandIndex < commands.size()) {
                    actionListener.onCustomCommand(commands.get(commandIndex));
                }
                // Keep menu open
            }
        }

        if (shouldCloseMenu) {
            closeMenu();
        }
        else if (activeRegion == Region.GRID) {
            // A toggle stays open and its switch flips
            int toggle = gridToggles.get(gridIndex);
            if (toggle != TOGGLE_NONE && actionListener != null) {
                gridButtons.get(gridIndex).setToggle(actionListener.isToggleOn(toggle));
            }
        }
    }

    private void closeOrGoBack() {
        if (page != PAGE_MAIN) {
            switchPage(PAGE_MAIN);
        } else {
            closeMenu();
        }
    }

    public void closeMenu() {
        hide(() -> {
            if (actionListener != null) {
                actionListener.onMenuClosed();
            }
        });
    }

    private void activateMouseEmulation() {
        if (actionListener != null) {
            actionListener.onToggleMouseEmulation();
        }
        closeMenu();
    }

    private void activateKeyboard() {
        // Hide first, then post the keyboard request so the window manager has a frame
        // to process the visibility change and return focus to the game view before the
        // IME is requested. Without this, newer Android silently drops showSoftInput()
        // because the overlay view still holds focus at the moment of the call.
        setVisibility(GONE);
        post(() -> {
            if (actionListener != null) {
                actionListener.onShowKeyboard();
            }
        });
    }

    private void activateGuideButton() {
        if (actionListener != null) {
            actionListener.onSendGuideButton();
        }
        closeMenu();
    }

    private void activateQuitSession() {
        if (actionListener != null) {
            actionListener.onQuitSession();
        }
        closeMenu();
    }

    private void activateToggleStats() {
        if (actionListener != null) {
            actionListener.onToggleStats();
        }
        closeMenu();
    }

    /**
     * Swap the grid between the main actions and a sub-page (Shortcuts, Monitors),
     * keeping the menu open and the selection on the first tile of the new page.
     */
    private void switchPage(int newPage) {
        page = newPage;
        buildMenu();
        post(() -> {
            if (!gridButtons.isEmpty()) {
                activeRegion = Region.GRID;
                setGridIndex(0);
                gridScroll.scrollTo(0, 0);
            }
        });
    }

    public void show() {
        page = PAGE_MAIN;
        buildMenu();
        gridScroll.scrollTo(0, 0);
        setVisibility(VISIBLE);

        // Slides in from the right edge (system animation settings still apply)
        animate().cancel();
        setAlpha(0f);
        setTranslationX(dp(SHEET_WIDTH_DP) * 0.4f);
        animate().alpha(1f).translationX(0)
            .setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();

        requestFocus();

        // Close (back to the game) is selected first, so A just resumes
        post(() -> {
            activeRegion = Region.BAR;
            setBarIndex(0);
        });
    }

    public void hide(Runnable onComplete) {
        animate().cancel();
        setAlpha(1f);
        setTranslationX(0);
        setVisibility(GONE);
        if (onComplete != null) {
            onComplete.run();
        }
    }
}
