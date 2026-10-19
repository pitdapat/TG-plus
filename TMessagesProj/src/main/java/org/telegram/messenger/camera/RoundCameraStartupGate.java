package org.telegram.messenger.camera;

/** One startup only. Camera metadata and texture callbacks may run on different threads. */
public final class RoundCameraStartupGate {
    public static final long MIN_SETTLE_MS = 120;
    public static final long NO_METADATA_WAIT_MS = 300;
    public static final long MAX_SETTLE_MS = 700;

    private long firstFrameElapsedMs = -1;
    private long lastResultTimestampNs;
    private long settledTimestampNs;
    private long releasedTimestampNs;
    private int settledResults;
    private boolean hasMeteringState;

    public synchronized boolean isReleased() {
        return releasedTimestampNs != 0;
    }

    public synchronized void onCaptureResult(long timestampNs, Integer ae, Integer awb) {
        if (releasedTimestampNs != 0 || timestampNs <= lastResultTimestampNs) return;
        lastResultTimestampNs = timestampNs;
        hasMeteringState |= ae != null || awb != null;
        // Camera2 AE: CONVERGED=2, LOCKED=3, FLASH_REQUIRED=4.
        // Camera2 AWB: CONVERGED=2, LOCKED=3. A missing optional state is tolerated.
        boolean aeReady = ae == null || ae == 2 || ae == 3 || ae == 4;
        boolean awbReady = awb == null || awb == 2 || awb == 3;
        if ((ae != null || awb != null) && aeReady && awbReady) {
            if (++settledResults >= 2 && settledTimestampNs == 0) {
                settledTimestampNs = timestampNs;
            }
        } else {
            settledResults = 0;
            settledTimestampNs = 0;
        }
    }

    public synchronized boolean allowFrame(long timestampNs, long elapsedMs) {
        if (timestampNs <= 0) return false;
        if (releasedTimestampNs != 0) return timestampNs >= releasedTimestampNs;
        if (firstFrameElapsedMs < 0) firstFrameElapsedMs = elapsedMs;
        long waitedMs = elapsedMs - firstFrameElapsedMs;
        boolean settled = settledResults >= 2 && timestampNs >= settledTimestampNs;
        if (waitedMs < MIN_SETTLE_MS || !(settled
                || !hasMeteringState && waitedMs >= NO_METADATA_WAIT_MS
                || waitedMs >= MAX_SETTLE_MS)) return false;
        releasedTimestampNs = timestampNs;
        return true;
    }
}
