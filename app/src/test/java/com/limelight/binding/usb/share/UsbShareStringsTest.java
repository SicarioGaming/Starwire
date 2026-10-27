package com.limelight.binding.usb.share;

import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The share flow's strings keep to names of their own, usb_share_, so they never meet the
 * tunnel's (usb_tunnel_, usb_rule_) or the exporter's when the lanes merge. Git merges two lanes'
 * new lines in strings.xml without a conflict, and then the build fails on a name both define:
 * the review found eight of them with feature/usb-tunnel.
 */
public class UsbShareStringsTest {
    private static final String STRINGS = "app/src/main/res/values/strings.xml";
    private static final String SECTION = "<!-- Starwire: sharing a wired USB device with the PC (Experimental) -->";
    private static final String CODE = "app/src/main/java/com/limelight/binding/usb/share";
    // The menu's tile follows the menu's names; Android's "prohibited" message is the USB driver's
    private static final List<String> SHARED = Arrays.asList("overlay_menu_usb_devices", "error_usb_prohibited");

    private static boolean ours(String name) {
        return name.startsWith("usb_share_") || SHARED.contains(name);
    }

    @Test
    public void theShareFlowsStringsHaveNamesOfTheirOwn() throws Exception {
        String strings = RepoFiles.read(STRINGS);
        int start = strings.indexOf(SECTION);
        assertTrue("no " + SECTION + " in " + STRINGS, start >= 0);
        int end = strings.indexOf("<!--", start + SECTION.length());
        Matcher m = Pattern.compile("<string name=\"([^\"]+)\"").matcher(strings.substring(start, end));
        List<String> names = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
            if (!ours(m.group(1))) {
                wrong.add(m.group(1));
            }
        }
        assertTrue("only " + names.size() + " strings found", names.size() > 20);
        assertTrue("name these usb_share_: " + wrong, wrong.isEmpty());
    }

    @Test
    public void theShareFlowUsesOnlyItsOwnStrings() throws Exception {
        File[] files = RepoFiles.file(CODE).listFiles((dir, name) -> name.endsWith(".java"));
        assertTrue(files != null && files.length > 5);
        List<String> wrong = new ArrayList<>();
        for (File f : files) {
            Matcher m = Pattern.compile("R\\.string\\.(\\w+)").matcher(RepoFiles.read(CODE + "/" + f.getName()));
            while (m.find()) {
                if (!ours(m.group(1))) {
                    wrong.add(f.getName() + ": " + m.group(1));
                }
            }
        }
        // The tunnel's words come through its endings' message ids, never by name
        assertTrue(String.join("\n", wrong), wrong.isEmpty());
    }
}
