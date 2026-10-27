package com.limelight.binding.usb.share;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.limelight.binding.audio.securemic.MicPairing;
import com.limelight.binding.usb.ExporterOnLoopback;
import com.limelight.binding.usb.UsbDeviceRules;
import com.limelight.binding.usb.UsbExporter;
import com.limelight.binding.usb.tunnel.HelperOnLoopback;
import com.limelight.binding.usb.tunnel.SwutCodec;
import com.limelight.binding.usb.tunnel.UsbTunnel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The app's backends for the share flow (RealUsbShareBackends) on their own: that the build
 * uses them, that they pass the tunnel's and the exporter's answers on unchanged, that the
 * pairing's key is wiped however connect() goes, and that with one exporter for every share, a
 * share only gives back, and only hears about, its own device. UsbShareWiredTest has the whole
 * path on loopback.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class RealUsbShareBackendsTest {
    private static final String PAD = "/dev/bus/usb/001/002";

    private final Context context = RuntimeEnvironment.getApplication();
    private final List<MicPairing> pairings = new ArrayList<>();
    private ExporterOnLoopback exporter;

    @After
    public void putTheBuildsOwnBack() throws IOException {
        UsbShareBackends.setForTests(null);
        if (exporter != null) {
            exporter.close();
        }
    }

    // Backends over the real exporter and tunnel, with loopback stand-ins below them
    private RealUsbShareBackends backends() throws IOException {
        exporter = new ExporterOnLoopback();
        exporter.plug(PAD, HelperOnLoopback.vectorShare());
        return new RealUsbShareBackends(new RealUsbShareBackends.Parts() {
            @Override
            public boolean isSupported() {
                return true;
            }

            @Override
            public UsbExporter exporter(Context app, UsbExporter.Listener listener) {
                return exporter.create(listener);
            }

            @Override
            public UsbExporter.Export start(UsbExporter x, UsbDevice device, boolean inputConfirmed)
                    throws UsbExporter.Refused, IOException {
                return exporter.start(x, device, inputConfirmed);
            }

            @Override
            public UsbTunnel tunnel(UsbTunnel.Exporter x, UsbTunnel.Listener listener) {
                try {
                    return HelperOnLoopback.tunnel(x, listener, new AtomicLong());
                }
                catch (Exception e) {
                    throw new AssertionError(e);
                }
            }

            @Override
            public int helperPort() {
                return UsbTunnel.DEFAULT_PORT;
            }

            @Override
            public MicPairing pairing(Context app, String computerUuid) {
                MicPairing p = HelperOnLoopback.pairing(computerUuid);
                pairings.add(p);
                return p;
            }
        });
    }

    @Test
    public void theBuildSharesThroughTheRealExporterAndTunnel() {
        UsbShareBackends.Factory factory = UsbShareBackends.get();
        assertTrue(factory instanceof RealUsbShareBackends);
        assertSame(factory, UsbShareBackends.get());
        // A computer isn't a 64-bit ARM phone
        assertEquals(UsbExporter.isSupported(), factory.isSupported());
        assertEquals(UsbExporter.isSupported(), UsbShareBackends.available());
    }

    // The share flow's numbers are the tunnel's and the contract's, so passing them on changes nothing
    @Test
    public void theShareFlowNumbersEndingsAsTheTunnelDoes() {
        int[][] kinds = {
                {UsbShareTunnel.Ending.NO_PAIRING, UsbTunnel.Ending.NO_PAIRING},
                {UsbShareTunnel.Ending.NOT_HOME_NETWORK, UsbTunnel.Ending.NOT_HOME_NETWORK},
                {UsbShareTunnel.Ending.CANT_REACH, UsbTunnel.Ending.CANT_REACH},
                {UsbShareTunnel.Ending.NO_ANSWER, UsbTunnel.Ending.NO_ANSWER},
                {UsbShareTunnel.Ending.NOT_VERIFIED, UsbTunnel.Ending.NOT_VERIFIED},
                {UsbShareTunnel.Ending.UPDATE_STARWIRE, UsbTunnel.Ending.UPDATE_STARWIRE},
                {UsbShareTunnel.Ending.UPDATE_HELPER, UsbTunnel.Ending.UPDATE_HELPER},
                {UsbShareTunnel.Ending.REFUSED, UsbTunnel.Ending.REFUSED},
                {UsbShareTunnel.Ending.NOT_SHARED, UsbTunnel.Ending.NOT_SHARED},
                {UsbShareTunnel.Ending.ENDED_BY_PC, UsbTunnel.Ending.ENDED_BY_PC},
                {UsbShareTunnel.Ending.STOPPED, UsbTunnel.Ending.STOPPED},
                {UsbShareTunnel.Ending.LOST, UsbTunnel.Ending.LOST},
                {UsbShareTunnel.Ending.PROTOCOL_ERROR, UsbTunnel.Ending.PROTOCOL_ERROR},
                {UsbShareTunnel.Ending.DEVICE_GONE, UsbTunnel.Ending.DEVICE_GONE},
        };
        for (int[] k : kinds) {
            assertEquals(k[1], k[0]);
        }
        assertEquals(SwutCodec.END_STOPPED, UsbShareTunnel.END_STOPPED);
        assertEquals(SwutCodec.END_UNPLUGGED, UsbShareTunnel.END_UNPLUGGED);
        assertEquals(SwutCodec.END_STREAM_ENDED, UsbShareTunnel.END_STREAM_ENDED);
        assertEquals(SwutCodec.END_CLOSING, UsbShareTunnel.END_CLOSING);
        int[] rules = {UsbVerdict.RULE_HUB, UsbVerdict.RULE_STORAGE, UsbVerdict.RULE_NETWORK,
                UsbVerdict.RULE_UNLISTED_VENDOR, UsbVerdict.RULE_UNCONFIRMED_INPUT, UsbVerdict.RULE_CONFIGURATIONS,
                UsbVerdict.RULE_UNREADABLE, UsbVerdict.RULE_OTHER_CLASS, UsbVerdict.RULE_MISMATCH,
                UsbVerdict.RULE_TOO_FAST};
        int[] codec = {SwutCodec.RULE_HUB, SwutCodec.RULE_STORAGE, SwutCodec.RULE_NETWORK,
                SwutCodec.RULE_UNLISTED_VENDOR, SwutCodec.RULE_UNCONFIRMED_INPUT, SwutCodec.RULE_CONFIGURATIONS,
                SwutCodec.RULE_UNREADABLE, SwutCodec.RULE_OTHER_CLASS, SwutCodec.RULE_MISMATCH,
                SwutCodec.RULE_TOO_FAST};
        assertArrayEquals(codec, rules);
    }

    @Test
    public void theExportersRefusalsBecomeTheShareFlowsVerdicts() {
        assertEquals(UsbVerdict.ASK_FIRST, RealUsbShareBackends.verdict(UsbDeviceRules.ASKS));
        for (int rule = 1; rule <= 10; rule++) {
            assertEquals(UsbVerdict.refused(rule), RealUsbShareBackends.verdict(UsbDeviceRules.refusal(rule)));
        }
    }

    // However connect() goes, the copy of the pairing read for it doesn't keep its key
    @Test
    public void thePairingsKeyIsWipedEvenWhenConnectFails() throws Exception {
        RealUsbShareBackends backends = backends();
        UsbShareTunnel tunnel = backends.tunnel(context, new NoExporter(), new NoListener());
        assertThrows(IllegalArgumentException.class, () -> tunnel.connect(new UsbShareTunnel.Target(null, "pc-uuid")));
        assertEquals(1, pairings.size());
        assertArrayEquals(new byte[32], pairings.get(0).key);
    }

    @Test
    public void nothingConnectsBeforeTheDeviceIsExported() throws Exception {
        UsbShareExporter share = backends().exporter(context, () -> {
        });
        assertThrows(IOException.class, () -> share.connect(1));
        // And stopping a share that never started gives nothing back
        share.stop();
        assertEquals(new ArrayList<String>(), exporter.calls());
    }

    // One exporter serves every share: a share that's over can't give back the next one's
    // device, and only the share holding a device hears that it was unplugged
    @Test
    public void aShareGivesBackAndHearsAboutOnlyItsOwnDevice() throws Exception {
        RealUsbShareBackends backends = backends();
        UsbDevice pad = AndroidUsbDevicesTest.pad(PAD, "Test pad");
        AtomicInteger firstHeard = new AtomicInteger();
        AtomicInteger secondHeard = new AtomicInteger();

        UsbShareExporter first = backends.exporter(context, firstHeard::incrementAndGet);
        assertArrayEquals(HelperOnLoopback.vectorShare(), first.start(pad, false).shareFrame());
        first.stop();
        UsbShareExporter second = backends.exporter(context, secondHeard::incrementAndGet);
        second.start(pad, false);
        List<String> held = exporter.calls();
        assertEquals(Arrays.asList("speed", "report 0", "watch on", "start", "localPort", "bind",
                "unbind 1-1", "stop", "close " + PAD, "watch off",
                "speed", "report 0", "watch on", "start", "localPort", "bind"), held);

        // A late stop() from the first share, which is long over
        first.stop();
        assertEquals(held, exporter.calls());

        exporter.unplug(PAD);
        assertEquals(0, firstHeard.get());
        assertEquals(1, secondHeard.get());
        // It's back already, so the second share's own stop() has nothing more to give back
        second.stop();
        List<String> after = exporter.calls();
        assertEquals(Arrays.asList("unbind 1-1", "stop", "close " + PAD, "watch off"),
                after.subList(held.size(), after.size()));
        assertThrows(IllegalStateException.class, () -> second.connect(1));
    }

    private static final class NoExporter implements UsbShareTunnel.Exporter {
        @Override
        public byte[] start() throws IOException {
            throw new IOException("not in this test");
        }

        @Override
        public Socket connect(int timeoutMs) throws IOException {
            throw new IOException("not in this test");
        }

        @Override
        public void stop() {
        }
    }

    private static final class NoListener implements UsbShareTunnel.Listener {
        @Override
        public void onSharing() {
        }

        @Override
        public void onEnded(UsbShareTunnel.Ending ending) {
            assertNotNull(ending);
        }
    }
}
