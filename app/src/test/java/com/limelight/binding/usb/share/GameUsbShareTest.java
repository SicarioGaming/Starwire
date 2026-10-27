package com.limelight.binding.usb.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.overlay.OverlayMenuView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Collections;

/**
 * Game's hooks into USB sharing: the setting decides whether the stream offers it, the stream's
 * PC is the one shared with, and the stream ending or the screen closing gives the device back.
 * With no game in its Intent, Game.onCreate sets up its menu and then finishes before anything
 * connects (as in GameFloatingButtonTest); the test then calls the hooks a stream would.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class GameUsbShareTest {
    private static final String PAD = "/dev/bus/usb/001/002";

    private final Context context = RuntimeEnvironment.getApplication();
    private final FakeUsb.Backends backends = new FakeUsb.Backends();
    private final FakeUsb.Queue worker = new FakeUsb.Queue();
    private ActivityController<Game> screen;
    private Game game;

    @Before
    public void plugInAPad() throws Exception {
        UsbShareBackends.setForTests(backends);
        StreamUsbShare.setWorkerForTests(worker);
        shadowOf((UsbManager) context.getSystemService(Context.USB_SERVICE))
                .addOrUpdateUsbDevice(AndroidUsbDevicesTest.pad(PAD, "Pad"), true);
    }

    @After
    public void putTheBuildsOwnBack() {
        UsbShareBackends.setForTests(null);
        StreamUsbShare.setWorkerForTests(null);
    }

    // The stream screen for this PC, with the setting on or off, once the stream is up
    private void streamStarted(boolean settingOn) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_usb_share", settingOn).commit();
        Intent intent = new Intent(context, Game.class)
                .putExtra(Game.EXTRA_HOST, "192.168.1.20")
                .putExtra(Game.EXTRA_PC_UUID, "pc-uuid");
        screen = Robolectric.buildActivity(Game.class, intent).create();
        game = screen.get();
        // What connectionStarted() calls
        ReflectionHelpers.callInstanceMethod(game, "startUsbSharing");
    }

    private StreamUsbShare share() {
        return ReflectionHelpers.getField(game, "usbShare");
    }

    private boolean menuOffersUsb() {
        OverlayMenuView menu = game.findViewById(R.id.overlayMenuView);
        return ReflectionHelpers.getField(menu, "usbDevicesShown");
    }

    private FakeUsb.Tunnel sharePad() {
        share().choose(PAD);
        worker.runAll();
        FakeUsb.Tunnel tunnel = backends.tunnel();
        assertTrue(tunnel.bindAck());
        tunnel.shareAck();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(context.getString(R.string.usb_share_state_shared), share().items().get(0).detail);
        return tunnel;
    }

    @Test
    public void theSettingIsOffByDefault() {
        assertFalse(PreferenceConfiguration.readPreferences(context).usbShare);
    }

    @Test
    public void withTheSettingOffTheStreamOffersNoUsb() {
        streamStarted(false);
        assertFalse(share().isRunning());
        assertFalse(menuOffersUsb());
        share().choose(PAD);
        worker.runAll();
        assertTrue(backends.tunnels.isEmpty());
    }

    @Test
    public void withTheSettingOnItSharesWithTheStreamsPc() {
        streamStarted(true);
        assertTrue(share().isRunning());
        assertTrue(menuOffersUsb());
        FakeUsb.Tunnel tunnel = sharePad();
        assertEquals("192.168.1.20", tunnel.target.host);
        assertEquals("pc-uuid", tunnel.target.computerUuid);
    }

    @Test
    public void theStreamEndingGivesTheDeviceBack() {
        streamStarted(true);
        FakeUsb.Tunnel tunnel = sharePad();
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        assertEquals(Collections.singletonList(UsbShareTunnel.END_STREAM_ENDED), tunnel.closes);
        assertFalse(share().isRunning());
        assertFalse(menuOffersUsb());
    }

    @Test
    public void theScreenClosingGivesTheDeviceBack() {
        streamStarted(true);
        FakeUsb.Tunnel tunnel = sharePad();
        screen.destroy();
        assertEquals(Collections.singletonList(UsbShareTunnel.END_CLOSING), tunnel.closes);
    }
}
