package com.limelight.binding.input.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;

import org.junit.Test;

public class AndroidNativePointerCaptureProviderTest {

    // Samsung's input stack reports captured mouse motion with SOURCE_MOUSE_RELATIVE's
    // device bits over a trackball class byte (0x20004) instead of the pointer class
    // AOSP uses. Classifying on the class byte alone loses every motion event there.
    private static final int SOURCE_SAMSUNG_CAPTURED_MOUSE = 0x20004;

    @Test
    public void relativeMouseSourcesMatchOnDeviceBitsNotClassByte() {
        assertTrue(AndroidNativePointerCaptureProvider.isRelativeMouseSource(
                InputDevice.SOURCE_MOUSE_RELATIVE));
        assertTrue(AndroidNativePointerCaptureProvider.isRelativeMouseSource(
                SOURCE_SAMSUNG_CAPTURED_MOUSE));
    }

    @Test
    public void otherSourcesAreNotRelativeMouse() {
        assertFalse(AndroidNativePointerCaptureProvider.isRelativeMouseSource(
                InputDevice.SOURCE_MOUSE));
        assertFalse(AndroidNativePointerCaptureProvider.isRelativeMouseSource(
                InputDevice.SOURCE_TOUCHPAD));
        assertFalse(AndroidNativePointerCaptureProvider.isRelativeMouseSource(
                InputDevice.SOURCE_TOUCHSCREEN));
        assertFalse(AndroidNativePointerCaptureProvider.isRelativeMouseSource(
                InputDevice.SOURCE_KEYBOARD));
        assertFalse(AndroidNativePointerCaptureProvider.isRelativeMouseSource(
                InputDevice.SOURCE_JOYSTICK));
    }
}
