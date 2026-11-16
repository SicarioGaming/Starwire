package com.limelight.repo;

import static org.junit.Assert.assertEquals;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The README says "Coming in the next release:" for what's on main but not out yet, or "Coming
 * in 0.2.1" once the version is known. Releasing moves the changelog's [Unreleased] entries under
 * the new version (VersionNumbersTest asks for that when versionName goes up), and from then on
 * such a row is out of date. This fails until the row is reworded, so the release can't go out
 * with it.
 */
public class NextReleaseRowsTest {
    private static final String COMING = "Coming in the next release";
    // "coming in 0.2.1", in any case, with or without a v
    private static final Pattern COMING_IN_VERSION = Pattern.compile("(?i)\\bcoming in v?(\\d+\\.\\d+\\.\\d+)\\b");
    private static final Pattern VERSION_HEADING = Pattern.compile("^## \\[(\\d+\\.\\d+\\.\\d+)\\]");

    // Whether the changelog's [Unreleased] section lists anything
    static boolean hasUnreleasedChanges(String changelog) {
        boolean inUnreleased = false;
        for (String line : changelog.replace("\r", "").split("\n", -1)) {
            if (line.startsWith("## [")) {
                inUnreleased = line.equals("## [Unreleased]");
            } else if (inUnreleased && line.startsWith("- ")) {
                return true;
            }
        }
        return false;
    }

    // The versions the changelog has a section for, which are out
    static Set<String> releasedVersions(String changelog) {
        Set<String> released = new HashSet<>();
        for (String line : changelog.replace("\r", "").split("\n", -1)) {
            Matcher heading = VERSION_HEADING.matcher(line);
            if (heading.find()) {
                released.add(heading.group(1));
            }
        }
        return released;
    }

    // The README lines that still say "coming" for a release that's out: the next release when
    // the changelog has nothing unreleased, or a version the changelog has a section for. Each
    // is given by its line number and the words around the "coming".
    static List<String> staleRows(String readme, String changelog) {
        List<String> stale = new ArrayList<>();
        boolean unreleased = hasUnreleasedChanges(changelog);
        Set<String> released = releasedVersions(changelog);
        String[] lines = readme.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int at = -1;
            if (!unreleased && line.contains(COMING)) {
                at = line.indexOf(COMING);
            }
            Matcher version = COMING_IN_VERSION.matcher(line);
            while (at < 0 && version.find()) {
                if (released.contains(version.group(1))) {
                    at = version.start();
                }
            }
            if (at >= 0) {
                int from = Math.max(0, at - 30);
                int to = Math.min(line.length(), at + 60);
                stale.add("line " + (i + 1) + ": " + (from > 0 ? "..." : "") + line.substring(from, to)
                        + (to < line.length() ? "..." : ""));
            }
        }
        return stale;
    }

    @Test
    public void theReadmeSaysComingOnlyForReleasesThatArentOut() throws Exception {
        assertEquals("these README rows say a release is coming, but CHANGELOG.md has it as out:"
                        + " say which release has them, like \"From 0.2.1\"",
                new ArrayList<String>(), staleRows(RepoFiles.read("README.md"), RepoFiles.read("CHANGELOG.md")));
    }

    @Test
    public void aReleaseThatLeavesTheRowInIsCaught() {
        String readme = "| Feature | Source |\n| --- | --- |\n| " + COMING + ": a Brightness item | Moonlight X |\n";
        String before = "# Changelog\n\n## [Unreleased]\n\n### Added\n\n- **A Brightness item**\n\n"
                + "## [0.2.1] - 2026-10-23\n\n- Older\n";
        String after = "# Changelog\n\n## [Unreleased]\n\n## [0.2.2] - 2026-11-02\n\n### Added\n\n"
                + "- **A Brightness item**\n\n## [0.2.1] - 2026-10-23\n\n- Older\n";
        assertEquals(new ArrayList<String>(), staleRows(readme, before));
        assertEquals(1, staleRows(readme, after).size());
        // Reworded for the release, it passes
        assertEquals(new ArrayList<String>(), staleRows(readme.replace(COMING, "From 0.2.2"), after));
    }

    @Test
    public void aRowNamingAReleaseThatsOutIsCaught() {
        String readme = "| Feature | Source |\n| --- | --- |\n"
                + "| Coming in 0.2.1: custom resolutions | Moonlight X |\n"
                + "| Per-game settings (codec, and the jitter buffer coming in 0.2.1) | Moonlight X |\n"
                + "| A tile, COMING IN V0.2.1 | Moonlight X |\n"
                + "| A setting coming in 0.2.10 | Moonlight X |\n";
        String before = "# Changelog\n\n## [Unreleased]\n\n### Added\n\n- **Custom resolutions**\n\n"
                + "## [0.2.0] - 2026-10-01\n\n- Older\n";
        String after = "# Changelog\n\n## [Unreleased]\n\n## [0.2.1] - 2026-10-23\n\n### Added\n\n"
                + "- **Custom resolutions**\n\n## [0.2.0] - 2026-10-01\n\n- Older\n";
        assertEquals(new ArrayList<String>(), staleRows(readme, before));
        List<String> stale = staleRows(readme, after);
        assertEquals(stale.toString(), 3, stale.size());
        assertEquals("line 3: | Coming in 0.2.1: custom resolutions | Moonlight X |", stale.get(0));
        assertEquals("line 4: ...(codec, and the jitter buffer coming in 0.2.1) | Moonlight X |", stale.get(1));
        assertEquals("line 5: | A tile, COMING IN V0.2.1 | Moonlight X |", stale.get(2));
        // Reworded for the release, it passes
        String reworded = readme.replace("Coming in 0.2.1", "From 0.2.1")
                .replace("the jitter buffer coming in 0.2.1", "from 0.2.1, the jitter buffer")
                .replace("COMING IN V0.2.1", "from 0.2.1");
        assertEquals(new ArrayList<String>(), staleRows(reworded, after));
    }
}
