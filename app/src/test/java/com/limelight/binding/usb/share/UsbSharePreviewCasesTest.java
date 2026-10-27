package com.limelight.binding.usb.share;

import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The preview against the contract's own cases (swut-v1-policy.txt, from the tunnel's branch):
 * each case's SHARE is read back into what Android would show of that device, and the preview
 * must never be stricter than the rules, nor name another rule. It may be less strict where
 * Android doesn't show what decides (IADs, descriptor lengths, report descriptors), since the
 * rules run again on all the descriptors when you share.
 *
 * Skipped until the cases file is on this branch.
 */
public class UsbSharePreviewCasesTest {
    static final String CASES = "app/src/test/resources/swut-v1-policy.txt";

    @Test
    public void thePreviewIsNeverStricterThanTheRules() throws Exception {
        File file = RepoFiles.file(CASES);
        Assume.assumeTrue(CASES + " isn't on this branch yet", file.isFile());

        Map<String, String> shares = new LinkedHashMap<>();
        Map<String, String> wants = new LinkedHashMap<>();
        for (String line : RepoFiles.read(CASES).split("\n")) {
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1).trim();
            if (key.endsWith(".share")) {
                shares.put(key.substring(0, key.length() - 6), value);
            }
            else if (key.endsWith(".want")) {
                wants.put(key.substring(0, key.length() - 5), value);
            }
        }
        assertTrue("no cases in " + CASES, shares.size() > 10);

        List<String> wrong = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<String, String> c : shares.entrySet()) {
            UsbDeviceSummary device = fromShare(hex(c.getValue()));
            if (device == null) {
                // Descriptors that don't read: Android shows such a device its own way, or not at all
                continue;
            }
            compared++;
            UsbVerdict preview = UsbSharePreview.judge(device);
            String want = wants.get(c.getKey());
            boolean ok;
            if (want.equals("allow")) {
                ok = preview.decision == UsbVerdict.ALLOWED;
            }
            else if (want.equals("ask")) {
                ok = preview.decision == UsbVerdict.ASK || (preview.decision == UsbVerdict.ALLOWED && !preview.complete);
            }
            else {
                int rule = Integer.parseInt(want.substring("refuse ".length()));
                ok = preview.isRefused() ? preview.rule == rule : (!preview.complete || rule == UsbVerdict.RULE_UNREADABLE);
            }
            if (!ok) {
                wrong.add(c.getKey() + ": the rules say " + want + ", the preview " + preview);
            }
        }
        assertTrue("compared only " + compared + " cases", compared > 10);
        assertTrue(String.join("\n", wrong), wrong.isEmpty());
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private static int u8(byte[] b, int at) {
        return b[at] & 0xFF;
    }

    private static int u16(byte[] b, int at) {
        return u8(b, at) | u8(b, at + 1) << 8;
    }

    // SHARE (docs/dev/usb-protocol.md, "SHARE and SHARE_ACK") back into what Android shows of
    // the device, or null if its descriptors don't read
    static UsbDeviceSummary fromShare(byte[] f) {
        int at = 5;
        at += 1 + u8(f, at);            // bus id
        at += 1 + u8(f, at);            // name
        int dev = at;
        at += 18;
        int length = u16(f, at);
        int config = at + 2;
        if (config + length > f.length || u8(f, dev) != 18 || u8(f, dev + 1) != 1) {
            return null;
        }
        List<UsbDeviceSummary.Interface> interfaces = new ArrayList<>();
        int[] current = null;
        List<int[]> endpoints = null;
        for (int d = config; d < config + length; ) {
            int len = u8(f, d);
            if (len < 2 || d + len > config + length) {
                return null;
            }
            int type = u8(f, d + 1);
            if (type == 4 && len >= 9) {
                if (current != null) {
                    interfaces.add(new UsbDeviceSummary.Interface(current[0], current[1], current[2], current[3],
                            current[4], endpoints));
                }
                current = new int[] {u8(f, d + 2), u8(f, d + 3), u8(f, d + 5), u8(f, d + 6), u8(f, d + 7)};
                endpoints = new ArrayList<>();
            }
            else if (type == 5 && len >= 7 && endpoints != null) {
                endpoints.add(new int[] {u8(f, d + 2), u8(f, d + 3)});
            }
            d += len;
        }
        if (current != null) {
            interfaces.add(new UsbDeviceSummary.Interface(current[0], current[1], current[2], current[3], current[4],
                    endpoints));
        }
        return new UsbDeviceSummary("case", null, u16(f, dev + 8), u16(f, dev + 10), u8(f, dev + 4), u8(f, dev + 5),
                u8(f, dev + 6), u8(f, dev + 17), interfaces);
    }
}
