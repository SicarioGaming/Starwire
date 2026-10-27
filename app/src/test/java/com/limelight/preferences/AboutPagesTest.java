package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.AboutCredits;
import com.limelight.ui.OpenSourceNotices;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings > About's Credits and Open-source licences pages, run by Robolectric: a pill and a
 * heading for each section, once each and in order, and every credit or licence under one.
 * AboutSettingsTest checks the pages on a device, but only the device matrix runs it: its typed
 * count of 5 credits sections went stale when 0.3.0 added USB devices, and no JVM test noticed.
 */
@RunWith(RobolectricTestRunner.class)
// The page reads the settings for its language, and the settings read MoonBridge's audio
// constants; instrumenting it skips loading the native library
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class AboutPagesTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void everyCreditIsInASectionThePageShows() {
        int[] used = new int[AboutCredits.ALL.length];
        for (int i = 0; i < used.length; i++) {
            used[i] = AboutCredits.ALL[i].section;
        }
        sectionsMatch("credits", AboutCredits.sections(), used);
    }

    @Test
    public void everyLicenceIsInASectionThePageShows() {
        int[] used = new int[OpenSourceNotices.ALL.length];
        for (int i = 0; i < used.length; i++) {
            used[i] = OpenSourceNotices.ALL[i].section;
        }
        sectionsMatch("licences", OpenSourceNotices.sections(), used);
    }

    @Test
    public void creditsPageShowsEachSection() {
        checkPage(OpenSourceActivity.PAGE_CREDITS, AboutCredits.sections(), AboutCredits.ALL.length);
    }

    @Test
    public void licencesPageShowsEachSection() {
        checkPage(OpenSourceActivity.PAGE_LICENCES, OpenSourceNotices.sections(), OpenSourceNotices.ALL.length);
    }

    // The sections a page lists are the ones its entries use: none left out, none listed twice,
    // and none without an entry under it
    private void sectionsMatch(String page, int[] listed, int[] used) {
        List<String> names = names(context, listed);
        assertEquals(page + " lists a section twice: " + names, names.size(), new LinkedHashSet<>(names).size());
        Set<String> wanted = new LinkedHashSet<>(names(context, used));
        assertEquals(page + ": the sections listed aren't the ones its entries are in",
                wanted, new LinkedHashSet<>(names));
    }

    private void checkPage(String page, int[] sections, int entries) {
        Intent intent = new Intent(context, OpenSourceActivity.class)
                .putExtra(OpenSourceActivity.EXTRA_PAGE, page);
        Activity activity = Robolectric.buildActivity(OpenSourceActivity.class, intent).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        List<String> expected = names(activity, sections);

        LinearLayout pills = activity.findViewById(R.id.settingsSections);
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < pills.getChildCount(); i++) {
            shown.add(((TextView) pills.getChildAt(i)).getText().toString());
        }
        assertEquals(page + " section pills", expected, shown);

        PreferenceScreen screen = ((PreferenceFragment) activity.getFragmentManager()
                .findFragmentById(R.id.stream_settings)).getPreferenceScreen();
        List<String> headings = new ArrayList<>();
        int items = 0;
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            PreferenceCategory category = (PreferenceCategory) screen.getPreference(i);
            headings.add(category.getTitle().toString());
            assertTrue(category.getTitle() + " is empty", category.getPreferenceCount() > 0);
            items += category.getPreferenceCount();
        }
        assertEquals(page + " headings", expected, headings);
        assertEquals(page + " entries", entries, items);
    }

    private static List<String> names(Context context, int[] sections) {
        List<String> names = new ArrayList<>();
        for (int section : sections) {
            names.add(context.getString(section));
        }
        return names;
    }
}
