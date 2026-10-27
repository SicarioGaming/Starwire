package com.limelight.binding.usb.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.limelight.R;
import com.limelight.ui.overlay.OverlayMenuView;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowUsbManager;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The share flow as the stream screen holds it: the setting, the menu's item and page, the
 * question for a keyboard or mouse as a real dialog, and stop. Android's UsbManager is
 * Robolectric's; the exporter and tunnel are FakeUsb's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class StreamUsbShareTest {
    static final String PAD = "/dev/bus/usb/001/002";
    static final String KEYBOARD = "/dev/bus/usb/001/003";

    private final FakeUsb.Backends backends = new FakeUsb.Backends();
    private final FakeUsb.Queue worker = new FakeUsb.Queue();
    private final UsbShareTunnel.Target target = new UsbShareTunnel.Target("192.168.1.20", "pc-uuid");
    private Activity activity;
    private OverlayMenuView menu;
    private StreamUsbShare share;
    private UsbDevice keyboard;

    @Before
    public void setUp() throws Exception {
        UsbShareBackends.setForTests(backends);
        StreamUsbShare.setWorkerForTests(worker);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(activity);
        menu = new OverlayMenuView(activity);
        frame.addView(menu);
        activity.setContentView(frame);
        ShadowUsbManager usb = shadowOf((UsbManager) activity.getSystemService(Context.USB_SERVICE));
        usb.addOrUpdateUsbDevice(AndroidUsbDevicesTest.pad(PAD, "Pad"), true);
        keyboard = AndroidUsbDevicesTest.keyboard(KEYBOARD, "Keyboard");
        usb.addOrUpdateUsbDevice(keyboard, true);
        share = new StreamUsbShare(activity, menu);
    }

    @After
    public void putTheBuildsOwnBack() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true);
        UsbShareBackends.setForTests(null);
        StreamUsbShare.setWorkerForTests(null);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private boolean menuOffersUsb() {
        return ReflectionHelpers.getField(menu, "usbDevicesShown");
    }

    private String detail(String key) {
        for (OverlayMenuView.UsbItem item : share.items()) {
            if (item.key.equals(key)) {
                return item.detail;
            }
        }
        return null;
    }

    private AlertDialog question() {
        AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("no question", dialog);
        return dialog;
    }

    @Test
    public void offUnlessTheSettingIsOn() {
        share.start(false, target, null);
        assertFalse(share.isRunning());
        assertFalse(menuOffersUsb());
        assertTrue(share.items().isEmpty());
        share.choose(PAD);
        assertTrue(backends.tunnels.isEmpty());
    }

    @Test
    public void offWithoutBackends() {
        UsbShareBackends.setForTests(null);
        share.start(true, target, null);
        assertFalse(share.isRunning());
        assertFalse(menuOffersUsb());
    }

    @Test
    public void theMenuListsWhatsPluggedIn() {
        share.start(true, target, null);
        assertTrue(share.isRunning());
        assertTrue(menuOffersUsb());
        List<OverlayMenuView.UsbItem> items = share.items();
        assertEquals(2, items.size());
        assertEquals("Pad", items.get(0).name);
        assertEquals(activity.getString(R.string.usb_share_verdict_unchecked), items.get(0).detail);
        assertEquals("Keyboard", items.get(1).name);
        assertEquals(activity.getString(R.string.usb_share_verdict_ask), items.get(1).detail);
    }

    // Closing the question any way but Share is a no: nothing connects, and nothing is left waiting
    @Test
    public void closingTheQuestionIsANo() {
        share.start(true, target, null);
        share.choose(KEYBOARD);
        idle();
        AlertDialog dialog = question();
        assertTrue(dialog.isShowing());
        assertEquals(activity.getString(R.string.usb_share_state_confirm), detail(KEYBOARD));

        dialog.dismiss();
        idle();
        worker.runAll();
        assertTrue(backends.tunnels.isEmpty());
        assertEquals(activity.getString(R.string.usb_share_verdict_ask), detail(KEYBOARD));
        // Another device can be shared at once
        share.choose(PAD);
        worker.runAll();
        assertEquals(1, backends.tunnels.size());
    }

    @Test
    public void shareIsAYes() {
        share.start(true, target, null);
        share.choose(KEYBOARD);
        idle();
        question().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        worker.runAll();
        assertEquals(1, backends.tunnel().connects);
        assertTrue(backends.tunnel().bindAck());
        assertTrue(backends.exporter().confirmed);
    }

    @Test
    public void stopGivesTheDeviceBackAndTakesTheItemAway() {
        share.start(true, target, null);
        share.choose(PAD);
        worker.runAll();
        backends.tunnel().bindAck();
        backends.tunnel().shareAck();
        idle();
        assertEquals(activity.getString(R.string.usb_share_state_shared), detail(PAD));

        share.stop(UsbShareTunnel.END_STREAM_ENDED);
        assertEquals(Collections.singletonList(UsbShareTunnel.END_STREAM_ENDED), backends.tunnel().closes);
        assertFalse(share.isRunning());
        assertFalse(menuOffersUsb());
        assertTrue(share.items().isEmpty());
        // Twice is harmless
        share.stop(UsbShareTunnel.END_CLOSING);
        assertEquals(1, backends.tunnel().closes.size());
    }

    @Test
    public void stopTakesTheQuestionAway() {
        share.start(true, target, null);
        share.choose(KEYBOARD);
        idle();
        AlertDialog dialog = question();
        share.stop(UsbShareTunnel.END_STREAM_ENDED);
        idle();
        assertFalse(dialog.isShowing());
        assertTrue(backends.tunnels.isEmpty());
    }

    // Unplugged while the question is up: the question goes with it
    @Test
    public void unpluggingTakesTheQuestionAway() {
        share.start(true, target, null);
        share.choose(KEYBOARD);
        idle();
        AlertDialog dialog = question();
        shadowOf((UsbManager) activity.getSystemService(Context.USB_SERVICE)).removeUsbDevice(keyboard);
        Intent detached = new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).setPackage(activity.getPackageName());
        detached.putExtra(UsbManager.EXTRA_DEVICE, keyboard);
        activity.sendBroadcast(detached);
        idle();
        assertFalse(dialog.isShowing());
        assertEquals(1, share.items().size());
        assertTrue(backends.tunnels.isEmpty());
    }

    // The phone's network going away reaches the tunnel at once, so a Wi-Fi drop offers "Share
    // again" without waiting for the tunnel's 8 s of silence. Watched only while the stream offers USB.
    @Test
    public void aNetworkDropReachesTheTunnelAtOnce() {
        ShadowConnectivityManager connectivity =
                shadowOf((ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE));
        List<ConnectivityManager.NetworkCallback> before = new ArrayList<>(connectivity.getNetworkCallbacks());
        share.start(true, target, null);
        List<ConnectivityManager.NetworkCallback> added = new ArrayList<>(connectivity.getNetworkCallbacks());
        added.removeAll(before);
        assertEquals(1, added.size());

        share.choose(PAD);
        worker.runAll();
        backends.tunnel().bindAck();
        backends.tunnel().shareAck();
        idle();
        added.get(0).onLost(ShadowNetwork.newInstance(100));
        idle();
        assertEquals(1, backends.tunnel().networkLosses);

        share.stop(UsbShareTunnel.END_STREAM_ENDED);
        assertFalse(connectivity.getNetworkCallbacks().contains(added.get(0)));
    }

    // The device's name is its own choice, so it's quoted, with its IDs. Don't share has the
    // focus, so with a controller the next A is a no, and Share takes a move first.
    @Test
    public void theQuestionQuotesTheDeviceAndStartsOnDontShare() {
        // As after a controller's A, which takes Android out of touch mode
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        share.start(true, target, null);
        share.choose(KEYBOARD);
        idle();
        AlertDialog dialog = question();
        assertEquals("While \"Keyboard\" (054c:0ce6) is shared, the PC gets everything you type or click on it.",
                shadowOf(dialog).getMessage().toString());
        assertTrue(dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isFocused());

        // The D-pad's centre, which Android's generic key map makes of a pad's A
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        idle();
        assertFalse(dialog.isShowing());
        worker.runAll();
        assertTrue(backends.tunnels.isEmpty());
        assertEquals(activity.getString(R.string.usb_share_verdict_ask), detail(KEYBOARD));
    }
}
