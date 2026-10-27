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
        assertEquals("cases in swut-v1-policy.txt", 103, names.size());
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

    // ---------------------------------------------------------------- Starwire's own cases
    // Where the contract leaves a choice, these pin the one Starwire makes, for the helper to
    // match. They aren't in the shared file until the contract says so.
    private static final String DEVICE = "120100020000004009120100000101020001";
    private static final String PAD_REPORT = "05010905a101150025017501951005091901291081020501150026ff0075089502"
            + "093009318102c0";

    private static String u16(int v) {
        return String.format("%02x%02x", v & 0xFF, (v >> 8) & 0xFF);
    }

    // A configuration descriptor around its body, as make-swut-v1-policy.sh writes one
    private static byte[] config(int interfaces, String body) {
        return hex("0902" + u16(9 + body.length() / 2) + String.format("%02x", interfaces) + "01008032" + body);
    }

    // A HID descriptor whose one class descriptor is a report of this length
    private static String hidDescriptor(int reportLength) {
        return "09211101000122" + u16(reportLength);
    }

    // HID interface n with one interrupt IN endpoint and a report of this length
    private static String hidInterface(int n, int reportLength) {
        return "0904" + String.format("%02x", n) + "000103000000" + hidDescriptor(reportLength) + "07058103400004";
    }

    private static String decide(String device, byte[] configuration, int[] interfaces, String... reports) {
        byte[][] r = new byte[reports.length][];
        for (int i = 0; i < reports.length; i++) {
            r[i] = hex(reports[i]);
        }
        return UsbDeviceRules.decide(hex(device), configuration, interfaces, r).toString();
    }

    @Test
    public void theHelpersMatchTheSharedPadCase() throws Exception {
        SwutCodec.Share pad = share(cases(), "pad");
        assertArrayEquals(pad.deviceDescriptor, hex(DEVICE));
        assertArrayEquals(pad.configuration, config(1, hidInterface(0, 40)));
        assertArrayEquals(pad.reports[0], hex(PAD_REPORT));
    }

    // An endpoint belongs to the interface before it. One before the first interface, or right
    // after an IAD, belongs to none, so it doesn't read
    @Test
    public void anEndpointOutsideAnInterfaceDoesntRead() throws Exception {
        assertEquals("refuse 7", decide(DEVICE, config(1, "07058203400004" + hidInterface(0, 40)), new int[] {0},
                PAD_REPORT));
        String grouped = "12010002ef02014009120100000101020001";    // EF/02/01, interfaces grouped by IADs
        assertEquals("allow", decide(grouped, config(1, "080b000103000000" + hidInterface(0, 40)), new int[] {0},
                PAD_REPORT));
        assertEquals("refuse 7", decide(grouped, config(1, "080b000103000000" + "07058203400004" + hidInterface(0, 40)),
                new int[] {0}, PAD_REPORT));
    }

    // bNumDescriptors says how many class descriptors follow; a HID descriptor too short to hold
    // them all doesn't read
    @Test
    public void aHidDescriptorTooShortForItsClassDescriptorsDoesntRead() throws Exception {
        String iface = "090400000103000000" + "092111010002222800" + "07058103400004";
        assertEquals("refuse 7", decide(DEVICE, config(1, iface), new int[] {0}, PAD_REPORT));
    }

    // As in Linux and Windows' HID drivers, the first HID descriptor after an interface is the one
    @Test
    public void onlyTheFirstHidDescriptorOfASettingCounts() throws Exception {
        String iface = "090400000103000000" + hidDescriptor(40) + hidDescriptor(41) + "07058103400004";
        assertEquals("allow", decide(DEVICE, config(1, iface), new int[] {0}, PAD_REPORT));
        assertEquals("refuse 7", decide(DEVICE, config(1, iface), new int[] {0}, PAD_REPORT + "00"));
    }

    // Endpoint addresses are compared whole, reserved bits and all, so 0x81 and 0x91 are two
    @Test
    public void endpointAddressesAreComparedWhole() throws Exception {
        String midi = "090400000201030000" + "07058102400000" + "07059102400000";
        assertEquals("allow", decide(DEVICE, config(1, midi), new int[0]));
    }

    // The reports are read in their interfaces' order, each one whole before its collections
    // count, so the first report that refuses names the rule
    @Test
    public void theFirstReportThatRefusesNamesTheRule() throws Exception {
        String led = "05080901a10115002501750195088102c0";            // the LED page, 17 bytes: rule 8
        String longItem = "fe0000" + PAD_REPORT;                      // a long item, 43 bytes: rule 7
        byte[] ledFirst = config(2, hidInterface(0, 17) + hidInterface(1, 43));
        assertEquals("refuse 8", decide(DEVICE, ledFirst, new int[] {0, 1}, led, longItem));
        byte[] longFirst = config(2, hidInterface(0, 43) + hidInterface(1, 17));
        assertEquals("refuse 7", decide(DEVICE, longFirst, new int[] {0, 1}, longItem, led));
    }

    // SHARE carries report descriptors of at most 4096 bytes, so a longer one is refused before
    // Starwire reads it
    @Test
    public void aReportLongerThanShareCarriesIsRefusedBeforeItsRead() throws Exception {
        byte[] configuration = config(1, hidInterface(0, SwutCodec.MAX_REPORT + 1));
        assertEquals("refuse 7", UsbDeviceRules.plan(hex(DEVICE), configuration).refusal.toString());
        byte[] longest = config(1, hidInterface(0, SwutCodec.MAX_REPORT));
        assertArrayEquals(new int[] {SwutCodec.MAX_REPORT}, UsbDeviceRules.plan(hex(DEVICE), longest).lengths);
    }

    @Test
    public void topLevelCollectionsCarryTheirPageAndUsage() throws Exception {
        // Game Pad, then Keyboard
        int[] found = UsbDeviceRules.topLevelCollections(hex("05010905a10115002501750195018102c0"
                + "05010906a10115002501750195018102c0"));
        assertArrayEquals(new int[] {0x00010005, 0x00010006}, found);
    }
}
