package com.limelight.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The helpers that let the device tests run back to Android 5: where a test writes its files,
 * and granting a permission. Before (2026-10-23), writing a file failed on the Android 5
 * emulator, and granting a permission on the Android 5 and 6 emulators.
 */
@RunWith(AndroidJUnit4.class)
public class TestSetupTest {
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    // Shared storage, when there is some, is where device-test.sh pulls the files from
    @Test
    public void filesGoToSharedStorageWhenThereIsSome() {
        File shared = new File(target.getCacheDir(), "shared-storage");
        Context withSharedStorage = new ContextWrapper(target) {
            @Override
            public File getExternalFilesDir(String type) {
                return shared;
            }
        };
        assertEquals(shared, TestFiles.dir(withSharedStorage));
        assertEquals(shared, TestFiles.file(withSharedStorage, "a.png").getParentFile());
    }

    // What the tests that save screenshots do, wherever this device puts the file
    @Test
    public void aTestCanSaveAFileOnThisDevice() throws Exception {
        File file = TestFiles.file(target, "test-setup-check.txt");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(1);
        }
        assertTrue(file.delete());
    }

    @Test
    public void filesGoToThePrivateFolderWithoutSharedStorage() throws Exception {
        // As on an Android 5 emulator without an SD card
        Context noSharedStorage = new ContextWrapper(target) {
            @Override
            public File getExternalFilesDir(String type) {
                return null;
            }
        };
        File file = TestFiles.file(noSharedStorage, "test-setup-check.txt");
        assertEquals(target.getFilesDir(), file.getParentFile());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(1);
        }
        assertTrue(file.delete());
    }

    @Test
    public void theMicrophonePermissionCanBeGranted() {
        Automation.grantRuntimePermission(target.getPackageName(), Manifest.permission.RECORD_AUDIO);
        assertEquals(PackageManager.PERMISSION_GRANTED,
                target.getPackageManager().checkPermission(Manifest.permission.RECORD_AUDIO, target.getPackageName()));
    }

    @Test
    public void uiAutomationIsTheSameEachTime() {
        assertNotNull(Automation.uiAutomation());
        // A second connection would have replaced the first, and UI Automator with it
        assertSame(Automation.uiAutomation(), Automation.uiAutomation());
    }
}
