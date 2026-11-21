package org.telegram.messenger.utils;

/** Plain-text representation of formatting, independent of Android spans. */
public final class FormattingMarkers {
    public static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, STRIKE = 8;
    private static final String[] OPEN = {"**", "*", "<u>", "~~"};
    private static final String[] CLOSE = {"**", "*", "</u>", "~~"};
    private FormattingMarkers() {}

    /**
     * Renders {@code text} with markers for the per-character {@code styles}.
     * <ul>
     * <li>Emoji and spaces inside a styled run do not split it: an unstyled emoji between two
     * bold-italic words stays inside one {@code ***...***} pair instead of producing
     * {@code ***...***🙏***...***}.</li>
     * <li>Markers sit next to text, never just inside a space ({@code **a** b}, not
     * {@code **a **b}), and no style crosses a line break, so each line parses on its own.</li>
     * <li>A style that continues is not closed and reopened: the style whose run lasts longer
     * is opened first (outermost), so {@code *ab **cd** ef*} keeps italic around bold.</li>
     * </ul>
     */
    public static String render(String text, int[] styles) {
        if (styles.length != text.length()) throw new IllegalArgumentException("Style length");
        int length = text.length();
        int[] mask = normalize(text, styles);
        StringBuilder result = new StringBuilder(length + 16);
        int[] stack = new int[4];
        int depth = 0;
        int open = 0;
        for (int offset = 0; offset <= length; offset++) {
            int current = offset == length ? 0 : mask[offset];
            if (current != open) {
                int ending = open & ~current;
                if (ending != 0) {
                    // Close down to the lowest ending style; styles above it that continue are
                    // reopened below.
                    int lowest = 0;
                    while ((ending & (1 << stack[lowest])) == 0) lowest++;
                    while (depth > lowest) {
                        int bit = stack[--depth];
                        result.append(CLOSE[bit]);
                        open &= ~(1 << bit);
                    }
                }
                int starting = current & ~open;
                while (starting != 0) {
                    int best = -1;
                    int bestRun = -1;
                    for (int bit = 0; bit < 4; bit++) {
                        if ((starting & (1 << bit)) == 0) continue;
                        int run = runLength(mask, offset, 1 << bit);
                        if (run > bestRun) {
                            best = bit;
                            bestRun = run;
                        }
                    }
                    result.append(OPEN[best]);
                    stack[depth++] = best;
                    open |= 1 << best;
                    starting &= ~(1 << best);
                }
            }
            if (offset < length) result.append(text.charAt(offset));
        }
        return result.toString();
    }

    private static int runLength(int[] mask, int from, int bit) {
        int end = from;
        while (end < mask.length && (mask[end] & bit) != 0) end++;
        return end - from;
    }

    private static int[] normalize(String text, int[] styles) {
        int length = text.length();
        int[] mask = styles.clone();
        for (int i = 0; i < length; i++) {
            if (isLineBreak(text.charAt(i))) mask[i] = 0;
        }
        // Emoji take on the styles shared by the nearest text on both sides (same line).
        for (int i = 0; i < length; i++) {
            if (!isEmojiPart(text, i)) continue;
            int left = nearest(text, mask, i, -1, true);
            int right = nearest(text, mask, i, 1, true);
            mask[i] = styles[i] | (left & right);
        }
        // Spaces carry bold/italic only between two characters that both have it, and
        // underline/strike only when they had it already, so markers wrap words, not spaces.
        int[] withEmoji = mask.clone();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (isLineBreak(c) || !Character.isWhitespace(c)) continue;
            int shared = nearest(text, withEmoji, i, -1, false) & nearest(text, withEmoji, i, 1, false);
            mask[i] = (shared & (BOLD | ITALIC)) | (styles[i] & shared & (UNDERLINE | STRIKE));
        }
        return mask;
    }

    /** Style of the closest character in {@code direction} that is not a space (or emoji). */
    private static int nearest(String text, int[] mask, int from, int direction, boolean skipEmoji) {
        for (int i = from + direction; i >= 0 && i < text.length(); i += direction) {
            char c = text.charAt(i);
            if (isLineBreak(c)) return 0;
            if (Character.isWhitespace(c)) continue;
            if (skipEmoji && isEmojiPart(text, i)) continue;
            return mask[i];
        }
        return 0;
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    /** Emoji, skin tones, variation selectors, joiners and other symbols that show no style. */
    private static boolean isEmojiPart(String text, int index) {
        char c = text.charAt(index);
        int codePoint;
        if (Character.isLowSurrogate(c) && index > 0 && Character.isHighSurrogate(text.charAt(index - 1))) {
            codePoint = Character.codePointAt(text, index - 1);
        } else {
            codePoint = Character.codePointAt(text, index);
        }
        switch (Character.getType(codePoint)) {
            case Character.OTHER_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.NON_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.FORMAT:
                return true;
            default:
                return false;
        }
    }
}
