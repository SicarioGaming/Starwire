package com.limelight.binding.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;

import com.limelight.RepoFiles;

import org.junit.Test;

/** The USB permission request's rules that don't need a device. */
public class UsbPermissionTest {
    // Android adds the device and its answer to the intent, so from Android 12 it must be mutable
    @Test
    public void thePendingIntentIsMutableFromAndroid12() {
        assertEquals(0, UsbPermission.pendingIntentFlags(30) & PendingIntent.FLAG_MUTABLE);
        assertTrue((UsbPermission.pendingIntentFlags(31) & PendingIntent.FLAG_MUTABLE) != 0);
        assertTrue((UsbPermission.pendingIntentFlags(36) & PendingIntent.FLAG_MUTABLE) != 0);
        assertEquals(0, UsbPermission.pendingIntentFlags(36) & PendingIntent.FLAG_IMMUTABLE);
    }

    @Test
    public void anAnswerCountsOnceAndOnlyForItsOwnDevice() {
        UsbPermission.Answer answer = new UsbPermission.Answer("/dev/bus/usb/001/002");
        assertNull("another action", answer.take("com.limelight.USB_PERMISSION", "/dev/bus/usb/001/002", true));
        assertNull("another device", answer.take(UsbPermission.ACTION, "/dev/bus/usb/001/003", true));
        assertNull("no device", answer.take(UsbPermission.ACTION, null, true));
        assertEquals(Boolean.FALSE, answer.take(UsbPermission.ACTION, "/dev/bus/usb/001/002", false));
        assertNull("only once", answer.take(UsbPermission.ACTION, "/dev/bus/usb/001/002", true));
    }

    // UsbDriverService claims the devices whose permission comes back on its own action, so
    // sharing asks with another one
    @Test
    public void sharingAsksWithItsOwnAction() throws Exception {
        String driver = RepoFiles.read("app/src/main/java/com/limelight/binding/input/driver/UsbDriverService.java");
        assertTrue(driver.contains("\"com.limelight.USB_PERMISSION\""));
        assertNotEquals("com.limelight.USB_PERMISSION", UsbPermission.ACTION);
        assertFalse(driver.contains(UsbPermission.ACTION));
    }
}
