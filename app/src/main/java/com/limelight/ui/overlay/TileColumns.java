package com.limelight.ui.overlay;

import java.util.List;

/**
 * How many tiles fit across the in-stream menu. A row of five on a phone's cover screen, or with
 * large text, broke labels mid-word ("Keyb oard"), so the grid uses fewer, wider tiles until
 * every label fits whole.
 */
final class TileColumns {
    /** The width of some text in pixels, as the tile's label would draw it. */
    interface TextWidth {
        float of(String text);
    }

    private TileColumns() {
    }

    /**
     * The most columns, from max down to min, whose tiles show every label in at most maxLines
     * lines without breaking a word. min when even that many don't fit.
     *
     * @param rowWidth width of a whole row of tiles, in pixels
     * @param gap space between two tiles
     * @param sidePadding padding inside a tile on each side of its label
     */
    static int fit(List<String> labels, TextWidth width, int rowWidth, int gap, int sidePadding,
                   int maxLines, int max, int min) {
        for (int columns = max; columns > min; columns--) {
            if (fits(labels, width, rowWidth, gap, sidePadding, maxLines, columns)) {
                return columns;
            }
        }
        return min;
    }

    /** Whether this many columns show every label in at most maxLines lines, words whole. */
    static boolean fits(List<String> labels, TextWidth width, int rowWidth, int gap, int sidePadding,
                        int maxLines, int columns) {
        // The row shares out its width like LinearLayout's weights: whole pixels, rounded down
        int text = (rowWidth - gap * (columns - 1)) / columns - 2 * sidePadding;
        return allFit(labels, width, text, maxLines);
    }

    private static boolean allFit(List<String> labels, TextWidth width, int text, int maxLines) {
        for (String label : labels) {
            if (lines(label, width, text) > maxLines) {
                return false;
            }
        }
        return true;
    }

    // Where a line may break: after a space, "-" or "/", and after any Chinese or Japanese character
    private static final String BREAKS = "(?<=[\\s/\\-\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff])";

    /**
     * Lines the label takes at this width, breaking where Android would. Integer.MAX_VALUE when
     * a word is wider than the line, so it would be broken or cut.
     */
    static int lines(String label, TextWidth width, int text) {
        int lines = 0;
        String line = "";
        // Each piece ends where a line may break: "On-screen Controls" is "On-", "screen ", "Controls"
        for (String piece : label.trim().split(BREAKS)) {
            String joined = line + piece;
            if (!line.isEmpty() && width.of(joined.trim()) <= text) {
                line = joined;
                continue;
            }
            if (width.of(piece.trim()) > text) {
                return Integer.MAX_VALUE;
            }
            lines++;
            line = piece;
        }
        return lines;
    }
}
