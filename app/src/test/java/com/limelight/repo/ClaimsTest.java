package com.limelight.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claims in the README, the changelog, the roadmap, the release notes and the app's own text that
 * no test or measurement backed, reworded after the 0.2.0 code review. These checks keep them from
 * coming back. Once something really is measured or tested, change the wording and this test
 * together.
 */
public class ClaimsTest {
    private static final String[][] UNBACKED = {
            // "not listed with the speed features" says the opposite, so it doesn't count
            {"(?i)turnip[^.\\n]*(faster|(?<!not listed with the )speed)", "the custom Turnip driver hasn't been measured"},
            {"(?i)(faster|speeds? up)[^.\\n]*turnip", "the custom Turnip driver hasn't been measured"},
            {"(?i)speed up vulkan", "the custom Turnip driver hasn't been measured"},
            {"(?i)faster video", "the video options haven't been measured in Starwire"},
            {"(?i)\\bfast video\\b", "nothing has measured the video pipeline in Starwire"},
            {"(?i)every millisecond counts", "the video options haven't been measured in Starwire"},
            {"(?i)every feature is tested", "not every feature has been tried on real hardware"},
            {"(?i)fit every android device", "only narrow screens and foldable sizes are tested"},
            {"(?i)devices of every size", "only narrow screens and foldable sizes are tested"},
            {"(?i)(apollo|sunshine).*\\bworks too\\b", "only Vibepollo has been tried as a host"},
            {"(?i)rebuilds libadrenotools from source", "the libraries are prebuilt"},
            {"(?i)if the driver doesn.{1,2}t work, the system one is used", "a driver that crashes closes Starwire"},
            {"(?i)local network only", "the 0.2.0 microphone isn't protected by being on the local network"},
            {"(?i)(closed|destroyed)[^.\\n]*\\bcould crash", "no test has shown a late dialog crashing Starwire"},
    };

    private static List<String> textFiles() {
        List<String> files = new ArrayList<>(Arrays.asList("README.md", "CHANGELOG.md", "docs/ROADMAP.md",
                "app/src/main/res/values/strings.xml"));
        File[] notes = RepoFiles.file(".github/release-notes").listFiles((d, name) -> name.endsWith(".md"));
        assertNotNull(notes);
        for (File note : notes) {
            files.add(".github/release-notes/" + note.getName());
        }
        return files;
    }

    private static String string(String strings, String name) {
        Matcher m = Pattern.compile("<string name=\"" + name + "\">(.*?)</string>", Pattern.DOTALL).matcher(strings);
        assertTrue(name + " not in strings.xml", m.find());
        return m.group(1);
    }

    // Why each unbacked claim in the line is one, or nothing when it makes none
    private static List<String> claimsIn(String line) {
        List<String> reasons = new ArrayList<>();
        for (String[] claim : UNBACKED) {
            if (Pattern.compile(claim[0]).matcher(line).find()) {
                reasons.add(claim[1]);
            }
        }
        return reasons;
    }

    @Test
    public void noClaimsThatNothingBacks() throws Exception {
        // The 0.2.1 notes say the driver is no longer listed with the speed features. That line
        // mustn't count as the claim it takes back, and the claim itself still must.
        assertEquals(0, claimsIn("- **Clearer about what's experimental:** the custom Turnip driver is now"
                + " described as an experimental option, not listed with the speed features.").size());
        assertEquals(1, claimsIn("The custom Turnip driver is listed with the speed features.").size());
        assertEquals(1, claimsIn("A custom Turnip driver for more speed.").size());
        assertEquals(1, claimsIn("Load a Turnip driver for faster PyroWave.").size());
        // The late-dialog fix is tested on the JVM only, so nothing has shown the crash it'd stop
        assertEquals(1, claimsIn("  already closed, which could crash the app.").size());

        List<String> found = new ArrayList<>();
        for (String path : textFiles()) {
            String[] lines = RepoFiles.read(path).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (String reason : claimsIn(lines[i])) {
                    found.add(path + ":" + (i + 1) + " (" + reason + "): " + lines[i].trim());
                }
            }
        }
        assertTrue("claims with nothing behind them:\n" + String.join("\n", found), found.isEmpty());
    }

    // ROADMAP.md says the driver hasn't been tried with a real Turnip build; everything else
    // that describes it has to agree
    @Test
    public void theCustomDriverIsDescribedAsUntried() throws Exception {
        String untried = "not yet tried with a real turnip driver";
        assertTrue(RepoFiles.read("docs/ROADMAP.md").toLowerCase(Locale.ROOT).contains(untried));

        String readme = RepoFiles.read("README.md");
        Matcher row = Pattern.compile("(?m)^\\| Custom Vulkan driver \\(Turnip\\).*$").matcher(readme);
        assertTrue("the README's custom driver row", row.find());
        assertTrue(row.group(), row.group().toLowerCase(Locale.ROOT).contains("experimental"));
        assertTrue(row.group(), row.group().toLowerCase(Locale.ROOT).contains(untried));

        String strings = RepoFiles.read("app/src/main/res/values/strings.xml");
        assertTrue(string(strings, "title_custom_vulkan_driver").toLowerCase(Locale.ROOT).contains("experimental"));
        assertTrue(string(strings, "summary_custom_vulkan_driver").toLowerCase(Locale.ROOT).contains(untried));
        assertTrue(string(strings, "impact_custom_vulkan_driver").toLowerCase(Locale.ROOT).contains(untried));

        // The warning before it's turned on: what it is, who to trust, and what the fallback
        // doesn't cover
        String warning = string(strings, "custom_vulkan_driver_warning_message");
        assertTrue(warning, warning.contains("Only use a driver file from a source you trust"));
        assertTrue(warning, warning.contains("goes back to the normal driver"));
        assertTrue(warning, warning.contains("A driver that crashes closes Starwire"));
        assertTrue(warning, warning.contains("hasn\\'t been tried with a real Turnip driver"));
    }

    // Phrases that only made sense while the 0.2.0 microphone, with no encryption, was the current
    // one. Each is worded as the old docs had it, so an unrelated known issue that says "until the
    // fix ships" doesn't trip a microphone test.
    private static final String[] OLD_MIC_WARNINGS = {
            "In 0.2.0 the microphone audio isn't encrypted",
            "Until the fix ships: - Use it only on a network you trust",
            "Until the fix ships: - Use the microphone only on a network you trust",
            "I'm working on a fix. [SECURITY.md](SECURITY.md) has the details",
            "I'm working on a fix, [coming in 0.2.1]",
            "(`SECURITY.md` has the details); a fix is being worked on",
            "it isn't secure yet",
            "Not encrypted or authenticated in 0.2.0, so only for networks you trust",
            "Local network only, but not secure",
            "Known issue: the 0.2.0 microphone",
            "A VBAN receiver on the PC (any host)",
            "TODO (encrypted mic)",
    };

    // Line breaks and indents don't matter to a phrase
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    // From 0.2.1 the microphone is encrypted and only plays from paired devices. The docs describe
    // that as how it works now, name the buttons the way the app and the receiver spell them, and
    // no longer warn as if the 0.2.0 microphone were current. Old release notes never change.
    @Test
    public void theDocsDescribeTheEncryptedMicrophone() throws Exception {
        String readme = flat(RepoFiles.read("README.md"));
        assertTrue("the README's Please read this doesn't say the mic is encrypted and paired",
                readme.contains("**Please read this before you use it.** From 0.2.1 the microphone is"
                + " encrypted, and the receiver only plays audio from devices you've paired with that PC."));
        assertTrue("the README doesn't describe pairing as how the mic works now",
                readme.contains("**Encrypted, and only from paired devices.** You pair each Android device"
                + " with the PC once"));
        Matcher row = Pattern.compile("(?m)^\\| Send your microphone to the PC with any host.*$")
                .matcher(RepoFiles.read("README.md"));
        assertTrue("the README's microphone row", row.find());
        assertTrue(row.group(), row.group().contains("Encrypted from 0.2.1, and only from devices you've paired"
                + " with the PC"));

        String guide = flat(RepoFiles.read("windows/README.md"));
        assertTrue("the guide's Please read first doesn't say the mic is encrypted and paired",
                guide.contains("From 0.2.1 the audio is encrypted on the way, and the receiver only plays it"
                + " from devices you've paired with that PC."));
        // Pairing and updating are part of the guide now, before its details, not a section after them
        List<String> sections = new ArrayList<>();
        Matcher heading = Pattern.compile("(?m)^## (.*)$").matcher(RepoFiles.read("windows/README.md"));
        while (heading.find()) {
            sections.add(heading.group(1).trim());
        }
        int details = sections.indexOf("Details and fixes");
        int pairing = sections.indexOf("Pair Starwire with the PC (once)");
        int updating = sections.indexOf("Updating from Starwire 0.2.0");
        assertTrue(sections.toString(), details >= 0 && pairing >= 0 && updating >= 0);
        assertTrue(sections.toString(), pairing < details && updating < details);

        // Each old warning is gone from the docs that describe the microphone as it is now. The
        // custom resolutions row keeps its own "Coming in 0.2.1", so only the microphone's counts.
        for (String path : new String[] {"README.md", "windows/README.md", "SECURITY.md", "docs/ROADMAP.md"}) {
            String text = flat(RepoFiles.read(path));
            for (String old : OLD_MIC_WARNINGS) {
                assertFalse(path + " still says: " + old, text.contains(old));
            }
            assertFalse(path + " still has the encrypted mic as coming",
                    Pattern.compile("(?i)coming in 0\\.2\\.1: (the microphone )?encrypted").matcher(text).find());
        }

        // The buttons the docs name are the ones the app and the receiver show
        for (String doc : new String[] {readme, guide}) {
            for (String label : new String[] {"Pair with the PC", "Pair a device...", "Show the code", "Not encrypted"}) {
                assertTrue(label, doc.contains(label));
            }
        }
        String strings = RepoFiles.read("app/src/main/res/values/strings.xml");
        assertEquals("Pair with the PC", string(strings, "title_mic_pairing"));
        assertEquals("Send my microphone", string(strings, "title_checkbox_enable_microphone"));
        assertTrue("the app's VBAN option isn't labelled Not encrypted",
                string(strings, "microphone_target_vban").startsWith("Not encrypted"));
        String receiver = RepoFiles.read("windows/StarwireMicReceiver.cs");
        assertTrue("the receiver's menu has no Pair a device...",
                receiver.contains("menu.Items.Add(\"Pair a device...\""));
        assertTrue("the receiver's pairing window has no Show the code",
                receiver.contains("showCode.Text = \"Show the code\";"));

        // What 0.2.0 shipped with stays in its release notes
        assertTrue("the 0.2.0 release notes no longer say the audio isn't encrypted",
                RepoFiles.read(".github/release-notes/v0.2.0-notes.md").contains("The audio isn't encrypted"));
    }

    // Two pictures in the README still show 0.2.0's unencrypted path and its receiver's rules, so
    // their alt text and captions say they're 0.2.0's
    @Test
    public void theOldMicrophonePicturesSayTheyreFrom020() throws Exception {
        String raw = RepoFiles.read("README.md");
        for (String picture : new String[] {"mic-signal-path.png", "mic-packet.png"}) {
            Matcher img = Pattern.compile("<img src=\"docs/images/" + Pattern.quote(picture)
                    + "\"[^>]*alt=\"([^\"]*)\"").matcher(raw);
            assertTrue(picture + " isn't in the README", img.find());
            assertTrue(picture + "'s alt text doesn't say it's from 0.2.0", img.group(1).contains("0.2.0"));
        }
        String readme = flat(raw);
        assertTrue("the microphone path picture has no caption saying it's 0.2.0's",
                readme.contains("<sub>This is 0.2.0's path. From 0.2.1 each VBAN packet travels encrypted"));
        assertTrue("the packet picture's caption doesn't say its receiver rules are 0.2.0's",
                readme.contains("<sub>The packet, the receiver's rules and the chart are from 0.2.0"));
    }

    // Plain VBAN has two separate risks: anyone can listen whenever the device sends "Not
    // encrypted", and anyone can send sound into the PC whenever Starwire's receiver is set up to
    // play plain VBAN, whichever option the device uses. The firewall rule covers every type of
    // network, so SECURITY.md says that too.
    @Test
    public void theUnencryptedRisksAreKeptApart() throws Exception {
        String security = flat(RepoFiles.read("SECURITY.md"));
        assertTrue("SECURITY.md doesn't say Not encrypted can be listened to",
                security.contains("sends plain VBAN, so anyone on the same network can listen to the microphone."));
        assertTrue("SECURITY.md ties sound sent into the PC to the device's option",
                security.contains("whichever option your device uses"));
        String setup = RepoFiles.read("windows/setup-microphone.ps1");
        assertTrue("the setup's firewall rule no longer applies on every network type", setup.contains("-Profile Any"));
        assertTrue("SECURITY.md doesn't say the firewall rule applies on public Wi-Fi",
                security.contains("firewall rule applies on every type of network Windows knows about, public Wi-Fi included"));

        String allow = "Starwire's receiver only plays plain VBAN if you set it up with `-AllowUnencrypted`";
        assertTrue("the setup has no -AllowUnencrypted", setup.contains("[switch]$AllowUnencrypted"));
        assertTrue("the receiver has no --allow-unencrypted",
                RepoFiles.read("windows/StarwireMicReceiver.cs").contains("--allow-unencrypted"));
        for (String path : new String[] {"README.md", "SECURITY.md"}) {
            assertTrue(path + " doesn't say when the receiver plays plain VBAN", flat(RepoFiles.read(path)).contains(allow));
        }
    }

    // The guide's upload figure is worked out from the protocol's packet size, not measured, and
    // says so
    @Test
    public void theEncryptedUploadFigureIsWorkedOut() throws Exception {
        assertTrue("mic-protocol.md no longer gives the 584-byte packet",
                RepoFiles.read("docs/dev/mic-protocol.md").contains("584 for Starwire's 540-byte packets"));
        double mbps = (584 + 8 + 20) * 187.5 * 8 / 1e6; // with UDP and IPv4 headers, 187.5 a second
        String guide = flat(RepoFiles.read("windows/README.md"));
        String figure = String.format(Locale.ROOT, "**About %.2f Mbps**", mbps);
        assertTrue("the guide doesn't give " + figure, guide.contains(figure));
        assertTrue("the guide doesn't say the figure isn't measured", guide.contains("That's worked out, not measured yet."));
    }
}
