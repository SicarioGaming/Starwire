package com.limelight.binding.input.dualsense;

/**
 * The four ways of handing a report to android.bluetooth.BluetoothHidHost's hidden methods, in the
 * order Starwire tries them. AOSP has the two hex String forms; some phone makers' builds may have
 * byte[] ones instead. The order and the "keep the one that worked" rule (HidSender) are ported
 * from Moonlight V+ by qiin2333 (GPL-3.0), AndroidBluetoothHidHostTransport.kt:27, :112-136 and
 * :293-298 at 5840bab3. Every form carries the whole report, report ID first.
 *
 * In AOSP, sendData writes the report on the HID interrupt channel and setReport sends a
 * SET_REPORT on the control channel (packages/modules/Bluetooth, btif_hh.cc).
 */
public enum SendForm {
    /** sendData(BluetoothDevice, byte[]) */
    SEND_DATA_BYTES(false, true),
    /** setReport(BluetoothDevice, OUTPUT, byte[]) */
    SET_REPORT_BYTES(true, true),
    /** sendData(BluetoothDevice, String), the report as hex */
    SEND_DATA_HEX(false, false),
    /** setReport(BluetoothDevice, OUTPUT, String), the report as hex */
    SET_REPORT_HEX(true, false);

    /** BluetoothHidHost.REPORT_TYPE_OUTPUT, the report type setReport takes. */
    public static final int REPORT_TYPE_OUTPUT = 2;

    /** setReport rather than sendData. */
    public final boolean setReport;
    /** Takes a byte[] rather than a hex String. */
    public final boolean bytes;

    SendForm(boolean setReport, boolean bytes) {
        this.setReport = setReport;
        this.bytes = bytes;
    }

    /** The last argument for this form: a copy of the report, or the report as upper-case hex. */
    public Object payload(byte[] report) {
        return bytes ? report.clone() : hex(report);
    }

    static String hex(byte[] report) {
        final char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[report.length * 2];
        for (int i = 0; i < report.length; i++) {
            out[2 * i] = digits[(report[i] >> 4) & 0x0F];
            out[2 * i + 1] = digits[report[i] & 0x0F];
        }
        return new String(out);
    }
}
