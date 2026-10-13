package org.telegram.messenger.camera;

/** Select a bounded stream close to the sensor aspect ratio to avoid extra HAL cropping. */
public final class RoundStreamSelector {
    private RoundStreamSelector() {}

    public static int choose(int[][] sizes, int requiredShortEdge, float sensorAspect) {
        if (sizes == null || requiredShortEdge <= 0) return -1;
        double target = sensorAspect > 0 && !Float.isNaN(sensorAspect) && !Float.isInfinite(sensorAspect)
                ? Math.max(sensorAspect, 1f / sensorAspect) : 4.0 / 3.0;
        int best = -1;
        double bestDifference = Double.MAX_VALUE;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < sizes.length; i++) {
            int[] size = sizes[i];
            if (size == null || size.length < 2) continue;
            int shortEdge = Math.min(size[0], size[1]);
            int longEdge = Math.max(size[0], size[1]);
            if (shortEdge < requiredShortEdge || longEdge > 1440) continue;
            double difference = Math.abs((double) longEdge / shortEdge - target);
            long area = (long) shortEdge * longEdge;
            if (difference < bestDifference - 0.001
                    || Math.abs(difference - bestDifference) <= 0.001 && area < bestArea) {
                best = i;
                bestDifference = difference;
                bestArea = area;
            }
        }
        return best;
    }
}
