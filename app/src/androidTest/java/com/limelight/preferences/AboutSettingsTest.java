package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.BuildConfig;
import com.limelight.R;
import com.limelight.ui.AboutCredits;
import com.limelight.ui.OpenSourceNotices;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Settings > About, and the Credits and Open-source licences pages it opens. */
@RunWith(AndroidJUnit4.class)
public class AboutSettingsTest {
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void aboutIsTheLastSection() {
        try (ActivityScenario<StreamSettings> scenario = ActivityScenario.launch(StreamSettings.class)) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                LinearLayout pills = activity.findViewById(R.id.settingsSections);
                TextView last = (TextView) pills.getChildAt(pills.getChildCount() - 1);
                assertEquals(activity.getString(R.string.category_about), last.getText().toString());

                PreferenceFragment fragment = (PreferenceFragment)
                        activity.getFragmentManager().findFragmentById(R.id.stream_settings);
                Preference version = fragment.findPreference("about_version");
                assertNotNull(version);
                String summary = version.getSummary().toString();
                assertTrue(summary, summary.startsWith(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"));
                // Built from a git checkout, so the commit is known
                assertTrue("commit " + BuildConfig.GIT_COMMIT, BuildConfig.GIT_COMMIT.matches("[0-9a-f]{12}"));
                assertTrue(summary, summary.contains(BuildConfig.GIT_COMMIT));

                for (String key : new String[] {"about_github", "about_issues", "about_source",
                        "about_credits", "about_licences"}) {
                    Preference item = fragment.findPreference(key);
                    assertNotNull(key, item);
                    assertTrue(key + " has no summary", !TextUtils.isEmpty(item.getSummary()));
                }
                assertTrue(fragment.findPreference("about_source").getSummary().toString()
                        .contains(BuildConfig.VERSION_NAME));
            });
        }
    }

    @Test
    public void versionDetailsTellBuildsApart() {
        String details = StreamSettings.SettingsFragment.versionDetails();
        assertTrue(details, details.startsWith("Starwire " + BuildConfig.VERSION_NAME));
        assertTrue(details, details.contains(BuildConfig.GIT_COMMIT));
        assertTrue(details, details.contains(BuildConfig.BUILD_TYPE));
        assertTrue(details, details.contains("API " + Build.VERSION.SDK_INT));
        assertTrue(details, details.contains(Build.MODEL));
    }

    @Test
    public void licencesPageListsEveryComponent() {
        checkPage(OpenSourceActivity.PAGE_LICENCES, R.string.title_about_licences,
                OpenSourceNotices.sections(), OpenSourceNotices.ALL.length);
    }

    @Test
    public void creditsPageListsEveryProject() {
        checkPage(OpenSourceActivity.PAGE_CREDITS, R.string.title_about_credits,
                AboutCredits.sections(), AboutCredits.ALL.length);
    }

    // The sections are the ones the page is built from, not a count typed in here: that count
    // stayed at 5 when 0.3.0 added the USB devices credits, and failed on every emulator leg that
    // ran it. A section left out, or listed twice, still fails, through the entries count or the
    // repeat check. AboutPagesTest checks the same on the JVM.
    private void checkPage(String page, int title, int[] sections, int entries) {
        Intent intent = new Intent(target, OpenSourceActivity.class)
                .putExtra(OpenSourceActivity.EXTRA_PAGE, page);
        try (ActivityScenario<OpenSourceActivity> scenario = ActivityScenario.launch(intent)) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                assertEquals(activity.getString(title),
                        ((TextView) activity.findViewById(R.id.settingsTitle)).getText().toString());
                List<String> expected = new ArrayList<>();
                for (int section : sections) {
                    expected.add(activity.getString(section));
                }
                assertEquals(page + " lists a section twice: " + expected,
                        expected.size(), new HashSet<>(expected).size());

                LinearLayout pills = activity.findViewById(R.id.settingsSections);
                List<String> shown = new ArrayList<>();
                for (int i = 0; i < pills.getChildCount(); i++) {
                    shown.add(((TextView) pills.getChildAt(i)).getText().toString());
                }
                assertEquals(page + " section pills", expected, shown);

                PreferenceFragment fragment = (PreferenceFragment)
                        activity.getFragmentManager().findFragmentById(R.id.stream_settings);
                PreferenceScreen screen = fragment.getPreferenceScreen();
                List<String> headings = new ArrayList<>();
                int items = 0;
                for (int i = 0; i < screen.getPreferenceCount(); i++) {
                    PreferenceCategory category = (PreferenceCategory) screen.getPreference(i);
                    headings.add(category.getTitle().toString());
                    assertTrue(category.getTitle() + " is empty", category.getPreferenceCount() > 0);
                    for (int j = 0; j < category.getPreferenceCount(); j++) {
                        Preference item = category.getPreference(j);
                        assertTrue(!TextUtils.isEmpty(item.getTitle()));
                        assertTrue(item.getTitle() + " has no summary", !TextUtils.isEmpty(item.getSummary()));
                        items++;
                    }
                }
                assertEquals(page + " headings", expected, headings);
                assertEquals(page + " entries", entries, items);
            });
        }
    }
}
