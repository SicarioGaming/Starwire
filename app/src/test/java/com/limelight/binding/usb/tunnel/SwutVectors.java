package com.limelight.binding.usb.tunnel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The USB tunnel's shared vectors file (app/src/test/resources/swut-v1-vectors.txt, the same bytes
 * as windows/tests/vectors/swut-v1-vectors.txt) and the fixed inputs it was made from, as
 * docs/dev/usb-protocol.md lists them.
 */
final class SwutVectors {
    static final String SHA256 = "b955594038a2a423525024796861a3f43748799c8e10e4260dea9f6eae60a085";
    static final int LINES = 21;

    // The mic vectors' pairing key and device id, so K_usb and the mic's K_auth come from the same inputs
    static final byte[] PAIR_KEY = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    static final byte[] DEVICE_ID = hex("a0a1a2a3a4a5a6a7");
    static final byte[] HELPER_NONCE = hex("d0d1d2d3d4d5d6d7d8d9dadbdcdddedf");
    static final byte[] PHONE_NONCE = hex("e0e1e2e3e4e5e6e7e8e9eaebecedeeef");
    static final byte[] OTHER_PHONE_NONCE = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
    // The certificates' keys: RFC 5903 section 8.1 (256-bit random ECP group), i for the phone, r for the helper
    static final byte[] PHONE_X = hex("DAD0B65394221CF9B051E1FECA5787D098DFE637FC90B9EF945D0C3772581180");
    static final byte[] PHONE_Y = hex("5271A0461CDB8252D61F1C456FA3E59AB1F45B33ACCF5F58389E0577B8990BB3");
    static final byte[] HELPER_X = hex("D12DFB5289C8D4F81208B70270398C342296970A0BCCB74C736FC7554494BF63");
    static final byte[] HELPER_Y = hex("56FBF3CA366CC23E8157854C13C58D6AAC23F046ADA30F8353E74F33039872AB");

    // SHARE's test device: a pad with one HID interface, 1209:0001, bus id 1-1
    static final String BUS_ID = "1-1";
    static final String NAME = "Test pad";
    static final byte[] DEVICE_DESCRIPTOR = hex("120100020000004009120100000101020001");
    static final byte[] REPORT = hex("05010905a101150025017501951005091901291081020501150026ff0075089502093009318102c0");
    // Configuration (34 bytes), interface 0 (HID), HID descriptor (a 40-byte report descriptor), endpoint 0x81
    static final byte[] CONFIGURATION = hex("090222000101008032" + "090400000103000000" + "092111010001222800" + "07058103400004");

    private SwutVectors() {
    }

    static byte[] fileBytes() throws IOException {
        return fileBytes("swut-v1-vectors.txt");
    }

    /** A shared file from app/src/test/resources: these vectors, or the device rules' cases. */
    static byte[] fileBytes(String name) throws IOException {
        try (InputStream in = SwutVectors.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " isn't on the test classpath");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** name=hex lines, in the file's order. */
    static Map<String, String> read() throws IOException {
        return read("swut-v1-vectors.txt");
    }

    /** name=value lines of a shared file, in the file's order. */
    static Map<String, String> read(String file) throws IOException {
        Map<String, String> v = new LinkedHashMap<>();
        for (String line : new String(fileBytes(file), StandardCharsets.US_ASCII).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                v.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return v;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** USB/IP's OP_REQ_IMPORT for a bus id (big-endian, as USB/IP's own numbers are): 40 bytes. */
    static byte[] opReqImport(String busId) {
        byte[] b = new byte[40];
        b[0] = 0x01;
        b[1] = 0x11;   // version 0x0111
        b[2] = (byte) 0x80;
        b[3] = 0x03;   // OP_REQ_IMPORT, 0x8003
        // status 0, then the bus id padded with zeros to 32 bytes
        byte[] id = ascii(busId);
        System.arraycopy(id, 0, b, 8, id.length);
        return b;
    }
}
