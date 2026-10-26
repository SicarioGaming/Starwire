package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * The four hidden call forms and "keep the one that worked", as Moonlight V+ does it
 * (qiin2333, GPL-3.0, AndroidBluetoothHidHostTransport.kt:112-136 and :293-298 at 5840bab3).
 */
public class HidSenderTest {
    private static final String PAD = "A0:5A:5C:00:00:01";
    private static final byte[] REPORT = Hex.bytes("3100100c40");

    @Test
    public void theOrderIsVplusOrder() {
        // V+'s DEFAULT_SEND_ORDER: raw sendData, raw setReport, hex sendData, hex setReport
        assertEquals(Arrays.asList(SendForm.SEND_DATA_BYTES, SendForm.SET_REPORT_BYTES, SendForm.SEND_DATA_HEX,
                SendForm.SET_REPORT_HEX), new HidSender().order(EnumSet.allOf(SendForm.class)));
    }

    @Test
    public void onlyTheFormsThisDeviceHasAreTried() {
        // AOSP has only the hex forms (BluetoothHidHost.sendData and setReport take a String)
        FakeHidHost host = FakeHidHost.working(PAD, "DualSense Wireless Controller");
        host.answers.remove(SendForm.SEND_DATA_BYTES);
        host.answers.remove(SendForm.SET_REPORT_BYTES);
        HidSender sender = new HidSender();
        assertTrue(sender.send(host, PAD, REPORT));
        assertEquals(1, host.calls.size());
        assertEquals(SendForm.SEND_DATA_HEX, host.calls.get(0).form);
    }

    @Test
    public void theFormThatWorkedIsTriedFirstNextTime() {
        FakeHidHost host = FakeHidHost.working(PAD, "DualSense Wireless Controller")
                .answer(FakeHidHost.Answer.FALSE, SendForm.SEND_DATA_BYTES)
                .answer(FakeHidHost.Answer.THROW, SendForm.SET_REPORT_BYTES);
        HidSender sender = new HidSender();
        assertTrue(sender.send(host, PAD, REPORT));
        assertSame(SendForm.SEND_DATA_HEX, sender.winner());
        assertEquals(3, host.calls.size());

        host.calls.clear();
        assertTrue(sender.send(host, PAD, REPORT));
        assertEquals("only the winner", 1, host.calls.size());
        assertEquals(SendForm.SEND_DATA_HEX, host.calls.get(0).form);
    }

    @Test
    public void whenTheWinnerFailsTheOthersAreTriedAgain() {
        FakeHidHost host = FakeHidHost.working(PAD, "DualSense Wireless Controller")
                .answer(FakeHidHost.Answer.FALSE, SendForm.SEND_DATA_BYTES, SendForm.SET_REPORT_BYTES);
        HidSender sender = new HidSender();
        assertTrue(sender.send(host, PAD, REPORT));
        assertSame(SendForm.SEND_DATA_HEX, sender.winner());

        host.answer(FakeHidHost.Answer.FALSE, SendForm.SEND_DATA_HEX);
        host.calls.clear();
        assertTrue(sender.send(host, PAD, REPORT));
        assertSame(SendForm.SET_REPORT_HEX, sender.winner());
        assertEquals(Arrays.asList(SendForm.SEND_DATA_HEX, SendForm.SEND_DATA_BYTES, SendForm.SET_REPORT_BYTES,
                SendForm.SET_REPORT_HEX), formsOf(host));
    }

    @Test
    public void whenEveryFormFailsNothingIsKept() {
        FakeHidHost host = FakeHidHost.working(PAD, "DualSense Wireless Controller");
        HidSender sender = new HidSender();
        assertTrue(sender.send(host, PAD, REPORT));
        host.answer(FakeHidHost.Answer.FALSE, SendForm.values());
        assertFalse(sender.send(host, PAD, REPORT));
        assertNull(sender.winner());
    }

    @Test
    public void everyFormCarriesTheWholeReport() {
        // The report ID stays in, as V+ sends it and as AOSP's btif_hh turns the hex back into bytes
        assertArrayEquals(REPORT, (byte[]) SendForm.SEND_DATA_BYTES.payload(REPORT));
        assertArrayEquals(REPORT, (byte[]) SendForm.SET_REPORT_BYTES.payload(REPORT));
        assertEquals("3100100C40", SendForm.SEND_DATA_HEX.payload(REPORT));
        assertEquals("3100100C40", SendForm.SET_REPORT_HEX.payload(REPORT));
        assertEquals("FF00A5", SendForm.hex(new byte[] {(byte) 0xFF, 0, (byte) 0xA5}));
        // A copy, so a form can't change the report another form sends next
        assertTrue(SendForm.SEND_DATA_BYTES.payload(REPORT) != REPORT);
        assertEquals(2, SendForm.REPORT_TYPE_OUTPUT);
        assertTrue(SendForm.SET_REPORT_HEX.setReport);
        assertFalse(SendForm.SEND_DATA_HEX.setReport);
    }

    private static java.util.List<SendForm> formsOf(FakeHidHost host) {
        java.util.List<SendForm> forms = new java.util.ArrayList<>();
        for (FakeHidHost.Call call : host.calls) {
            forms.add(call.form);
        }
        return forms;
    }
}
