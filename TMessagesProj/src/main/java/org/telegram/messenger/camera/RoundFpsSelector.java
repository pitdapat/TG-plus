package org.telegram.messenger.camera;

/** Select only a device-advertised range, preferring fixed 30 FPS for round video.
 * Fall back to the closest supported range when fixed 30 FPS is unavailable. */
public final class RoundFpsSelector {
    private RoundFpsSelector() {}
    public static int choose(int[][] ranges) {
        return choose(ranges, 30, 30);
    }
    public static int choose(int[][] ranges, int preferredHigh, int preferredLow) {
        if (ranges == null) return -1;
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < ranges.length; i++) {
            if (ranges[i] == null || ranges[i].length != 2) continue;
            int low = ranges[i][0], high = ranges[i][1];
            if (low < 1 || high < low) continue;
            long score = Math.abs((long) high - preferredHigh) * 1000 + Math.abs((long) low - preferredLow);
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }
}
