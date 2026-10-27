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
 * (qiin2333, GPL-3.0, AndroidBluetoothHidHostTransport.kt:112-136 and :293-298 at 5840bab3),
 * except that sendData comes first and setReport is only used where sendData is missing.
 */
public class HidSenderTest {
    private static final String PAD = "A0:5A:5C:00:00:01";
    private static final byte[] REPORT = Hex.bytes("3100100c40");

    @Test
    public void sendDataComesFirstAndSetReportOnlyWhereSendDataIsMissing() {
        // V+'s DEFAULT_SEND_ORDER is raw sendData, raw setReport, hex sendData, hex setReport.
        // Starwire keeps raw before hex, with both sendData forms first
        assertEquals(Arrays.asList(SendForm.SEND_DATA_BYTES, SendForm.SEND_DATA_HEX),
                new HidSender().order(EnumSet.allOf(SendForm.class)));
        assertEquals(Arrays.asList(SendForm.SEND_DATA_HEX),
                new HidSender().order(EnumSet.of(SendForm.SEND_DATA_HEX, SendForm.SET_REPORT_HEX)));
        assertEquals(Arrays.asList(SendForm.SET_REPORT_BYTES, SendForm.SET_REPORT_HEX),
                new HidSender().order(EnumSet.of(SendForm.SET_REPORT_BYTES, SendForm.SET_REPORT_HEX)));
    }

    @Test
    public void setReportNeverStandsInForSendData() {
        // The maintainer's tablet on 26 Oct, with a separate test app: only the hex forms are
        // there. sendData changed the pad's light bar; setReport returned true and the pad did
        // nothing. So one refused sendData is a failed send, never a switch to setReport for good.
        FakeHidHost host = FakeHidHost.working(PAD, "DualSense Wireless Controller");
        host.answers.remove(SendForm.SEND_DATA_BYTES);
        host.answers.remove(SendForm.SET_REPORT_BYTES);
        HidSender sender = new HidSender();
        assertTrue(sender.send(host, PAD, REPORT));
        assertSame(SendForm.SEND_DATA_HEX, sender.winner());

        host.answer(FakeHidHost.Answer.FALSE, SendForm.SEND_DATA_HEX);
        assertFalse("one refusal is a failed send", sender.send(host, PAD, REPORT));
        assertNull(sender.winner());

        host.answer(FakeHidHost.Answer.TRUE, SendForm.SEND_DATA_HEX);
        for (int i = 0; i < 5; i++) {
            assertTrue(sender.send(host, PAD, REPORT));
        }
        assertSame(SendForm.SEND_DATA_HEX, sender.winner());
        assertEquals(7, host.calls.size());
        for (FakeHidHost.Call call : host.calls) {
            assertFalse("setReport was called", call.form.setReport);
        }
    }

    @Test
    public void aSendDataRefusedFromTheStartIsNotSentBySetReport() {
        FakeHidHost host = FakeHidHost.working(PAD, "DualSense Wireless Controller")
                .answer(FakeHidHost.Answer.FALSE, SendForm.SEND_DATA_BYTES, SendForm.SEND_DATA_HEX);
        HidSender sender = new HidSender();
        assertFalse(sender.send(host, PAD, REPORT));
        assertEquals(Arrays.asList(SendForm.SEND_DATA_BYTES, SendForm.SEND_DATA_HEX), formsOf(host));
    }

    @Test
    public void setReportIsTheLastResortOnADeviceWithoutSendData() {
        // No such device is known. V+ tries setReport, so Starwire does too, but only there
        FakeHidHost host = FakeHidHost.working(PAD, "DualSense Wireless Controller")
                .answer(FakeHidHost.Answer.FALSE, SendForm.SET_REPORT_BYTES);
        host.answers.remove(SendForm.SEND_DATA_BYTES);
        host.answers.remove(SendForm.SEND_DATA_HEX);
        HidSender sender = new HidSender();
        assertTrue(sender.send(host, PAD, REPORT));
        assertSame(SendForm.SET_REPORT_HEX, sender.winner());
        assertEquals(Arrays.asList(SendForm.SET_REPORT_BYTES, SendForm.SET_REPORT_HEX), formsOf(host));
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
                .answer(FakeHidHost.Answer.THROW, SendForm.SEND_DATA_BYTES);
        HidSender sender = new HidSender();
        assertTrue(sender.send(host, PAD, REPORT));
        assertSame(SendForm.SEND_DATA_HEX, sender.winner());
        assertEquals(2, host.calls.size());
        assertTrue(sender.lastError() instanceof IllegalStateException);

        host.calls.clear();
        assertTrue(sender.send(host, PAD, REPORT));
        assertEquals("only the winner", 1, host.calls.size());
        assertEquals(SendForm.SEND_DATA_HEX, host.calls.get(0).form);
    }

    @Test
    public void whenTheWinnerFailsTheOtherSendDataFormIsTriedAgain() {
        FakeHidHost host = FakeHidHost.working(PAD, "DualSense Wireless Controller")
                .answer(FakeHidHost.Answer.FALSE, SendForm.SEND_DATA_BYTES);
        HidSender sender = new HidSender();
        assertTrue(sender.send(host, PAD, REPORT));
        assertSame(SendForm.SEND_DATA_HEX, sender.winner());

        host.answer(FakeHidHost.Answer.FALSE, SendForm.SEND_DATA_HEX);
        host.answer(FakeHidHost.Answer.TRUE, SendForm.SEND_DATA_BYTES);
        host.calls.clear();
        assertTrue(sender.send(host, PAD, REPORT));
        assertSame(SendForm.SEND_DATA_BYTES, sender.winner());
        assertEquals(Arrays.asList(SendForm.SEND_DATA_HEX, SendForm.SEND_DATA_BYTES), formsOf(host));
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
