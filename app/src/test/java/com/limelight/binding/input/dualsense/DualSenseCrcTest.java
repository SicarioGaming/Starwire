package com.limelight.binding.input.dualsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.junit.Test;

/**
 * The Bluetooth output report's checksum. No published capture of a real pad's 0x31 report with
 * its checksum was found, so the full-report vectors are checked three ways: against the CRC
 * catalogue, against the seed constant two DualSense projects ship, and against a bit-by-bit CRC
 * written here that shares no code with java.util.zip.
 */
public class DualSenseCrcTest {
    // A light-bar-only report (solid red, sequence 0), from private/features/flagship-0.3.0-design-draft.md
    // (23 Oct 2026, step (a) of the build order), where an earlier research pass worked out its
    // checksum, 0x53A370D5. Recomputed for this spike with Node 24's zlib.crc32 over 0xA2 + bytes 0 to 73.
    static final String LIGHTBAR_RED_REPORT =
            "3100100004000000000000000000000000000000000000000000000000000000000000000000000000000000000000FF0000000000000000000000000000000000000000000000000000D570A353";

    @Test
    public void theUnderlyingCrcIsTheCatalogueCrc32() {
        // reveng CRC catalogue, CRC-32/ISO-HDLC: check value 0xCBF43926 for "123456789"
        // (reveng.sourceforge.io/crc-catalogue/17plus.htm). Linux's crc32_le(0xFFFFFFFF, ...)
        // followed by ~, as hid-playstation.c:1313-1314 uses it, is this same CRC.
        CRC32 crc = new CRC32();
        crc.update("123456789".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0xCBF43926L, crc.getValue());
        assertEquals(0xCBF43926, bitwiseCrc32("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void theSeedAloneMatchesDs5Dongle() {
        // DS5Dongle src/utils.h:126-128 @daa35cca starts its table CRC from 0xEADA2D49 and calls it
        // "0xA2 seed"; SAxense.c:25 @6fd1648e uses the same constant. So CRC-32 of the one byte 0xA2
        // must be 0xEADA2D49.
        assertEquals(0xEADA2D49, DualSenseCrc.outputCrc(new byte[0], 0));
        assertEquals(0xEADA2D49, bitwiseCrc32(new byte[] {(byte) 0xA2}));
    }

    @Test
    public void theLightBarReportFromTheDesignDraftChecksOut() {
        byte[] report = Hex.bytes(LIGHTBAR_RED_REPORT);
        assertEquals(DualSenseOutput.BLUETOOTH_REPORT_SIZE, report.length);
        assertEquals(0x53A370D5, DualSenseCrc.outputCrc(report, 74));
        assertEquals(0x53A370D5, bitwiseCrc32(withSeed(report, 74)));

        byte[] resigned = report.clone();
        resigned[74] = resigned[75] = resigned[76] = resigned[77] = 0;
        DualSenseCrc.sign(resigned);
        assertEquals(LIGHTBAR_RED_REPORT.toLowerCase(), Hex.of(resigned));
    }

    @Test
    public void everyByteAndTheSeedCount() {
        byte[] report = Hex.bytes(LIGHTBAR_RED_REPORT);
        int good = DualSenseCrc.outputCrc(report, 74);
        for (int i = 0; i < 74; i++) {
            byte[] changed = report.clone();
            changed[i] ^= 0x01;
            assertNotEquals("byte " + i, good, DualSenseCrc.outputCrc(changed, 74));
            assertEquals("byte " + i, bitwiseCrc32(withSeed(changed, 74)), DualSenseCrc.outputCrc(changed, 74));
        }
        CRC32 unseeded = new CRC32();
        unseeded.update(report, 0, 74);
        assertNotEquals(good, (int) unseeded.getValue());
    }

    private static byte[] withSeed(byte[] report, int length) {
        byte[] data = new byte[length + 1];
        data[0] = (byte) 0xA2;
        System.arraycopy(report, 0, data, 1, length);
        return data;
    }

    // CRC-32/ISO-HDLC one bit at a time: reflected polynomial 0xEDB88320, start and final XOR
    // 0xFFFFFFFF. The same check Nonary's moonlight-qt runs on its haptics reports
    // (tests/haptics/tst_dualsensehaptics.cpp:463-477 @d6205e1e), written here from the catalogue.
    static int bitwiseCrc32(byte[] data) {
        int crc = 0xFFFFFFFF;
        for (byte b : data) {
            crc ^= b & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xEDB88320 : crc >>> 1;
            }
        }
        return ~crc;
    }
}
