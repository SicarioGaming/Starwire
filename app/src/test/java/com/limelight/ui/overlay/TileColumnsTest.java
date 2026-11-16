package com.limelight.ui.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * How many tiles the in-stream menu puts across. Every character here is 10 px wide, so a label's
 * width is easy to read off. The pictures in src/test/screenshots (streamMenu) show the real thing.
 */
public class TileColumnsTest {
    private static final TileColumns.TextWidth TEN_PX = text -> text.length() * 10;
    private static final int GAP = 8;
    private static final int PADDING = 8;

    private static int fit(int rowWidth, String... labels) {
        return TileColumns.fit(Arrays.asList(labels), TEN_PX, rowWidth, GAP, PADDING, 2, 5, 2);
    }

    @Test
    public void fiveAcrossWhenEveryLabelFits() {
        // (800 - 4 gaps) / 5 = 153, less the padding: 137 px for "Full Keyboard" (130 px)
        assertEquals(5, fit(800, "Full Keyboard", "Stats (RB)", "Rotate"));
    }

    @Test
    public void fewerColumnsUntilTheLongestWordFits() {
        // "Sensitivity" is 110 px. Five across leave 57 px a tile, four 78, three 112.
        assertEquals(3, fit(400, "Keyboard (Y)", "Sensitivity", "Rotate"));
        // The same labels on a wider sheet go back to five
        assertEquals(5, fit(720, "Keyboard (Y)", "Sensitivity", "Rotate"));
    }

    @Test
    public void sevenAcrossFitWhenEachTileHasRoomForTheLongestWord() {
        // The stream menu's wider sheet: (1120 - 6 gaps) / 7 = 153, less the padding: 137 px,
        // enough for "Full Keyboard" (130 px) but not "Supercalifragilistic" (200 px)
        assertTrue(TileColumns.fits(Arrays.asList("Full Keyboard", "Stats (RB)", "Rotate"), TEN_PX, 1120,
                GAP, PADDING, 2, 7));
        assertFalse(TileColumns.fits(Arrays.asList("Rotate", "Supercalifragilistic"), TEN_PX, 1120,
                GAP, PADDING, 2, 7));
        // (860 - 6 gaps) / 7 = 116, less the padding: just the 100 px "Brightness" needs. A pixel
        // less and it doesn't fit.
        assertTrue(TileColumns.fits(Arrays.asList("Brightness"), TEN_PX, 860, GAP, PADDING, 2, 7));
        assertFalse(TileColumns.fits(Arrays.asList("Brightness"), TEN_PX, 859, GAP, PADDING, 2, 7));
    }

    @Test
    public void aLabelMayTakeTwoLinesButNotThree() {
        // "Send Clipboard" fits as "Send" over "Clipboard" in 90 px
        assertEquals(2, TileColumns.lines("Send Clipboard", TEN_PX, 90));
        assertEquals(3, TileColumns.lines("Aa Bb Cc", TEN_PX, 20));
        // In a 120 px row, three across leave 34 px a tile (three lines), two leave 56 (two lines)
        List<String> labels = Arrays.asList("Aa Bb Cc");
        assertEquals(2, TileColumns.fit(labels, TEN_PX, 120, GAP, 0, 2, 5, 1));
    }

    @Test
    public void linesBreakAfterHyphensAndSlashes() {
        assertEquals(2, TileColumns.lines("On-screen Controls", TEN_PX, 90));
        // "On-" and "screen" each fit 60 px, but "Controls" (80 px) would be cut
        assertEquals(Integer.MAX_VALUE, TileColumns.lines("On-screen Controls", TEN_PX, 60));
        assertEquals(2, TileColumns.lines("Pan/Zoom", TEN_PX, 40));
        assertEquals(1, TileColumns.lines("Pan/Zoom", TEN_PX, 80));
    }

    @Test
    public void chineseAndJapaneseBreakBetweenCharacters() {
        // Ten characters, 100 px, in two lines of 50 px
        assertEquals(2, TileColumns.lines("画面上のコントロール", TEN_PX, 50));
    }

    @Test
    public void theFewestColumnsWhenNothingFits() {
        assertEquals(2, fit(200, "Supercalifragilistic"));
    }

    @Test
    public void spacesAroundALabelDontCount() {
        assertEquals(1, TileColumns.lines("  Rotate  ", TEN_PX, 60));
        assertEquals(1, TileColumns.lines("", TEN_PX, 10));
    }
}
