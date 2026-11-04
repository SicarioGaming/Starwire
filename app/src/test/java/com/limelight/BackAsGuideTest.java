package com.limelight;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.preference.PreferenceManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.limelight.binding.input.BackAsGuide;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        shadows = BackAsGuideTest.Connection.class)
public class BackAsGuideTest {
    @Implements(NvConnection.class)
    public static class Connection {
        static final List<Integer> buttons = new ArrayList<>();

        @Implementation
        protected void __constructor__(Context context, ComputerDetails.AddressTuple host,
                int port, String id, StreamConfiguration config, LimelightCryptoProvider crypto,
                X509Certificate cert) {}

        @Implementation
        protected void sendControllerInput(short number, short mask, int flags,
                byte lt, byte rt, short lx, short ly, short rx, short ry) {
            buttons.add(flags);
        }
    }

    private KeyEvent back(int action, int source, int flags, int modifiers) {
        return new KeyEvent(100, 110, action, KeyEvent.KEYCODE_BACK, 0, modifiers,
                0, 0, flags, source);
    }

    @Test
    public void physicalNavigationBackSendsAndReleasesGuideThroughGame() {
        Context context = RuntimeEnvironment.getApplication();
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(context);
        prefs.backAsGuide = true;
        prefs.overlayTriggerButton = "start";
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        NvConnection conn = new NvConnection(context, null, 0, null, null, null, null);
        ControllerHandler handler = new ControllerHandler(activity, conn, null, prefs);
        Object device = ReflectionHelpers.getField(handler, "defaultContext");
        ReflectionHelpers.setField(device, "backIsStart", true);
        ReflectionHelpers.setField(device, "modeIsSelect", true);
        Game game = Robolectric.buildActivity(Game.class).get();
        ReflectionHelpers.setField(game, "prefConfig", prefs);
        ReflectionHelpers.setField(game, "controllerHandler", handler);
        Connection.buttons.clear();
        try {
            assertTrue(game.handleKeyDown(back(KeyEvent.ACTION_DOWN, InputDevice.SOURCE_KEYBOARD, 0, 0)));
            assertTrue(game.handleKeyUp(back(KeyEvent.ACTION_UP, InputDevice.SOURCE_KEYBOARD, 0, 0)));
            assertEquals(Arrays.asList(ControllerPacket.SPECIAL_BUTTON_FLAG, 0), Connection.buttons);
            prefs.backAsGuide = false;
            ReflectionHelpers.setField(device, "backIsStart", false);
            ReflectionHelpers.setField(device, "modeIsSelect", false);
            ReflectionHelpers.setField(device, "ignoreBack", false);
            Connection.buttons.clear();
            assertTrue(handler.handleButtonDown(back(KeyEvent.ACTION_DOWN, InputDevice.SOURCE_GAMEPAD, 0, 0)));
            assertTrue(handler.handleButtonUp(back(KeyEvent.ACTION_UP, InputDevice.SOURCE_GAMEPAD, 0, 0)));
            assertEquals(Arrays.asList(ControllerPacket.BACK_FLAG, 0), Connection.buttons);
            prefs.backAsGuide = true;
            ReflectionHelpers.setField(device, "backIsStart", true);
            ReflectionHelpers.setField(device, "modeIsSelect", true);
            Connection.buttons.clear();
            assertTrue(handler.handleButtonDown(back(KeyEvent.ACTION_DOWN, InputDevice.SOURCE_GAMEPAD, 0, 0)));
            assertTrue(handler.handleButtonUp(back(KeyEvent.ACTION_UP, InputDevice.SOURCE_GAMEPAD, 0, 0)));
            assertEquals(Arrays.asList(ControllerPacket.SPECIAL_BUTTON_FLAG, 0), Connection.buttons);
        } finally {
            handler.stop();
            handler.destroy();
        }
    }

    @Test
    public void preferenceIsOffByDefaultAndPersists() {
        Context context = RuntimeEnvironment.getApplication();
        assertFalse(PreferenceConfiguration.readPreferences(context).backAsGuide);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_back_as_guide", true).commit();
        assertTrue(PreferenceConfiguration.readPreferences(context).backAsGuide);
    }

    @Test
    public void spanishSettingExplainsTheMappingAndMenuAccess() {
        Context context = RuntimeEnvironment.getApplication();
        Configuration spanish = new Configuration(context.getResources().getConfiguration());
        spanish.setLocale(new Locale("es"));
        Context translated = context.createConfigurationContext(spanish);
        assertEquals("Botón Atrás como Guía", translated.getString(R.string.title_back_as_guide));
        String summary = translated.getString(R.string.summary_back_as_guide);
        assertTrue(summary.contains("Guía de Xbox"));
        assertTrue(summary.contains("botón flotante"));
    }

    @Test
    public void leaveMouseVirtualNavigationAndModifiedBackAlone() {
        assertFalse(BackAsGuide.matches(false, back(KeyEvent.ACTION_DOWN, InputDevice.SOURCE_GAMEPAD, 0, 0)));
        for (int source : new int[] {InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_MOUSE_RELATIVE}) {
            assertFalse(BackAsGuide.matches(true, back(KeyEvent.ACTION_DOWN, source, 0, 0)));
        }
        for (int flags : new int[] {KeyEvent.FLAG_VIRTUAL_HARD_KEY, KeyEvent.FLAG_SOFT_KEYBOARD}) {
            assertFalse(BackAsGuide.matches(true, back(KeyEvent.ACTION_DOWN, InputDevice.SOURCE_KEYBOARD, flags, 0)));
        }
        assertFalse(BackAsGuide.matches(true, back(KeyEvent.ACTION_DOWN, InputDevice.SOURCE_KEYBOARD, 0, KeyEvent.META_ALT_ON)));
        assertFalse(BackAsGuide.matches(true, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT)));
    }
}
