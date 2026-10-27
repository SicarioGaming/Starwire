package com.limelight.testing;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.Configurator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * UiAutomation calls that work back to Android 5, the app's minSdk. Two of them are newer than
 * that and stopped tests on the Android 5 and 6 emulators with NoSuchMethodError:
 * Instrumentation.getUiAutomation(flags) (Android 7) and UiAutomation.grantRuntimePermission
 * (Android 9).
 */
public final class Automation {
    private Automation() {}

    /**
     * The UiAutomation UI Automator works through. Asking with other flags than UiDevice did would
     * connect it again. Before Android 7 there are no flags to ask with.
     */
    public static UiAutomation uiAutomation() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return instrumentation.getUiAutomation(Configurator.getInstance().getUiAutomationFlags());
        }
        return instrumentation.getUiAutomation();
    }

    /**
     * Grants a runtime permission, then checks it took. Android 9 and later have a call for it;
     * Android 6 to 8 use pm grant, run as the shell, which may grant them. Before Android 6 a
     * permission in the manifest is granted when the app is installed.
     */
    public static void grantRuntimePermission(String packageName, String permission) {
        String output = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            uiAutomation().grantRuntimePermission(packageName, permission);
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            output = shell("pm grant " + packageName + " " + permission);
        }
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (context.getPackageManager().checkPermission(permission, packageName) != PackageManager.PERMISSION_GRANTED) {
            throw new AssertionError(packageName + " doesn't have " + permission + " after granting it"
                    + (output.isEmpty() ? "" : " (pm said: " + output.trim() + ")"));
        }
    }

    /** Runs a shell command as the shell user and returns what it printed. */
    public static String shell(String command) {
        ParcelFileDescriptor pfd = uiAutomation().executeShellCommand(command);
        // Reading to the end waits for the command to finish
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new AssertionError("couldn't read the output of: " + command, e);
        }
    }
}
