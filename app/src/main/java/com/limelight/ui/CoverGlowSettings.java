package com.limelight.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.animation.DecelerateInterpolator;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Cover Glow settings pages: the back button and title, and a pill per section above the list.
 * Tapping a pill scrolls to that section; scrolling the list moves the highlight along.
 */
public final class CoverGlowSettings {
    private static final int PILL_TEXT = 0xFFE2E8F0;
    private static final int PILL_TEXT_SELECTED = 0xFF0B0F14;

    private CoverGlowSettings() {}

    /** Call after setContentView. A null title keeps "Settings". */
    public static void setUpHeader(Activity activity, CharSequence title) {
        if (title != null) {
            ((TextView) activity.findViewById(R.id.settingsTitle)).setText(title);
        }
        activity.findViewById(R.id.settingsBack).setOnClickListener(v -> activity.onBackPressed());
    }

    /** Call from the fragment's onViewCreated, once its preferences are added. */
    public static void attach(PreferenceFragment fragment, View fragmentView) {
        Activity activity = fragment.getActivity();
        LinearLayout pills = activity.findViewById(R.id.settingsSections);
        HorizontalScrollView pillScroll = activity.findViewById(R.id.settingsSectionsScroll);
        ListView list = fragmentView.findViewById(android.R.id.list);
        if (pills == null || list == null) {
            return;
        }
        float d = activity.getResources().getDisplayMetrics().density;

        // The fragment binds its list after this, so add the impact lines once it has
        list.post(() -> SettingsImpact.attach(list));

        list.setDivider(new ColorDrawable(0x1AFFFFFF));
        list.setDividerHeight(Math.max(1, Math.round(d)));
        list.setClipToPadding(false);

        List<PreferenceCategory> sections = new ArrayList<>();
        PreferenceScreen screen = fragment.getPreferenceScreen();
        for (int i = 0; screen != null && i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if (preference instanceof PreferenceCategory && preference.getTitle() != null) {
                String friendly = friendlyName(activity, preference.getTitle());
                if (friendly != null) {
                    preference.setTitle(friendly);
                }
                sections.add((PreferenceCategory) preference);
            }
        }

        pills.removeAllViews();
        if (sections.size() < 2) {
            pillScroll.setVisibility(View.GONE);
            return;
        }
        pillScroll.setVisibility(View.VISIBLE);

        Sections state = new Sections(sections, pills, pillScroll, list);
        for (int i = 0; i < sections.size(); i++) {
            TextView pill = new TextView(activity);
            pill.setText(shortName(sections.get(i).getTitle()));
            pill.setTextSize(15);
            pill.setGravity(Gravity.CENTER);
            pill.setMinHeight((int) (44 * d));
            pill.setPadding((int) (18 * d), 0, (int) (18 * d), 0);
            pill.setFocusable(true);
            final int index = i;
            pill.setOnClickListener(v -> state.jumpTo(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd((int) (8 * d));
            pills.addView(pill, params);
        }
        state.highlight(0);

        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) {
                    state.pinned = -1;
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                state.follow(firstVisible);
            }
        });
    }

    // Section headings and their plain-language names
    private static final int[][] FRIENDLY_NAMES = {
            {R.string.category_basic_settings, R.string.cg_section_stream},
            {R.string.category_audio_settings, R.string.cg_section_sound},
            {R.string.category_gamepad_settings, R.string.cg_section_controllers},
            {R.string.category_overlay_menu, R.string.cg_section_menu},
            {R.string.category_input_settings, R.string.cg_section_touch},
            {R.string.category_on_screen_controls_settings, R.string.cg_section_onscreen},
            {R.string.category_host_settings, R.string.cg_section_pc},
            {R.string.category_ui_settings, R.string.cg_section_app},
            {R.string.category_advanced_settings, R.string.cg_section_advanced},
    };

    /** The plain-language name for a section heading, or null (only written in English). */
    static String friendlyName(Context context, CharSequence title) {
        Locale locale = context.getResources().getConfiguration().locale;
        if (locale == null || !"en".equals(locale.getLanguage())) {
            return null;
        }
        for (int[] pair : FRIENDLY_NAMES) {
            if (context.getString(pair[0]).contentEquals(title)) {
                return context.getString(pair[1]);
            }
        }
        return null;
    }

    // The settings the "Predicted impact" panel describes
    // The "Predicted impact" panel shows on screens at least this wide
    static final int FEEL_PANEL_MIN_WIDTH_DP = 840;

    private static final List<String> FEEL_KEYS = Arrays.asList(
            "frame_pacing", "vsync_mode", "checkbox_async_decode", "checkbox_async_lfr");

    /**
     * Shows the "Predicted impact" panel on wide screens and keeps it up to date. Keep the
     * returned listener (preferences only hold it weakly) and pass it back to detachFeel().
     */
    public static SharedPreferences.OnSharedPreferenceChangeListener attachFeel(Activity activity) {
        View panel = activity.findViewById(R.id.feelPanel);
        View column = activity.findViewById(R.id.feelColumn);
        if (panel == null || column == null) {
            return null;
        }
        if (activity.getResources().getConfiguration().screenWidthDp < FEEL_PANEL_MIN_WIDTH_DP) {
            // Hide the whole column, not just the panel: its fixed width would otherwise squeeze
            // the settings list to nothing on narrow screens (Galaxy Z Fold cover screen)
            column.setVisibility(View.GONE);
            panel.setVisibility(View.GONE);
            return null;
        }
        column.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);

        View track = activity.findViewById(R.id.feelTrack);
        View knob = activity.findViewById(R.id.feelKnob);
        if (track.getTag() == null) {
            // Keep the knob in place when the panel is laid out again
            track.setTag(Boolean.TRUE);
            track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (knob.getTag() instanceof Float && r - l != or - ol) {
                    knob.setTranslationX((Float) knob.getTag() * (track.getWidth() - knob.getWidth()));
                }
            });
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        showFeel(activity, prefs, false);
        SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
            if (FEEL_KEYS.contains(key)) {
                showFeel(activity, p, true);
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(listener);
        return listener;
    }

    public static void detachFeel(Activity activity, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        if (listener != null) {
            PreferenceManager.getDefaultSharedPreferences(activity)
                    .unregisterOnSharedPreferenceChangeListener(listener);
        }
    }

    private static void showFeel(Activity activity, SharedPreferences prefs, boolean animate) {
        SettingsFeel feel = SettingsFeel.describe(activity, prefs);
        ((TextView) activity.findViewById(R.id.feelText)).setText(feel.text);
        ((TextView) activity.findViewById(R.id.feelDelay)).setText(SettingsFeel.levelName(activity, feel.delay));
        ((TextView) activity.findViewById(R.id.feelBattery)).setText(SettingsFeel.levelName(activity, feel.battery));

        // One row per setting that moves the marker: its value, and which way it pushes
        LinearLayout factors = activity.findViewById(R.id.feelFactors);
        factors.removeAllViews();
        float d = activity.getResources().getDisplayMetrics().density;
        for (SettingsFeel.Factor factor : feel.factors) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (4 * d), 0, (int) (4 * d));

            TextView name = new TextView(activity);
            name.setText(activity.getString(R.string.feel_factor_row, factor.name, factor.value));
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(13);
            name.setMaxLines(2);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView effect = new TextView(activity);
            effect.setText(SettingsFeel.effectName(activity, factor.effect));
            effect.setTextColor(SettingsFeel.effectColor(factor.effect));
            effect.setTextSize(13);
            effect.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams effectParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            effectParams.setMarginStart((int) (10 * d));
            row.addView(effect, effectParams);
            factors.addView(row);
        }

        View track = activity.findViewById(R.id.feelTrack);
        View knob = activity.findViewById(R.id.feelKnob);
        knob.setTag(feel.position);
        track.post(() -> {
            float x = feel.position * (track.getWidth() - knob.getWidth());
            if (animate) {
                knob.animate().translationX(x).setDuration(450)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }
            else {
                knob.setTranslationX(x);
            }
        });
    }

    // Settings that came from another Moonlight version, credited the same way as the README
    private static final java.util.Map<String, String> CREDITS = new java.util.HashMap<>();

    static {
        String artemide = "From Artemide by derflacco";
        String artemis = "From Artemis by Yukino Song";
        String moonlightX = "From Moonlight X by MoreOrLessSoftware";
        String turnip = "From Moonlight X Turnip by NoCrypt";
        String keyboard = "Full keyboard from Axixi2233 and Artemis by Yukino Song";
        for (String key : new String[] {"checkbox_sync_lfr", "checkbox_async_decode", "checkbox_async_lfr"}) {
            CREDITS.put(key, artemide);
        }
        CREDITS.put("vsync_mode", "Fast V-Sync from Artemide by derflacco");
        CREDITS.put("frame_pacing", "Warp Drive pacing from Artemis by Yukino Song");
        CREDITS.put("checkbox_ultra_low_latency", "From Moonlight X by MoreOrLessSoftware, Artemis and Artemide");
        CREDITS.put("list_audio_haptics", "Idea from Moonlight V+ by qiin2333");
        CREDITS.put("checkbox_enable_microphone", "Capture from logabell's Artemis changes; the PC receiver is new in Starwire");
        CREDITS.put("list_microphone_device", "From logabell's Artemis changes");
        CREDITS.put("seekbar_audio_haptics_intensity", "Idea from Moonlight V+ by qiin2333");
        for (String key : new String[] {"checkbox_enable_floating_button", "virtual_display_mode",
                "checkbox_smart_clipboard_sync", "checkbox_smart_clipboard_sync_toast", "checkbox_hide_clipboard_content"}) {
            CREDITS.put(key, artemis);
        }
        CREDITS.put("checkbox_prevent_packet_loss", "From Artemis by Yukino Song, via Artemide");
        CREDITS.put("seekbar_touch_sensitivity", "From Artemis by Yukino Song and Axixi2233");
        for (String key : new String[] {"overlay_custom_commands", "overlay_trigger_button", "overlay_hold_duration",
                "left_edge_swipe_action", "video_format", "video_renderer", "pyrowave_late_frames"}) {
            CREDITS.put(key, moonlightX);
        }
        // The settings are Moonlight's own; entering any value is from Moonlight X
        CREDITS.put("list_resolution", "Custom resolutions from Moonlight X by MoreOrLessSoftware");
        CREDITS.put("list_fps", "Custom frame rates from Moonlight X by MoreOrLessSoftware");
        CREDITS.put("import_shortcuts_file", "File format from Axixi2233 and Artemis by Yukino Song");
        CREDITS.put("clear_imported_shortcuts", "File format from Axixi2233 and Artemis by Yukino Song");
        CREDITS.put("checkbox_pinch_to_zoom", "Idea from Moonlit Artemis by 4had45");
        for (String key : new String[] {"checkbox_keyboard_vibrate", "checkbox_sticky_modifier_keys", "seekbar_keyboard_opacity"}) {
            CREDITS.put(key, keyboard);
        }
        for (String key : new String[] {"checkbox_custom_vulkan_driver", "text_custom_vulkan_driver_path",
                "checkbox_custom_vulkan_driver_toast"}) {
            CREDITS.put(key, turnip);
        }
        CREDITS.put("checkbox_enable_perf_overlay_lite", "From Axixi2233, via Artemis and Artemide");
    }

    /** Where a setting came from, or null for Moonlight's own and Starwire's new settings. */
    static String creditFor(String key) {
        return key == null ? null : CREDITS.get(key);
    }

    /** Shows, quietly under the Predicted impact panel, where the setting just tapped came from. */
    public static void showCredit(Activity activity, Preference preference) {
        TextView credit = activity.findViewById(R.id.settingCredit);
        View panel = activity.findViewById(R.id.feelPanel);
        if (credit == null || panel == null || panel.getVisibility() != View.VISIBLE) {
            return;
        }
        String source = creditFor(preference.getKey());
        if (source == null) {
            credit.setVisibility(View.GONE);
            return;
        }
        credit.setText(preference.getTitle() + " · " + source);
        credit.setAlpha(0f);
        credit.setVisibility(View.VISIBLE);
        credit.animate().alpha(1f).setDuration(250).start();
    }

    /** "Audio Settings" reads as "Audio" on a pill. */
    static String shortName(CharSequence title) {
        String name = title.toString().trim();
        String suffix = " settings";
        if (name.length() > suffix.length()
                && name.toLowerCase().endsWith(suffix)) {
            name = name.substring(0, name.length() - suffix.length());
        }
        return name;
    }

    private static final class Sections {
        final List<PreferenceCategory> sections;
        final LinearLayout pills;
        final HorizontalScrollView pillScroll;
        final ListView list;
        int selected = -1;
        // A tapped section stays highlighted until the list is dragged, even if it is too
        // near the end to reach the top
        int pinned = -1;

        Sections(List<PreferenceCategory> sections, LinearLayout pills,
                 HorizontalScrollView pillScroll, ListView list) {
            this.sections = sections;
            this.pills = pills;
            this.pillScroll = pillScroll;
            this.list = list;
        }

        int positionOf(PreferenceCategory section) {
            ListAdapter adapter = list.getAdapter();
            for (int i = 0; adapter != null && i < adapter.getCount(); i++) {
                if (adapter.getItem(i) == section) {
                    return i;
                }
            }
            return -1;
        }

        void jumpTo(int index) {
            int position = positionOf(sections.get(index));
            if (position < 0) {
                return;
            }
            pinned = index;
            highlight(index);
            list.smoothScrollToPositionFromTop(position, 0, 300);
            // Over rows of different heights the smooth scroll can stop well off the heading (the
            // On-screen controls heading ended up at the bottom of the screen every time), so stop
            // it and land on the heading exactly, unless the list was dragged or another pill
            // tapped since
            list.postDelayed(() -> {
                if (pinned == index) {
                    // A zero scroll stops what's left of the smooth one
                    list.smoothScrollBy(0, 0);
                    list.setSelectionFromTop(position, 0);
                }
            }, 450);
        }

        void follow(int firstVisible) {
            if (pinned >= 0) {
                highlight(pinned);
                return;
            }
            int current = 0;
            for (int i = 0; i < sections.size(); i++) {
                int position = positionOf(sections.get(i));
                if (position >= 0 && position <= firstVisible) {
                    current = i;
                }
            }
            highlight(current);
        }

        void highlight(int index) {
            if (index == selected) {
                return;
            }
            selected = index;
            for (int i = 0; i < pills.getChildCount(); i++) {
                TextView pill = (TextView) pills.getChildAt(i);
                boolean on = i == index;
                pill.setBackgroundResource(on ? R.drawable.cg_pill_white : R.drawable.cg_pill_frosted);
                pill.setTextColor(on ? PILL_TEXT_SELECTED : PILL_TEXT);
                pill.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                pill.setSelected(on);
            }
            View pill = pills.getChildAt(index);
            pill.post(() -> pillScroll.smoothScrollTo(
                    pill.getLeft() - (pillScroll.getWidth() - pill.getWidth()) / 2, 0));
        }
    }
}
