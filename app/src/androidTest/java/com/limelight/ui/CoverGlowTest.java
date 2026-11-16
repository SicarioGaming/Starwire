package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.R;
import com.limelight.preferences.StreamSettings;
import com.limelight.testing.TestFiles;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Cover Glow: the colour taken from a game's cover, and the home screen layout drawn at the
 * tablet's size (saved as a picture to look at, since the screen needs a paired PC).
 */
@RunWith(AndroidJUnit4.class)
public class CoverGlowTest {
    private static float hue(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }

    @Test
    public void aRedCoverTintsRed() {
        int[] pixels = new int[100];
        Arrays.fill(pixels, Color.rgb(200, 30, 40));
        float h = hue(CoverGlow.tintFromPixels(pixels));
        assertTrue("hue " + h, h < 10 || h > 350);
    }

    @Test
    public void theVividPartOfADarkCoverWins() {
        // Mostly near-black with a blue logo
        int[] pixels = new int[100];
        Arrays.fill(pixels, Color.rgb(12, 12, 14));
        for (int i = 0; i < 15; i++) {
            pixels[i] = Color.rgb(30, 90, 230);
        }
        float h = hue(CoverGlow.tintFromPixels(pixels));
        assertTrue("hue " + h, h > 210 && h < 235);
    }

    @Test
    public void aHardwareCoverTintsWithoutCrashing() {
        // Box art is decoded straight to GPU memory, where its pixels can't be read directly
        // (0.1.0 crashed on the first cover that loaded). GPU bitmaps start with Android 8.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O);
        Bitmap soft = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888);
        soft.eraseColor(Color.rgb(200, 30, 40));
        Bitmap cover = soft.copy(Bitmap.Config.HARDWARE, false);
        assertEquals(Bitmap.Config.HARDWARE, cover.getConfig());
        float h = hue(CoverGlow.tintFromBitmap(cover));
        assertTrue("hue " + h, h < 10 || h > 350);
    }

    @Test
    public void aGreyCoverKeepsTheDefault() {
        int[] pixels = new int[100];
        Arrays.fill(pixels, Color.rgb(128, 128, 128));
        assertEquals(CoverGlow.DEFAULT_TINT, CoverGlow.tintFromPixels(pixels));
    }

    @Test
    public void whiteTextStaysReadableOnTheBackground() {
        for (int tint : new int[] {CoverGlow.DEFAULT_TINT, Color.YELLOW, Color.WHITE, 0xFFA3E635}) {
            int deep = CoverGlow.deep(tint);
            double contrast = (1.05) / (luminance(deep) + 0.05);
            assertTrue("contrast " + contrast + " on " + Integer.toHexString(deep), contrast >= 7);
        }
    }

    private static double luminance(int color) {
        double[] c = {Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0};
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    @Test
    public void pcListRendersAtTabletSize() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.res.Configuration landscape = new android.content.res.Configuration(
                target.getResources().getConfiguration());
        landscape.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        Context context = new ContextThemeWrapper(target.createConfigurationContext(landscape), R.style.AppTheme);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LayoutInflater inflater = LayoutInflater.from(context);
            View root = inflater.inflate(R.layout.activity_pc_view, null);
            root.findViewById(R.id.no_pc_found_layout).setVisibility(View.GONE);

            ViewGroup container = root.findViewById(R.id.pcFragmentContainer);
            LinearLayout row = new LinearLayout(context);
            String[] names = {"GAMING-PC", "LAPTOP"};
            for (String name : names) {
                View item = inflater.inflate(R.layout.pc_grid_item, row, false);
                ((ImageView) item.findViewById(R.id.grid_image)).setImageResource(R.drawable.ic_computer);
                ((TextView) item.findViewById(R.id.grid_text)).setText(name);
                item.findViewById(R.id.grid_spinner).setVisibility(View.GONE);
                row.addView(item);
            }
            container.addView(row);

            save(root, target, "cover_glow_pcs.png");
        });
    }

    @Test
    public void settingsScreenHasSectionPills() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<StreamSettings> scenario = ActivityScenario.launch(StreamSettings.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                LinearLayout pills = activity.findViewById(R.id.settingsSections);
                assertTrue("only " + pills.getChildCount() + " sections", pills.getChildCount() >= 5);
                // Plain-language names in English; other languages keep their section headings
                // (the device matrix runs this in Hebrew too)
                @SuppressWarnings("deprecation")
                boolean english = "en".equals(activity.getResources().getConfiguration().locale.getLanguage());
                assertEquals(activity.getString(english ? R.string.cg_section_stream : R.string.category_basic_settings),
                        ((TextView) pills.getChildAt(0)).getText().toString());
                assertEquals(activity.getString(english ? R.string.cg_section_sound : R.string.category_audio_settings),
                        ((TextView) pills.getChildAt(1)).getText().toString());

                // The "Predicted impact" panel shows when the screen is wide enough for it (a
                // tablet held sideways), and its whole column goes on narrower ones (the tablet
                // held upright), so the settings keep their room
                if (activity.getResources().getConfiguration().screenWidthDp >= CoverGlowSettings.FEEL_PANEL_MIN_WIDTH_DP) {
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.feelPanel).getVisibility());
                    assertTrue(((TextView) activity.findViewById(R.id.feelText)).getText().length() > 0);
                }
                else {
                    assertEquals(View.GONE, activity.findViewById(R.id.feelColumn).getVisibility());
                }
                assertTrue(pills.getChildAt(0).isSelected());

                // Tapping a section moves the highlight to it
                pills.getChildAt(2).performClick();
                assertTrue(pills.getChildAt(2).isSelected());
                assertFalse(pills.getChildAt(0).isSelected());
                pills.getChildAt(0).performClick();

                save(activity.getWindow().getDecorView(), target, "cover_glow_settings.png");
            });
        }
    }

    @Test
    public void streamMenuRendersAtTabletSize() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context context = new ContextThemeWrapper(target, R.style.AppTheme);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // A stand-in for the game video behind the sheet
            FrameLayout root = new FrameLayout(context);
            root.setBackgroundColor(0xFF2A3340);

            com.limelight.ui.overlay.OverlayMenuView menu = new com.limelight.ui.overlay.OverlayMenuView(context);
            float d = context.getResources().getDisplayMetrics().density;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.END);
            params.setMargins((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (16 * d));
            root.addView(menu, params);
            menu.setGameInfo("Desktop", "1440p · 120 FPS", AppIcons.accentFor("Desktop"));
            menu.setCover(AppIcons.tile(context, "Desktop"));
            // Stats and the menu button switched on, to show both looks of a toggle
            menu.setMenuActionListener((com.limelight.ui.overlay.OverlayMenuView.MenuActionListener) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {com.limelight.ui.overlay.OverlayMenuView.MenuActionListener.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("isToggleOn")) {
                            int toggle = (Integer) args[0];
                            return toggle == com.limelight.ui.overlay.OverlayMenuView.TOGGLE_STATS
                                    || toggle == com.limelight.ui.overlay.OverlayMenuView.TOGGLE_FLOATING_BUTTON;
                        }
                        return null;
                    }));
            menu.buildMenu();
            menu.setVisibility(View.VISIBLE);
            // Close selected, as when the menu opens
            menu.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_UP));
            menu.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN));

            save(root, target, "cover_glow_menu.png");

            // A sheet beside the game, sized as OverlayMenuView.fitMainPage sizes it for this
            // device's screen: the usual 440 dp, five tiles across or fewer; or, where seven tiles
            // of the usual size fit in 60% of the screen's width (a tablet held sideways), just
            // wide enough for those seven
            ViewGroup row = (ViewGroup) ((ViewGroup) findFirst(menu, android.widget.ScrollView.class)
                    .getChildAt(0)).getChildAt(0);
            int across = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                if (row.getChildAt(i) instanceof com.limelight.ui.overlay.OverlayMenuButton) {
                    across++;
                }
            }
            int usual = (int) (440 * d);
            if (across == 7) {
                int padding = menu.getPaddingLeft() + menu.getPaddingRight();
                int gap = ((ViewGroup.MarginLayoutParams) row.getChildAt(1).getLayoutParams()).getMarginStart();
                int tile = (usual - padding - gap * 4) / 5;
                assertEquals("sheet seven tiles across", tile * 7 + gap * 6 + padding, menu.getWidth());
                int screen = (int) (context.getResources().getConfiguration().screenWidthDp * d);
                assertTrue("sheet " + menu.getWidth() + " px wide on a " + screen + " px screen",
                        menu.getWidth() <= screen * 0.6f);
            }
            else {
                assertTrue(across + " tiles across", across >= 1 && across <= 5);
                assertEquals("sheet " + across + " tiles across", usual, menu.getWidth());
            }
        });
    }

    @Test
    public void feelPanelPlacesTheChoicesInOrder() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SettingsFeel warp2 = SettingsFeel.describe(context, "warp2", "off", false);
        SettingsFeel latency = SettingsFeel.describe(context, "latency", "off", false);
        SettingsFeel balanced = SettingsFeel.describe(context, "balanced", "off", false);
        SettingsFeel smooth = SettingsFeel.describe(context, "smoothness", "off", false);
        assertTrue(warp2.position < latency.position);
        assertTrue(latency.position < balanced.position);
        assertTrue(balanced.position < smooth.position);
        assertEquals(SettingsFeel.LEVEL_HIGHER, warp2.battery);
        assertEquals(SettingsFeel.LEVEL_HIGHER, smooth.delay);

        // V-Sync moves towards smooth and adds delay; host frame timing ignores it
        SettingsFeel vsync = SettingsFeel.describe(context, "latency", "vsync", false);
        assertTrue(vsync.position > latency.position);
        assertEquals(SettingsFeel.LEVEL_MEDIUM, vsync.delay);
        assertEquals(SettingsFeel.EFFECT_SMOOTHER, vsync.factors.get(1).effect);
        SettingsFeel host = SettingsFeel.describe(context, "host-timed", "vsync", true);
        assertEquals(SettingsFeel.describe(context, "host-timed", "off", false).position, host.position, 0.001f);

        // Unknown values fall back to Balanced
        assertEquals(balanced.text, SettingsFeel.describe(context, "something-new", "off", false).text);
    }

    @Test
    public void appsWithoutCoverArtGetIconsAndGamesKeepTheirCovers() {
        // Only the apps hosts add themselves, by their exact names
        assertTrue(AppIcons.hasNoCoverArt("Desktop"));
        assertTrue(AppIcons.hasNoCoverArt("Steam Big Picture"));
        assertTrue(AppIcons.hasNoCoverArt(" playnite "));
        assertFalse(AppIcons.hasNoCoverArt("Epic Mickey"));
        assertFalse(AppIcons.hasNoCoverArt("Remote Desktop Game Night"));

        // Game titles with app-like words aren't mistaken for apps
        assertEquals(AppIcons.Kind.GAME, AppIcons.kindFor("Mirror's Edge"));
        assertEquals(AppIcons.Kind.GAME, AppIcons.kindFor("Command & Conquer"));
        assertEquals(AppIcons.Kind.RETRO, AppIcons.kindFor("RetroArch"));
        assertEquals(AppIcons.Kind.MEDIA, AppIcons.kindFor("Netflix"));

        // A game shown as an icon keeps the same colour every time
        assertEquals(AppIcons.accentFor("Hades"), AppIcons.accentFor("HADES"));
    }

    @Test
    public void theCoverSettingChoosesIconsOrCovers() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        try {
            prefs.edit().remove(AppIcons.PREF_COVER_STYLE).commit();
            assertTrue(AppIcons.useIcon(context, "Desktop"));
            assertFalse(AppIcons.useIcon(context, "Hades"));

            prefs.edit().putString(AppIcons.PREF_COVER_STYLE, AppIcons.STYLE_ALWAYS).commit();
            assertTrue(AppIcons.useIcon(context, "Hades"));

            prefs.edit().putString(AppIcons.PREF_COVER_STYLE, AppIcons.STYLE_NEVER).commit();
            assertFalse(AppIcons.useIcon(context, "Desktop"));
        }
        finally {
            prefs.edit().remove(AppIcons.PREF_COVER_STYLE).commit();
        }
    }

    @Test
    public void theRainbowRingOnlyTurnsWhileShowing() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Rainbow.Ring ring = new Rainbow.Ring(18, 3);
            ring.setVisible(true, false);
            assertTrue(ring.isRunning());
            ring.setVisible(false, false);
            assertFalse(ring.isRunning());
        });
    }

    @Test
    public void streamMenuOverlayRendersOnTransparent() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context context = new ContextThemeWrapper(target, R.style.AppTheme);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Transparent around the sheet, to lay over a game screenshot
            FrameLayout root = new FrameLayout(context);
            com.limelight.ui.overlay.OverlayMenuView menu = new com.limelight.ui.overlay.OverlayMenuView(context);
            float d = context.getResources().getDisplayMetrics().density;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.END);
            params.setMargins((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (16 * d));
            root.addView(menu, params);
            menu.setGameInfo("Aniimo", "1504p · 120 FPS", 0xFF4F46E5);
            menu.buildMenu();
            menu.setVisibility(View.VISIBLE);
            menu.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_UP));
            menu.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN));
            save(root, target, "menu_overlay.png");

            // Every row of tools is laid out. Where they don't all fit (a phone, a Fold's cover
            // screen), the grid fades out at the bottom to show there's more, and scrolls far
            // enough to show the last row whole.
            android.widget.ScrollView grid = findFirst(menu, android.widget.ScrollView.class);
            ViewGroup rows = (ViewGroup) grid.getChildAt(0);
            StringBuilder layout = new StringBuilder("scroll h=" + grid.getHeight() + " content h=" + rows.getHeight() + ";");
            for (int i = 0; i < rows.getChildCount(); i++) {
                View row = rows.getChildAt(i);
                layout.append(" row").append(i).append(" top=").append(row.getTop()).append(" h=").append(row.getHeight())
                        .append(" n=").append(((ViewGroup) row).getChildCount())
                        .append(" first h=").append(((ViewGroup) row).getChildAt(0).getHeight()).append(';');
            }
            View last = rows.getChildAt(rows.getChildCount() - 1);
            assertTrue(layout.toString(), last.getHeight() > 0);
            if (rows.getHeight() <= grid.getHeight()) {
                assertTrue(layout.toString(), last.getBottom() <= grid.getHeight());
            }
            else {
                assertTrue("nothing shows there are more tools; " + layout, grid.getVerticalFadingEdgeLength() > 0);
                grid.scrollTo(0, rows.getHeight());
                assertTrue("can't scroll to the last row; " + layout, last.getTop() >= grid.getScrollY()
                        && last.getBottom() <= grid.getScrollY() + grid.getHeight());
            }
        });
    }

    private static <T extends View> T findFirst(View root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                T found = findFirst(((ViewGroup) root).getChildAt(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void predictedImpactSaysWhatMovesIt() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SettingsFeel warpVsync = SettingsFeel.describe(context, "warp", "vsync", true);
        assertEquals(3, warpVsync.factors.size());
        assertEquals(SettingsFeel.EFFECT_FASTER, warpVsync.factors.get(0).effect);
        assertEquals(SettingsFeel.EFFECT_SMOOTHER, warpVsync.factors.get(1).effect);
        assertEquals(SettingsFeel.EFFECT_FASTER, warpVsync.factors.get(2).effect);
        // The values are the names people see in settings, not internal ones
        assertTrue(warpVsync.factors.get(0).value, warpVsync.factors.get(0).value.startsWith("Warp Drive"));

        // Host frame timing ignores V-Sync and Async LFR
        SettingsFeel host = SettingsFeel.describe(context, "host-timed", "vsync", true);
        assertEquals(SettingsFeel.EFFECT_NOT_USED, host.factors.get(1).effect);
        assertEquals(SettingsFeel.EFFECT_NOT_USED, host.factors.get(2).effect);
    }

    @Test
    public void borrowedSettingsAreCredited() {
        assertTrue(CoverGlowSettings.creditFor("checkbox_async_decode").contains("derflacco"));
        assertTrue(CoverGlowSettings.creditFor("list_audio_haptics").contains("Moonlight V+"));
        assertTrue(CoverGlowSettings.creditFor("checkbox_custom_vulkan_driver").contains("NoCrypt"));
        // Moonlight's own resolution and frame rate settings take any value, from Moonlight X
        assertTrue(CoverGlowSettings.creditFor("list_resolution").contains("Moonlight X"));
        assertTrue(CoverGlowSettings.creditFor("list_fps").contains("MoreOrLessSoftware"));
        // Moonlight's own and Starwire's new settings have no credit line
        assertEquals(null, CoverGlowSettings.creditFor("seekbar_bitrate_kbps"));
        assertEquals(null, CoverGlowSettings.creditFor("hide_cursor_mode"));
    }

    @Test
    public void launcherIconRenders() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.graphics.drawable.Drawable icon = target.getDrawable(R.mipmap.ic_launcher);
        int size = 512;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, size, size);
        icon.draw(new Canvas(bitmap));
        // Not blank: the void in the middle is dark and the ring around it is bright
        int middle = bitmap.getPixel(size / 2, size / 2);
        assertTrue("middle " + Integer.toHexString(middle), Color.alpha(middle) > 200 && luminanceOf(middle) < 0.05);
        File out = TestFiles.file(target, "starwire_icon.png");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static double luminanceOf(int color) {
        return luminance(color);
    }

    @Test
    public void sectionNamesDropTheWordSettings() {
        assertEquals("Audio", CoverGlowSettings.shortName("Audio Settings"));
        assertEquals("Overlay Menu", CoverGlowSettings.shortName("Overlay Menu"));
        assertEquals("Settings", CoverGlowSettings.shortName("Settings"));
    }

    @Test
    public void streamMenuFitsAPhoneHeldSideways() {
        // A Galaxy Z Fold's cover screen turned sideways for a stream: about 905 x 369 dp
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.res.Configuration phone = new android.content.res.Configuration(
                target.getResources().getConfiguration());
        phone.screenWidthDp = 905;
        phone.screenHeightDp = 369;
        Context context = new ContextThemeWrapper(target.createConfigurationContext(phone), R.style.AppTheme);
        float d = context.getResources().getDisplayMetrics().density;
        int width = Math.round(905 * d), height = Math.round(369 * d);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FrameLayout root = new FrameLayout(context);
            root.setBackgroundColor(0xFF2A3340);
            com.limelight.ui.overlay.OverlayMenuView menu = new com.limelight.ui.overlay.OverlayMenuView(context);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.END);
            params.setMargins((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (16 * d));
            root.addView(menu, params);
            menu.setGameInfo("Desktop", "1080p · 60 FPS", AppIcons.accentFor("Desktop"));
            menu.setCover(AppIcons.tile(context, "Desktop"));
            menu.buildMenu();
            menu.setVisibility(View.VISIBLE);

            save(root, target, "menu_phone_sideways.png", width, height);
            assertTrue("sheet taller than the screen: " + menu.getHeight() + " px of " + height,
                    menu.getHeight() <= height);
            // Close, Quit and Disconnect share one row, so at least two rows of tools show
            View close = findLabel(menu, context.getString(R.string.overlay_menu_close));
            View disconnect = findLabel(menu, context.getString(R.string.overlay_menu_disconnect));
            int[] a = new int[2], b = new int[2];
            close.getLocationInWindow(a);
            disconnect.getLocationInWindow(b);
            assertEquals("bar buttons on different rows", a[1], b[1]);
        });
    }

    private static void save(View root, Context target, String name) {
        save(root, target, name, 2400, 1504);
    }

    // The menu button showing this label
    private static View findLabel(View root, String label) {
        if (root instanceof com.limelight.ui.overlay.OverlayMenuButton) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.widget.TextView
                        && label.contentEquals(((android.widget.TextView) child).getText())) {
                    return root;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findLabel(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void save(View root, Context target, String name, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File out = TestFiles.file(target, name);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void homeScreenRendersAtTabletSize() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context context = new ContextThemeWrapper(target, R.style.AppTheme);
        final int tint = AppIcons.accentFor("Desktop");

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LayoutInflater inflater = LayoutInflater.from(context);
            View root = inflater.inflate(R.layout.activity_app_view, null);
            android.animation.ValueAnimator rainbow = Rainbow.applyTo((TextView) root.findViewById(R.id.cgWordmark));
            root.setBackgroundColor(CoverGlow.deep(tint));
            root.findViewById(R.id.coverGlow).setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(tint));
            ((TextView) root.findViewById(R.id.appListText)).setText("GAMING-PC");
            ((TextView) root.findViewById(R.id.coverRowLabel)).setText("On GAMING-PC");
            ((TextView) root.findViewById(R.id.heroLabel)).setText(R.string.cg_last_played);
            ((TextView) root.findViewById(R.id.heroTitle)).setText("Desktop");
            ((ImageView) root.findViewById(R.id.heroCover)).setImageDrawable(AppIcons.tile(context, "Desktop"));

            float d = context.getResources().getDisplayMetrics().density;
            LinearLayout chips = root.findViewById(R.id.heroChips);
            for (String chip : new String[] {"PyroWave", "1440p · 120 FPS", "Warp Drive", "Fast V-Sync"}) {
                TextView view = new TextView(context);
                view.setText(chip);
                view.setTextColor(Color.WHITE);
                view.setTextSize(14);
                view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                view.setBackgroundResource(R.drawable.cg_chip);
                view.setPadding((int) (14 * d), (int) (7 * d), (int) (14 * d), (int) (7 * d));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMarginEnd((int) (8 * d));
                chips.addView(view, params);
            }

            // The cover row, with the icon tiles Starwire draws for these kinds of app
            FrameLayout container = root.findViewById(R.id.appFragmentContainer);
            LinearLayout row = new LinearLayout(context);
            // Desktop, an app with its own icon, then games without cover art (each its own take)
            String[] names = {"Desktop", "Steam Big Picture", "Hades II", "Celeste", "Balatro", "Hollow Knight", "Dredge", "Tunic"};
            for (int i = 0; i < names.length; i++) {
                View item = inflater.inflate(R.layout.app_grid_item, row, false);
                ((ImageView) item.findViewById(R.id.grid_image)).setImageDrawable(AppIcons.tile(context, names[i]));
                TextView label = item.findViewById(R.id.grid_text);
                label.setText(names[i]);
                label.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
                if (i == 0) {
                    // The selected game's ring, as AppGridAdapter draws it: a view's foreground is
                    // Android 6 and later (this stopped the test on Android 5 with NoSuchMethodError)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        item.setForeground(new Rainbow.Ring(26 * d, 3 * d));
                    }
                }
                else {
                    item.setAlpha(0.82f);
                }
                row.addView(item);
            }
            container.addView(row);

            int width = 2400, height = 1504;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);

            // Everything fits: at least one full row of covers is left below the hero
            View coverRow = root.findViewById(R.id.appFragmentContainer);
            assertTrue("cover row only " + coverRow.getHeight() + " px",
                    coverRow.getHeight() >= 176 * d);

            rainbow.cancel();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            File out = TestFiles.file(target, "cover_glow_home.png");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
