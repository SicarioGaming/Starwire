package com.limelight.binding.input.dualsense;

import java.util.zip.CRC32;

/**
 * The checksum at the end of a DualSense Bluetooth output report. A pad ignores a report whose
 * checksum is wrong.
 *
 * It's the ordinary CRC-32 (the one java.util.zip.CRC32 computes, CRC-32/ISO-HDLC in the CRC
 * catalogue) over one extra byte, 0xA2, followed by the report from its report ID up to the
 * checksum. 0xA2 is the Bluetooth HID header that marks an output report: the pad checks it even
 * though it isn't part of the report. The result goes in the report's last four bytes,
 * little-endian.
 *
 * Written from the description in Linux's drivers/hid/hid-playstation.c (PS_OUTPUT_CRC32_SEED and
 * dualsense_send_output_report), which matches SDL's src/joystick/hidapi/SDL_hidapi_ps5.c and
 * DS5Dongle's src/utils.h. No code was copied.
 */
public final class DualSenseCrc {
    /** The byte that goes into the checksum ahead of an output report. */
    public static final int OUTPUT_SEED = 0xA2;

    private DualSenseCrc() {
    }

    /** The checksum of 0xA2 followed by report[0] to report[length - 1]. */
    public static int outputCrc(byte[] report, int length) {
        CRC32 crc = new CRC32();
        crc.update(OUTPUT_SEED);
        crc.update(report, 0, length);
        return (int) crc.getValue();
    }

    /** Puts the checksum of everything before a report's last four bytes into those four bytes. */
    public static void sign(byte[] report) {
        int at = report.length - 4;
        int crc = outputCrc(report, at);
        report[at] = (byte) crc;
        report[at + 1] = (byte) (crc >>> 8);
        report[at + 2] = (byte) (crc >>> 16);
        report[at + 3] = (byte) (crc >>> 24);
    }
}
