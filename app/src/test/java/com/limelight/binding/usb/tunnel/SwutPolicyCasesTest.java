package com.limelight.binding.usb.tunnel;

import static com.limelight.binding.usb.tunnel.SwutVectors.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The device rules' shared cases (swut-v1-policy.txt, docs/dev/usb-protocol.md, "The device rules'
 * cases"), which Starwire's device rules and the PC helper's are each to decide the same way. This
 * checks the file itself: the same bytes in both places, a well-formed SHARE in every case, a
 * decision that names a real rule, and a case for every rule a SHARE can break. No code here
 * decides the cases yet.
 */
public class SwutPolicyCasesTest {
    static final String FILE = "swut-v1-policy.txt";
    static final String SHA256 = "2ec55b710f7fb46d2be00b38103dc25f118b4c213ca14f710d68d42de4cadb4b";

    private static final Pattern WANT = Pattern.compile("allow|ask|refuse ([1-9]|10)");

    @Test
    public void theCasesFileIsTheSharedOneByteForByte() throws Exception {
        byte[] ours = SwutVectors.fileBytes(FILE);
        assertEquals(SHA256, hex(MessageDigest.getInstance("SHA-256").digest(ours)));
        byte[] helpers = Files.readAllBytes(RepoFiles.file("windows/tests/vectors/" + FILE).toPath());
        assertArrayEquals("the app's and the helper's cases differ", helpers, ours);
        assertFalse("the cases must keep LF line endings", new String(ours, "US-ASCII").contains("\r"));
        assertTrue("usb-protocol.md doesn't give the cases file's SHA-256",
                RepoFiles.read("docs/dev/usb-protocol.md").contains(SHA256.toUpperCase(Locale.ROOT)));
    }

    @Test
    public void everyCaseIsAWellFormedShareWithADecision() throws Exception {
        Map<String, String> lines = SwutVectors.read(FILE);
        Set<String> wants = new TreeSet<>();
        int cases = 0;
        for (Map.Entry<String, String> e : lines.entrySet()) {
            String key = e.getKey();
            if (key.endsWith(".want")) {
                assertTrue(key + " has no .share line", lines.containsKey(key.substring(0, key.length() - 5) + ".share"));
                continue;
            }
            assertTrue("a line that's neither .share nor .want: " + key, key.endsWith(".share"));
            String name = key.substring(0, key.length() - 6);
            cases++;
            SwutCodec.Share share = SwutCodec.parseShare(hex(e.getValue()));
            assertNotNull(name + " isn't a well-formed SHARE", share);
            assertEquals(name, 0, share.flags);
            assertEquals(name, SwutVectors.BUS_ID, share.busId);
            assertEquals(name, SwutVectors.NAME, share.nameText());
            String want = lines.get(name + ".want");
            assertNotNull(name + " has no .want line", want);
            assertTrue(name + ": " + want, WANT.matcher(want).matches());
            wants.add(want);
        }
        assertEquals("every line belongs to a case", 2 * cases, lines.size());
        // Rules 5 (no confirmation), 9 (the relay) and 10 (the speed) aren't in a SHARE's
        // descriptors, so they have no case; every other rule, and both other decisions, do
        Set<String> expected = new TreeSet<>();
        for (String w : new String[] {"allow", "ask", "refuse 1", "refuse 2", "refuse 3", "refuse 4", "refuse 6",
                "refuse 7", "refuse 8"}) {
            expected.add(w);
        }
        assertEquals(expected, wants);
    }

    @Test
    public void thePadCaseIsTheVectorsShare() throws Exception {
        assertEquals(SwutVectors.read().get("frame.share"), SwutVectors.read(FILE).get("pad.share"));
        assertEquals("allow", SwutVectors.read(FILE).get("pad.want"));
    }

    // The doc's table of rules and the codec's numbers for them are the same ten
    @Test
    public void theDocsRulesAreTheCodecs() throws Exception {
        String doc = RepoFiles.read("docs/dev/usb-protocol.md");
        int[] codec = {SwutCodec.RULE_HUB, SwutCodec.RULE_STORAGE, SwutCodec.RULE_NETWORK, SwutCodec.RULE_UNLISTED_VENDOR,
                SwutCodec.RULE_UNCONFIRMED_INPUT, SwutCodec.RULE_CONFIGURATIONS, SwutCodec.RULE_UNREADABLE,
                SwutCodec.RULE_OTHER_CLASS, SwutCodec.RULE_MISMATCH, SwutCodec.RULE_TOO_FAST};
        String[] words = {"a hub", "storage", "a network adapter", "vendor-specific", "a keyboard or mouse you didn't confirm",
                "more than one configuration", "descriptors that don't read", "a kind of device Starwire doesn't share",
                "something SHARE didn't describe", "faster than high speed"};
        Matcher row = Pattern.compile("(?m)^\\| (\\d+) \\| (.*) \\|$").matcher(
                doc.substring(doc.indexOf("| Rule | Refused because it's |")));
        for (int i = 0; i < codec.length; i++) {
            assertEquals(i + 1, codec[i]);
            assertTrue("the doc's rules table has no row " + (i + 1), row.find());
            assertEquals(String.valueOf(i + 1), row.group(1));
            assertTrue("rule " + (i + 1) + " is " + row.group(2), row.group(2).contains(words[i]));
        }
    }
}
