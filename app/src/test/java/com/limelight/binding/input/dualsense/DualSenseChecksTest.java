package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothDevice;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * The checks before the hidden calls, the status names, and how a hidden method becomes a call
 * form (BluetoothHidHostLink.formOf), against fake classes shaped like BluetoothHidHost.
 */
public class DualSenseChecksTest {
    @Test
    public void checksInOrder() {
        assertSame(DualSenseStatus.OFF, DualSenseChecks.beforeConnect(false, 36, true, true));
        assertSame(DualSenseStatus.NEEDS_ANDROID_12, DualSenseChecks.beforeConnect(true, 30, true, true));
        assertSame(DualSenseStatus.NO_PERMISSION, DualSenseChecks.beforeConnect(true, 31, false, true));
        assertSame(DualSenseStatus.BLUETOOTH_OFF, DualSenseChecks.beforeConnect(true, 36, true, false));
        assertSame(DualSenseStatus.READY, DualSenseChecks.beforeConnect(true, 31, true, true));
    }

    @Test
    public void everyFailureIsAFallbackWithItsOwnText() {
        for (DualSenseStatus status : DualSenseStatus.values()) {
            boolean ok = status == DualSenseStatus.OFF || status == DualSenseStatus.CHECKING
                    || status == DualSenseStatus.READY || status == DualSenseStatus.WORKING;
            assertEquals(status.name(), !ok, status.isFallback());
            assertSame(status, DualSenseStatus.fromName(status.name()));
            for (DualSenseStatus other : DualSenseStatus.values()) {
                assertTrue(status + " and " + other + " share a text", status == other || status.message != other.message);
            }
        }
        assertNull(DualSenseStatus.fromName(null));
        assertNull(DualSenseStatus.fromName("BROKEN"));
    }

    // Shaped like AOSP's android.bluetooth.BluetoothHidHost, and like the byte[] forms V+ looks for
    @SuppressWarnings("unused")
    static final class AospShaped {
        public boolean sendData(BluetoothDevice device, String report) { return false; }
        public boolean setReport(BluetoothDevice device, byte type, String report) { return false; }
        public boolean getReport(BluetoothDevice device, byte type, byte id, int size) { return false; }
        public boolean sendData(String report) { return false; }
        public boolean setReport(BluetoothDevice device, long type, String report) { return false; }
    }

    @SuppressWarnings("unused")
    static final class BytesShaped {
        public boolean sendData(BluetoothDevice device, byte[] report) { return false; }
        public boolean setReport(BluetoothDevice device, Integer type, byte[] report) { return false; }
        public boolean sendData(BluetoothDevice device, byte[] report, int extra) { return false; }
    }

    @Test
    public void theAospMethodsAreTheHexForms() throws Exception {
        assertSame(SendForm.SEND_DATA_HEX, BluetoothHidHostLink.formOf(
                AospShaped.class.getMethod("sendData", BluetoothDevice.class, String.class)));
        assertSame(SendForm.SET_REPORT_HEX, BluetoothHidHostLink.formOf(
                AospShaped.class.getMethod("setReport", BluetoothDevice.class, byte.class, String.class)));
        assertNull(BluetoothHidHostLink.formOf(
                AospShaped.class.getMethod("getReport", BluetoothDevice.class, byte.class, byte.class, int.class)));
        assertNull("no device first", BluetoothHidHostLink.formOf(AospShaped.class.getMethod("sendData", String.class)));
        assertNull("a long isn't a report type", BluetoothHidHostLink.formOf(
                AospShaped.class.getMethod("setReport", BluetoothDevice.class, long.class, String.class)));
    }

    @Test
    public void byteArrayMethodsAreTheBytesForms() throws Exception {
        assertSame(SendForm.SEND_DATA_BYTES, BluetoothHidHostLink.formOf(
                BytesShaped.class.getMethod("sendData", BluetoothDevice.class, byte[].class)));
        assertSame(SendForm.SET_REPORT_BYTES, BluetoothHidHostLink.formOf(
                BytesShaped.class.getMethod("setReport", BluetoothDevice.class, Integer.class, byte[].class)));
        Method extra = BytesShaped.class.getMethod("sendData", BluetoothDevice.class, byte[].class, int.class);
        assertNull(BluetoothHidHostLink.formOf(extra));
        assertFalse(DualSenseChecks.MIN_SDK < 31);
    }
}
