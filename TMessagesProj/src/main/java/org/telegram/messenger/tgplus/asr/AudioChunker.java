package org.telegram.messenger.tgplus.asr;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long audio into pieces the recognizer can take in one go. Cuts are placed at the quietest
 * 100 ms in the last few seconds of each piece, so words are rarely cut in half.
 */
public final class AudioChunker {

    public static final int MAX_CHUNK_SECONDS = 30;
    private static final int SEARCH_SECONDS = 5;
    private static final int MIN_LAST_CHUNK_MS = 1000;

    private AudioChunker() {}

    /** Returns [start, end) sample ranges that cover all of the audio, in order. */
    public static List<int[]> split(float[] samples, int sampleRate) {
        return split(samples, sampleRate, MAX_CHUNK_SECONDS);
    }

    static List<int[]> split(float[] samples, int sampleRate, int maxSeconds) {
        List<int[]> chunks = new ArrayList<>();
        int total = samples == null ? 0 : samples.length;
        if (total == 0 || sampleRate <= 0 || maxSeconds <= 0) return chunks;
        int max = maxSeconds * sampleRate;
        int search = Math.min(SEARCH_SECONDS, Math.max(1, maxSeconds / 2)) * sampleRate;
        int frame = Math.max(1, sampleRate / 10);
        int start = 0;
        while (total - start > max) {
            int limit = start + max;
            int cut = quietestPoint(samples, Math.max(start + frame, limit - search), limit, frame);
            if (cut <= start) cut = limit;
            chunks.add(new int[]{start, cut});
            start = cut;
        }
        int minLast = (int) ((long) MIN_LAST_CHUNK_MS * sampleRate / 1000);
        if (total - start < minLast && !chunks.isEmpty()) {
            chunks.get(chunks.size() - 1)[1] = total;
        } else {
            chunks.add(new int[]{start, total});
        }
        return chunks;
    }

    /** Center of the lowest-energy frame that lies fully inside [from, to). */
    private static int quietestPoint(float[] samples, int from, int to, int frame) {
        double best = Double.MAX_VALUE;
        int bestCut = -1;
        for (int begin = from; begin + frame <= to; begin += frame / 2 > 0 ? frame / 2 : 1) {
            double energy = 0;
            for (int i = begin; i < begin + frame; i++) energy += (double) samples[i] * samples[i];
            if (energy < best) {
                best = energy;
                bestCut = begin + frame / 2;
            }
        }
        return bestCut;
    }

    /** Joins the text of consecutive pieces with single spaces, skipping empty ones. */
    public static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null) continue;
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(trimmed);
        }
        return out.toString();
    }
}
