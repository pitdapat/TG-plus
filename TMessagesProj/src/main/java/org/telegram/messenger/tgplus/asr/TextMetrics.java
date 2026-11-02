package org.telegram.messenger.tgplus.asr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text comparison for the speech self-test. */
public final class TextMetrics {

    private TextMetrics() {}

    /** Lower-case words without punctuation; apostrophes inside words are kept. */
    public static List<String> words(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder word = new StringBuilder();
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || (cp == '\'' && word.length() > 0)) {
                word.appendCodePoint(cp);
            } else if (word.length() > 0) {
                out.add(trimApostrophes(word.toString()));
                word.setLength(0);
            }
        }
        if (word.length() > 0) out.add(trimApostrophes(word.toString()));
        out.removeIf(String::isEmpty);
        return out;
    }

    private static String trimApostrophes(String w) {
        int s = 0, e = w.length();
        while (s < e && w.charAt(s) == '\'') s++;
        while (e > s && w.charAt(e - 1) == '\'') e--;
        return w.substring(s, e);
    }

    /** Word error rate: (substitutions + deletions + insertions) / reference words. 0 = identical. */
    public static double wordErrorRate(String reference, String hypothesis) {
        List<String> r = words(reference), h = words(hypothesis);
        if (r.isEmpty()) return h.isEmpty() ? 0 : 1;
        int[] prev = new int[h.size() + 1], cur = new int[h.size() + 1];
        for (int j = 0; j <= h.size(); j++) prev[j] = j;
        for (int i = 1; i <= r.size(); i++) {
            cur[0] = i;
            for (int j = 1; j <= h.size(); j++) {
                int cost = r.get(i - 1).equals(h.get(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[h.size()] / (double) r.size();
    }
}
