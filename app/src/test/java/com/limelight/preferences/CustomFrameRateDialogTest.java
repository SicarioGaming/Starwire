package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * The custom frame rate dialog's OK button, run by Robolectric: a rate it refuses keeps the
 * dialog open with an error on the field, and a good rate entered after that is saved and closes
 * the dialog. Android TV 14 and Android 6 emulators left the dialog open with 37 in it
 * (2026-10-27). On Android TV 14 the log shows the device test's tap landing on the refused
 * value's error popup, not on OK. Android 6's log was gone, so the same cause is likely there but
 * not shown. This checks without a screen that OK doesn't turn the rate down.
 * CustomResolutionTest presses the same buttons on devices.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block, and the settings read its
// audio constants. Instrumenting it lets Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class CustomFrameRateDialogTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static EditText findField(View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText field = findField(group.getChildAt(i));
                if (field != null) {
                    return field;
                }
            }
        }
        return null;
    }

    @Test
    public void okTakesAGoodRateAfterARefusedOne() {
        prefs.edit().putString("list_fps", "60").commit();
        StreamSettings activity = Robolectric.buildActivity(StreamSettings.class).setup().get();
        idle();
        PreferenceFragment fragment = (PreferenceFragment)
                activity.getFragmentManager().findFragmentById(R.id.stream_settings);
        FpsListPreference fps = (FpsListPreference) fragment.findPreference("list_fps");

        // The list of rates, then its Custom button
        fps.onClick();
        idle();
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        list.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        idle();
        AlertDialog custom = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame("the Custom button opened no dialog", list, custom);
        EditText field = findField(custom.getWindow().getDecorView());
        assertNotNull("the custom frame rate dialog has no field", field);

        field.setText("0");
        custom.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue("0 closed the dialog", custom.isShowing());
        assertEquals(context.getString(R.string.seekbar_custom_invalid), String.valueOf(field.getError()));
        assertEquals("60", prefs.getString("list_fps", null));

        field.setText("37");
        custom.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse("37 left the dialog open", custom.isShowing());
        assertEquals("37", fps.getValue());
        assertEquals("37", prefs.getString("list_fps", null));
        assertEquals(context.getString(R.string.fps_custom_entry, "37"), fps.getEntry().toString());
    }
}
