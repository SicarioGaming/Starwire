package com.limelight.binding.input.dualsense;

/** Hex strings for the DualSense tests' byte vectors. */
final class Hex {
    private Hex() {
    }

    static byte[] bytes(String hex) {
        String s = hex.replace(" ", "");
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("odd length: " + hex);
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static String of(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) {
            s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return s.toString();
    }

    /** The 47-byte common block of a USB report (everything after the report ID). */
    static byte[] common(byte[] usbReport) {
        byte[] c = new byte[DualSenseOutput.COMMON_SIZE];
        System.arraycopy(usbReport, 1, c, 0, c.length);
        return c;
    }
}
