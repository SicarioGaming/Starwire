package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.R;
import com.limelight.testing.TestFiles;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The settings list always gets room on screen. On a Galaxy Z Fold's cover screen (about 360 dp
 * wide) the Predicted impact column used to take all the width, leaving the settings invisible.
 * Run with the display set to a narrow size (adb shell wm size) to check the phone case; on a
 * tablet it checks the wide case, where the panel shows beside the list.
 */
@RunWith(AndroidJUnit4.class)
public class SettingsWidthTest {
    @Test
    public void settingsListHasRoomAtAnyWidth() {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                StreamSettings.class);
        try (ActivityScenario<StreamSettings> scenario = ActivityScenario.launch(intent)) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                int widthDp = activity.getResources().getConfiguration().screenWidthDp;
                int width = metrics.widthPixels, height = metrics.heightPixels;

                // Lay the screen out by hand at the display's size, so this works with the screen off
                View root = activity.getWindow().getDecorView();
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                saveDrawing(activity, root, width, height, "settings_" + widthDp + "dp.png");

                View list = activity.findViewById(R.id.stream_settings);
                // The column holding the Predicted impact panel and its credit line
                View column = (View) activity.findViewById(R.id.feelPanel).getParent();
                float listDp = list.getWidth() / metrics.density;
                assertTrue("settings list is only " + listDp + " dp wide on a " + widthDp + " dp screen",
                        listDp >= Math.min(280, widthDp - 80));
                // The impact column shows only where it fits beside the list
                assertEquals(widthDp >= 840 ? View.VISIBLE : View.GONE, column.getVisibility());
            });
        }
    }

    private static void saveDrawing(android.app.Activity activity, View root, int width, int height, String name) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height,
                android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                TestFiles.file(activity, name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
