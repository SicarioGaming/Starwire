package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Whether the wordmark's letters fit the top row's height, in plain numbers. GameScreenHeaderTest
 * fits the real wordmark, but with the font the tests have, its letters never spill over the
 * bottom of the row without spilling over the top too, so only these show the bottom can decide.
 */
public class WordmarkRowTest {
    // The 44 dp row at xhdpi, in pixels
    private static final int ROW = 88;

    @Test
    public void lettersInsideTheRowFit() {
        assertTrue(WordmarkRow.insideRow(ROW, 10, 70));
        // Right up to both edges is still inside
        assertTrue(WordmarkRow.insideRow(ROW, 0, ROW));
    }

    @Test
    public void lettersOverTheTopDontFit() {
        assertFalse(WordmarkRow.insideRow(ROW, -1, 70));
    }

    @Test
    public void lettersUnderTheBottomDontFit() {
        assertFalse(WordmarkRow.insideRow(ROW, 10, ROW + 1));
    }

    @Test
    public void lettersPastBothEdgesDontFit() {
        assertFalse(WordmarkRow.insideRow(ROW, -1, ROW + 1));
    }
}
