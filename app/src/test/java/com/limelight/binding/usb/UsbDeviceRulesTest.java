package com.limelight.binding.usb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.binding.usb.tunnel.SwutCodec;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starwire's device rules against the shared cases (swut-v1-policy.txt, the same file the PC
 * helper's rules are tested against): every case gets the decision the file wants, and the checks
 * before the report descriptors never let through a device the whole rules refuse for something
 * other than its reports.
 */
public class UsbDeviceRulesTest {
    private static Map<String, String> cases() throws IOException {
        try (InputStream in = UsbDeviceRulesTest.class.getClassLoader().getResourceAsStream("swut-v1-policy.txt")) {
            assertNotNull("swut-v1-policy.txt isn't on the test classpath", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            Map<String, String> lines = new LinkedHashMap<>();
            for (String line : new String(out.toByteArray(), StandardCharsets.US_ASCII).split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    lines.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            return lines;
        }
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private static SwutCodec.Share share(Map<String, String> lines, String name) {
        SwutCodec.Share share = SwutCodec.parseShare(hex(lines.get(name + ".share")));
        assertNotNull(name, share);
        return share;
    }

    private static List<String> names(Map<String, String> lines) {
        List<String> names = new ArrayList<>();
        for (String key : lines.keySet()) {
            if (key.endsWith(".share")) {
                names.add(key.substring(0, key.length() - ".share".length()));
            }
        }
        return names;
    }

    @Test
    public void everySharedCaseGetsTheDecisionTheFileWants() throws Exception {
        Map<String, String> lines = cases();
        List<String> wrong = new ArrayList<>();
        List<String> names = names(lines);
        for (String name : names) {
            String got = UsbDeviceRules.decide(share(lines, name)).toString();
            if (!got.equals(lines.get(name + ".want"))) {
                wrong.add(name + ": " + got + ", wants " + lines.get(name + ".want"));
            }
        }
        assertTrue(wrong.size() + " of " + names.size() + " cases decided wrongly: " + wrong, wrong.isEmpty());
        assertEquals("cases in swut-v1-policy.txt", 63, names.size());
    }

    // Starwire reads report descriptors only after the checks before them: a device they refuse
    // never has an interface taken from Android. They refuse what the whole rules refuse, with
    // the same rule, unless the refusal is in the reports; and for every device they let
    // through, they ask for exactly the reports SHARE carries.
    @Test
    public void theChecksBeforeTheReportsAgreeWithTheWholeRules() throws Exception {
        Map<String, String> lines = cases();
        List<String> wrong = new ArrayList<>();
        for (String name : names(lines)) {
            SwutCodec.Share share = share(lines, name);
            UsbDeviceRules.Plan plan = UsbDeviceRules.plan(share.deviceDescriptor, share.configuration);
            UsbDeviceRules.Decision whole = UsbDeviceRules.decide(share);
            if (plan.refusal != null) {
                if (!plan.refusal.toString().equals(whole.toString())) {
                    wrong.add(name + ": " + plan.refusal + " before the reports, " + whole + " in all");
                }
                continue;
            }
            boolean sameReports = java.util.Arrays.equals(plan.interfaces, share.reportInterfaces);
            for (int i = 0; sameReports && i < plan.lengths.length; i++) {
                sameReports = plan.lengths[i] == share.reports[i].length;
            }
            // Only the cases whose SHARE carries other reports than the descriptors ask for may differ
            if (!sameReports && !whole.toString().equals("refuse 7")) {
                wrong.add(name + ": asks for other reports than SHARE has, yet " + whole);
            }
        }
        assertTrue(wrong.toString(), wrong.isEmpty());
    }

    @Test
    public void aPadIsAllowedAndItsReportIsTheOneToRead() throws Exception {
        SwutCodec.Share pad = share(cases(), "pad");
        UsbDeviceRules.Plan plan = UsbDeviceRules.plan(pad.deviceDescriptor, pad.configuration);
        assertNull(plan.refusal);
        assertArrayEquals(new int[] {0}, plan.interfaces);
        assertArrayEquals(new int[] {40}, plan.lengths);
        assertEquals("allow", UsbDeviceRules.decide(pad).toString());
    }

    // Starwire asks you; only the helper refuses an unconfirmed keyboard (rule 5), so the flag
    // makes no difference to Starwire's own decision
    @Test
    public void aKeyboardAsksWhateverTheFlagSays() throws Exception {
        SwutCodec.Share keyboard = share(cases(), "report_keyboard");
        SwutCodec.Share confirmed = new SwutCodec.Share(SwutCodec.FLAG_CONFIRMED_INPUT, keyboard.busId, keyboard.name,
                keyboard.deviceDescriptor, keyboard.configuration, keyboard.reportInterfaces, keyboard.reports);
        assertEquals("ask", UsbDeviceRules.decide(keyboard).toString());
        assertEquals("ask", UsbDeviceRules.decide(confirmed).toString());
    }

    // A HID interface whose alternate settings disagree on the report's length can't be described
    @Test
    public void alternateSettingsThatDisagreeOnTheReportLengthDontRead() throws Exception {
        SwutCodec.Share pad = share(cases(), "pad");
        // The pad's configuration with a second alternate setting of interface 0, whose HID
        // descriptor gives 41 bytes
        byte[] alt = hex("090400010103000000" + "092111010001222900" + "07058103400004");
        byte[] config = new byte[pad.configuration.length + alt.length];
        System.arraycopy(pad.configuration, 0, config, 0, pad.configuration.length);
        System.arraycopy(alt, 0, config, pad.configuration.length, alt.length);
        config[2] = (byte) config.length;
        assertEquals("refuse 7", UsbDeviceRules.plan(pad.deviceDescriptor, config).refusal.toString());
        assertEquals("refuse 7", UsbDeviceRules.decide(pad.deviceDescriptor, config, pad.reportInterfaces,
                pad.reports).toString());
    }

    // Descriptors SHARE can't carry, or that end part way through, never get further
    @Test
    public void descriptorsThatDontFitAreRefusedWithRule7() throws Exception {
        SwutCodec.Share pad = share(cases(), "pad");
        assertEquals("refuse 7", UsbDeviceRules.plan(new byte[17], pad.configuration).refusal.toString());
        byte[] cut = java.util.Arrays.copyOf(pad.configuration, pad.configuration.length - 1);
        assertEquals("refuse 7", UsbDeviceRules.plan(pad.deviceDescriptor, cut).refusal.toString());
        byte[] huge = new byte[SwutCodec.MAX_CONFIGURATION + 1];
        System.arraycopy(pad.configuration, 0, huge, 0, pad.configuration.length);
        huge[2] = (byte) huge.length;
        huge[3] = (byte) (huge.length >> 8);
        assertEquals("refuse 7", UsbDeviceRules.plan(pad.deviceDescriptor, huge).refusal.toString());
    }

    @Test
    public void topLevelCollectionsCarryTheirPageAndUsage() throws Exception {
        // Game Pad, then Keyboard
        int[] found = UsbDeviceRules.topLevelCollections(hex("05010905a10115002501750195018102c0"
                + "05010906a10115002501750195018102c0"));
        assertArrayEquals(new int[] {0x00010005, 0x00010006}, found);
    }
}
