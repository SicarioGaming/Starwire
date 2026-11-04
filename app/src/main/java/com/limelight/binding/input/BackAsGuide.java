package com.limelight.binding.input;

import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/** Physical Back can be a separate navigation device on Android handhelds. */
public final class BackAsGuide {
    private BackAsGuide() {}

    public static boolean matches(boolean enabled, KeyEvent event) {
        int source = event.getSource();
        return enabled && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.hasNoModifiers()
                && (event.getFlags() & (KeyEvent.FLAG_VIRTUAL_HARD_KEY | KeyEvent.FLAG_SOFT_KEYBOARD)) == 0
                && (source & InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || (source & InputDevice.SOURCE_MOUSE_RELATIVE) != InputDevice.SOURCE_MOUSE_RELATIVE);
    }
}
