package com.limelight.testing;

import android.content.Context;

import java.io.File;

/**
 * Where tests leave screenshots and reports: the app's folder in shared storage
 * (Android/data/(package)/files), where device-test.sh pulls them from, or the app's private
 * files folder when the device has no shared storage.
 *
 * An Android 5 emulator without an SD card has none: getExternalFilesDir() returns null there,
 * so new File(null, name) was just the name, and writing it failed with "EROFS (Read-only file
 * system)" in the root folder. Fifteen tests that save a screenshot failed that way on the
 * Android 5.0 leg (2026-10-23): AppViewTest 5, CoverGlowTest 6, ScreenWidthTest 3 and
 * SettingsWidthTest 1.
 */
public final class TestFiles {
    private TestFiles() {}

    /** The folder, which exists. */
    public static File dir(Context context) {
        File shared = context.getExternalFilesDir(null);
        return shared != null ? shared : context.getFilesDir();
    }

    public static File file(Context context, String name) {
        return new File(dir(context), name);
    }
}
