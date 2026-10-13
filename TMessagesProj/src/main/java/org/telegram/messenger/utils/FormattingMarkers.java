package org.telegram.messenger.utils;

/** Plain-text representation of formatting, independent of Android spans. */
public final class FormattingMarkers {
    public static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, STRIKE = 8;
    private static final String[] OPEN = {"**", "*", "<u>", "~~"};
    private static final String[] CLOSE = {"**", "*", "</u>", "~~"};
    private FormattingMarkers() {}

    public static String render(String text, int[] styles) {
        if (styles.length != text.length()) throw new IllegalArgumentException("Style length");
        StringBuilder result = new StringBuilder(text.length());
        int[] active = new int[4];
        int[] next = new int[4];
        int count = 0;
        for (int offset = 0; offset <= text.length(); offset++) {
            int mask = offset == text.length() ? 0 : styles[offset];
            int nextCount = 0;
            for (int bit = 0; bit < 4; bit++) {
                if ((mask & (1 << bit)) != 0) next[nextCount++] = bit;
            }
            int common = 0;
            while (common < count && common < nextCount && active[common] == next[common]) common++;
            for (int i = count - 1; i >= common; i--) result.append(CLOSE[active[i]]);
            for (int i = common; i < nextCount; i++) result.append(OPEN[next[i]]);
            int[] previous = active;
            active = next;
            next = previous;
            count = nextCount;
            if (offset < text.length()) result.append(text.charAt(offset));
        }
        return result.toString();
    }
}
