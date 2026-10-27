package com.limelight.binding.usb.share;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Looper;
import android.widget.FrameLayout;

import com.limelight.R;
import com.limelight.binding.audio.securemic.MicPairing;
import com.limelight.binding.usb.ExporterOnLoopback;
import com.limelight.binding.usb.UsbExporter;
import com.limelight.binding.usb.tunnel.HelperOnLoopback;
import com.limelight.binding.usb.tunnel.SwutCodec;
import com.limelight.binding.usb.tunnel.UsbTunnel;
import com.limelight.ui.overlay.OverlayMenuView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The stream menu's USB devices page wired to the real exporter and tunnel, end to end on
 * 127.0.0.1: the share flow as the stream screen holds it (StreamUsbShare, with Robolectric's
 * UsbManager), the app's backends (RealUsbShareBackends), the real UsbExporter with Starwire's
 * device rules over a stand-in for the native exporter (ExporterOnLoopback), and the real
 * UsbTunnel against a fake helper (HelperOnLoopback). HELLO, BIND, SHARE, SHARE_ACK, DATA and END
 * go over real TLS, and USB/IP's bytes over the exporter's one authorised connection. The tunnel's
 * clock only moves when a test moves it, and none does, so no time limit or KEEPALIVE comes in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class UsbShareWiredTest {
    private static final String PAD = "/dev/bus/usb/001/002";
    private static final UsbShareTunnel.Target TARGET = new UsbShareTunnel.Target("127.0.0.1", "pc-uuid");

    private final AtomicLong now = new AtomicLong(5_000_000);
    // What the tunnels and the exporter told the share flow, in order
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<MicPairing> pairings = Collections.synchronizedList(new ArrayList<>());
    private final List<UsbTunnel> tunnels = Collections.synchronizedList(new ArrayList<>());
    private final List<CountDownLatch> ended = Collections.synchronizedList(new ArrayList<>());
    private final List<Socket> closeLater = new ArrayList<>();
    private HelperOnLoopback helper;
    private ExporterOnLoopback exporter;
    private Activity activity;
    private StreamUsbShare share;
    private UsbDevice pad;

    @Before
    public void setUp() throws Exception {
        helper = new HelperOnLoopback();
        exporter = new ExporterOnLoopback();
        UsbShareBackends.setForTests(new RealUsbShareBackends(new RealUsbShareBackends.Parts() {
            @Override
            public boolean isSupported() {
                return true;
            }

            @Override
            public UsbExporter exporter(Context app, UsbExporter.Listener listener) {
                return exporter.create(export -> {
                    listener.onDeviceGone(export);
                    events.add("gone");
                });
            }

            @Override
            public UsbExporter.Export start(UsbExporter x, UsbDevice device, boolean inputConfirmed)
                    throws UsbExporter.Refused, IOException {
                return exporter.start(x, device, inputConfirmed);
            }

            @Override
            public UsbTunnel tunnel(UsbTunnel.Exporter x, UsbTunnel.Listener listener) {
                CountDownLatch done = new CountDownLatch(1);
                try {
                    UsbTunnel t = HelperOnLoopback.tunnel(x, new UsbTunnel.Listener() {
                        @Override
                        public void onSharing() {
                            listener.onSharing();
                            events.add("sharing");
                        }

                        @Override
                        public void onEnded(UsbTunnel.Ending ending) {
                            listener.onEnded(ending);
                            events.add("ended " + ending.kind);
                            done.countDown();
                        }
                    }, now);
                    tunnels.add(t);
                    ended.add(done);
                    return t;
                }
                catch (Exception e) {
                    throw new AssertionError(e);
                }
            }

            @Override
            public int helperPort() {
                return helper.port();
            }

            @Override
            public MicPairing pairing(Context app, String computerUuid) {
                MicPairing p = HelperOnLoopback.pairing(computerUuid);
                pairings.add(p);
                return p;
            }
        }));
        StreamUsbShare.setWorkerForTests(Runnable::run);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(activity);
        OverlayMenuView menu = new OverlayMenuView(activity);
        frame.addView(menu);
        activity.setContentView(frame);
        pad = AndroidUsbDevicesTest.pad(PAD, "Test pad");
        shadowOf((UsbManager) activity.getSystemService(Context.USB_SERVICE)).addOrUpdateUsbDevice(pad, true);
        exporter.plug(PAD, HelperOnLoopback.vectorShare());
        share = new StreamUsbShare(activity, menu);
    }

    @After
    public void tearDown() throws Exception {
        try {
            share.stop(UsbShareTunnel.END_CLOSING);
            // A failed test mustn't leave a tunnel holding the app's one share
            List<UsbTunnel> made;
            synchronized (tunnels) {
                made = new ArrayList<>(tunnels);
            }
            for (int i = 0; i < made.size(); i++) {
                made.get(i).networkLost();
                assertTrue("a tunnel never ended", ended.get(i).await(HelperOnLoopback.SAFETY_MS, TimeUnit.MILLISECONDS));
            }
            for (Socket s : closeLater) {
                s.close();
            }
        }
        finally {
            helper.close();
            exporter.close();
            UsbShareBackends.setForTests(null);
            StreamUsbShare.setWorkerForTests(null);
        }
    }

    // ---------------------------------------------------------------- steps
    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    // The next thing the share flow heard, once the main thread has run what it posted
    private String next() throws InterruptedException {
        String e = events.poll(HelperOnLoopback.SAFETY_MS, TimeUnit.MILLISECONDS);
        assertNotNull("nothing more happened", e);
        idle();
        return e;
    }

    // The next tunnel ending, after any "gone" from the exporter
    private String nextEnding() throws InterruptedException {
        String e;
        do {
            e = next();
        } while (e.equals("gone"));
        assertTrue(e, e.startsWith("ended "));
        return e;
    }

    private String detail(String key) {
        for (OverlayMenuView.UsbItem item : share.items()) {
            if (item.key.equals(key)) {
                return item.detail;
            }
        }
        return null;
    }

    private String str(int id, Object... args) {
        return activity.getString(id, args);
    }

    private void chooseOnThePage(String key) {
        share.choose(key);
        idle();
    }

    // From choosing the pad to sharing it: the SHARE the helper got, and the exporter's connection
    private Socket sharePad() throws Exception {
        share.start(true, TARGET, null);
        chooseOnThePage(PAD);
        assertEquals(str(R.string.usb_share_state_connecting), detail(PAD));
        helper.bind();
        assertArrayEquals("not the pad's SHARE", HelperOnLoopback.vectorShare(), helper.read());
        helper.send(SwutCodec.shareAck(SwutCodec.SHARE_ATTACHING, 0));
        Socket usbip = exporter.acceptAuthorised();
        closeLater.add(usbip);
        assertEquals("sharing", next());
        assertEquals(str(R.string.usb_share_state_shared), detail(PAD));
        return usbip;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        for (int at = 0; at < n; ) {
            int got = in.read(b, at, n - at);
            if (got < 0) {
                throw new IOException("closed after " + at + " bytes");
            }
            at += got;
        }
        return b;
    }

    // n bytes of DATA from the phone, in however many frames they came
    private byte[] dataFromThePhone(int n) throws IOException {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        while (got.size() < n) {
            byte[] f = helper.read();
            assertEquals("not DATA", SwutCodec.TYPE_DATA, SwutCodec.typeOf(f));
            got.write(f, SwutCodec.FRAME_HEADER, f.length - SwutCodec.FRAME_HEADER);
        }
        return got.toByteArray();
    }

    // The phone's last frames before it closed: no SHARE among them, and an END with this reason last
    private static void endsWithoutAShare(List<byte[]> frames, int reason) {
        assertFalse("the phone closed without a word", frames.isEmpty());
        for (byte[] f : frames) {
            assertNotEquals("the phone sent a SHARE", SwutCodec.TYPE_SHARE, SwutCodec.typeOf(f));
        }
        byte[] last = frames.get(frames.size() - 1);
        assertEquals("the last frame isn't an END", SwutCodec.TYPE_END, SwutCodec.typeOf(last));
        assertEquals(reason, SwutCodec.code(last, SwutCodec.TYPE_END));
    }

    private static boolean closedByPeer(Socket s) {
        try {
            return s.getInputStream().read() < 0;
        }
        catch (IOException e) {
            return true;
        }
    }

    // The exporter's calls once the pad has gone back, in V+'s order: the connection, the export,
    // then the device
    private void padWentBack(Socket usbip) {
        assertEquals(Arrays.asList("speed", "report 0", "watch on", "start", "localPort", "bind", "authorize",
                "unbind 1-1", "stop", "close " + PAD, "watch off"), exporter.calls());
        assertTrue("the exporter's connection is still open", closedByPeer(usbip));
        for (MicPairing p : pairings) {
            assertArrayEquals("the pairing's key wasn't wiped", new byte[32], p.key);
        }
    }

    // ---------------------------------------------------------------- the tests
    @Test
    public void aPadGoesToThePcThroughTheTunnelAndComesBack() throws Exception {
        share.start(true, TARGET, null);
        chooseOnThePage(PAD);
        helper.bind();
        // The rules read all of the device before it was exported, and the SHARE is what they read
        assertArrayEquals(HelperOnLoopback.vectorShare(), helper.read());
        assertEquals(Arrays.asList("speed", "report 0", "watch on", "start", "localPort", "bind"), exporter.calls());
        assertEquals(str(R.string.usb_share_state_connecting), detail(PAD));

        helper.send(SwutCodec.shareAck(SwutCodec.SHARE_ATTACHING, 0));
        Socket usbip = exporter.acceptAuthorised();
        closeLater.add(usbip);
        assertEquals("sharing", next());
        assertEquals(str(R.string.usb_share_state_shared), detail(PAD));

        // USB/IP both ways: the PC's import request reaches the exporter, and its answer the PC
        byte[] request = HelperOnLoopback.opReqImport("1-1");
        helper.send(SwutCodec.data(request, 0, request.length));
        assertArrayEquals(request, readN(usbip.getInputStream(), request.length));
        byte[] answer = new byte[312];
        for (int i = 0; i < answer.length; i++) {
            answer[i] = (byte) (i * 7);
        }
        OutputStream toPc = usbip.getOutputStream();
        toPc.write(answer);
        toPc.flush();
        assertArrayEquals(answer, dataFromThePhone(answer.length));

        // Choosing it again stops: END 1, then the device goes back
        chooseOnThePage(PAD);
        List<byte[]> last = helper.readUntilClosed();
        endsWithoutAShare(last, SwutCodec.END_STOPPED);
        assertEquals("ended " + UsbTunnel.Ending.STOPPED, nextEnding());
        padWentBack(usbip);
        // Shared once, so it's known to be fine now
        assertEquals(str(R.string.usb_share_verdict_allowed), detail(PAD));
        assertEquals(1, pairings.size());
    }

    @Test
    public void theStreamEndingGivesThePadBack() throws Exception {
        Socket usbip = sharePad();
        share.stop(UsbShareTunnel.END_STREAM_ENDED);
        endsWithoutAShare(helper.readUntilClosed(), SwutCodec.END_STREAM_ENDED);
        assertEquals("ended " + UsbTunnel.Ending.STOPPED, nextEnding());
        padWentBack(usbip);
        assertFalse(share.isRunning());
    }

    @Test
    public void unpluggingThePadEndsTheShare() throws Exception {
        Socket usbip = sharePad();
        shadowOf((UsbManager) activity.getSystemService(Context.USB_SERVICE)).removeUsbDevice(pad);
        Intent detached = new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).setPackage(activity.getPackageName());
        detached.putExtra(UsbManager.EXTRA_DEVICE, pad);
        activity.sendBroadcast(detached);
        idle();
        endsWithoutAShare(helper.readUntilClosed(), SwutCodec.END_UNPLUGGED);
        assertEquals("ended " + UsbTunnel.Ending.STOPPED, nextEnding());
        padWentBack(usbip);
        assertNull(detail(PAD));
    }

    // The exporter hears of the unplug first: it gives the device back at once, and the PC hears END 2
    @Test
    public void theExportersUnplugWatchEndsTheShare() throws Exception {
        Socket usbip = sharePad();
        exporter.unplug(PAD);
        endsWithoutAShare(helper.readUntilClosed(), SwutCodec.END_UNPLUGGED);
        String ending = nextEnding();
        // Whichever saw it first, the exporter's connection closing or the share flow's stop
        assertTrue(ending, ending.equals("ended " + UsbTunnel.Ending.DEVICE_GONE)
                || ending.equals("ended " + UsbTunnel.Ending.STOPPED));
        padWentBack(usbip);
    }

    // The list showed a pad, but its report descriptor is a keyboard's. Nothing goes to the PC, not
    // even a SHARE, until you say yes, and then SHARE says you did.
    @Test
    public void aKeyboardTheListDidntShowWaitsForYourYes() throws Exception {
        byte[] keyboard = HelperOnLoopback.caseShare("report_keyboard");
        exporter.plug(PAD, keyboard);
        share.start(true, TARGET, null);
        // What Android tells every app looks like a pad's
        assertEquals(str(R.string.usb_share_verdict_unchecked), detail(PAD));
        chooseOnThePage(PAD);
        helper.bind();
        endsWithoutAShare(helper.readUntilClosed(), SwutCodec.END_UNPLUGGED);
        assertEquals("ended " + UsbTunnel.Ending.DEVICE_GONE, nextEnding());
        // Read and closed again, never exported
        assertEquals(Arrays.asList("speed", "report 0", "close " + PAD), exporter.calls());
        assertEquals(str(R.string.usb_share_state_confirm), detail(PAD));

        AlertDialog question = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("no question", question);
        question.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        helper.bind();
        SwutCodec.Share sent = SwutCodec.parseShare(helper.read());
        SwutCodec.Share want = SwutCodec.parseShare(keyboard);
        assertNotNull(sent);
        assertEquals("SHARE doesn't say you said yes", SwutCodec.FLAG_CONFIRMED_INPUT, sent.flags);
        assertArrayEquals(want.deviceDescriptor, sent.deviceDescriptor);
        assertArrayEquals(want.configuration, sent.configuration);
        assertArrayEquals(want.reports[0], sent.reports[0]);
        assertEquals(2, pairings.size());

        chooseOnThePage(PAD);
        endsWithoutAShare(helper.readUntilClosed(), SwutCodec.END_STOPPED);
        assertEquals("ended " + UsbTunnel.Ending.STOPPED, nextEnding());
        assertEquals(str(R.string.usb_share_verdict_ask), detail(PAD));
    }

    // The exporter refuses what the list couldn't see: nothing is exported or sent, and the page says why
    @Test
    public void aDeviceTheRulesRefuseStaysOnThePhone() throws Exception {
        exporter.plug(PAD, HelperOnLoopback.caseShare("report_fido_page"));
        share.start(true, TARGET, null);
        chooseOnThePage(PAD);
        helper.bind();
        endsWithoutAShare(helper.readUntilClosed(), SwutCodec.END_UNPLUGGED);
        assertEquals("ended " + UsbTunnel.Ending.DEVICE_GONE, nextEnding());
        assertEquals(Arrays.asList("speed", "report 0", "close " + PAD), exporter.calls());
        assertEquals(str(R.string.usb_share_verdict_refused, str(R.string.usb_share_rule_other_class)), detail(PAD));
    }
}
