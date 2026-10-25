package com.limelight.repo;

import static org.junit.Assert.assertEquals;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The README says "Coming in the next release:" for what's on main but not out yet. Releasing
 * moves the changelog's [Unreleased] entries under the new version (VersionNumbersTest asks for
 * that when versionName goes up), and from then on such a row is out of date. This fails until
 * the row is reworded, so the release can't go out with it.
 */
public class NextReleaseRowsTest {
    private static final String COMING = "Coming in the next release";

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

    // The README lines that still say "coming", when the changelog has nothing unreleased
    static List<String> staleRows(String readme, String changelog) {
        List<String> stale = new ArrayList<>();
        if (hasUnreleasedChanges(changelog)) {
            return stale;
        }
        for (String line : readme.replace("\r", "").split("\n", -1)) {
            if (line.contains(COMING)) {
                stale.add(line.length() > 80 ? line.substring(0, 80) + "..." : line);
            }
        }
        return stale;
    }

    @Test
    public void theReadmeSaysComingOnlyWhileTheChangelogHasUnreleasedChanges() throws Exception {
        assertEquals("these README rows say \"" + COMING + "\" but CHANGELOG.md's [Unreleased] is empty:"
                        + " say which release has them",
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
}
