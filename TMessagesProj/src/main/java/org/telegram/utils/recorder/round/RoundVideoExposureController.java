package org.telegram.utils.recorder.round;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.util.Range;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * "Motion priority" exposure: keeps the shutter at or below a flicker-safe limit so moving
 * subjects stay sharp, raising ISO instead.
 *
 * <p>The camera starts in regular auto exposure. Only when auto exposure keeps choosing an
 * exposure time longer than the limit does this switch to:
 * <ul>
 *     <li>{@code PRIORITY}: Android 16 exposure-time-priority AE, where the camera keeps metering
 *     and only adjusts ISO (preferred, fully automatic);</li>
 *     <li>{@code MANUAL}: manual sensor control seeded from the last auto-exposure result with the
 *     same overall brightness. Manual mode does not re-meter, so it is reset whenever lighting is
 *     changed by the app (flash) and on every camera switch.</li>
 * </ul>
 * Also requests FAST noise reduction, which avoids multi-frame noise reduction ghosting.
 *
 * <p>Not thread-safe: use from the camera handler thread only.
 */
final class RoundVideoExposureController {

    // Limits are whole multiples of the light's half-cycle (10 ms at 50 Hz, 8.33 ms at 60 Hz),
    // so mains-powered lights do not cause banding.
    /** STRONG: 1/100 s under 50 Hz light. */
    static final long STRONG_50HZ_NS = 10_000_000L;
    /** STRONG: 1/120 s under 60 Hz light. */
    static final long STRONG_60HZ_NS = 8_333_333L;
    /** BALANCED: 1/50 s under 50 Hz light. */
    static final long BALANCED_50HZ_NS = 20_000_000L;
    /** BALANCED: 1/60 s under 60 Hz light. */
    static final long BALANCED_60HZ_NS = 16_666_667L;

    private static final float ENTER_MARGIN = 1.1f;
    private static final int ENTER_FRAMES = 6;
    private static final int EXIT_FRAMES = 30;

    enum Strategy { NONE, PRIORITY, MANUAL }

    enum Mode { AUTO, PRIORITY, MANUAL }

    private final boolean enabled;
    private final boolean strong;
    private final Strategy strategy;
    private final int frameRate;
    private final boolean fastNoiseReductionSupported;
    @Nullable private final Range<Long> exposureRange;
    @Nullable private final Range<Integer> sensitivityRange;

    private Mode mode = Mode.AUTO;
    private int overLimitFrames;
    private int brightFrames;
    private int lastFlicker = -1;
    private long fixedExposureNs;
    private int fixedSensitivity;
    private long modeChanges;

    private long statFrames;
    private long statExposureTotalNs;
    private long statExposureMaxNs;
    private long statSensitivityTotal;
    private int statSensitivityMax;

    RoundVideoExposureController(
            @NonNull CameraCharacteristics characteristics,
            @NonNull RoundVideoSession.MotionPriority level,
            int frameRate
    ) {
        this.enabled = level != RoundVideoSession.MotionPriority.OFF;
        this.strong = level == RoundVideoSession.MotionPriority.STRONG;
        this.frameRate = Math.max(1, frameRate);
        this.strategy = detectStrategy(characteristics);
        this.exposureRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        );
        this.sensitivityRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        );
        int[] noiseModes = characteristics.get(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        );
        this.fastNoiseReductionSupported =
                contains(noiseModes, CameraMetadata.NOISE_REDUCTION_MODE_FAST);
    }

    @NonNull
    static Strategy detectStrategy(@NonNull CameraCharacteristics characteristics) {
        if (Build.VERSION.SDK_INT >= 36) {
            int[] priorityModes = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES
            );
            if (contains(
                    priorityModes,
                    CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
            )) {
                return Strategy.PRIORITY;
            }
        }
        int[] capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        );
        if (contains(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                && characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null
                && characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null) {
            return Strategy.MANUAL;
        }
        return Strategy.NONE;
    }

    @NonNull
    Strategy getStrategy() {
        return strategy;
    }

    @NonNull
    Mode getMode() {
        return mode;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Returns to regular auto exposure; used when lighting changes (flash) or on errors. */
    void reset() {
        if (mode != Mode.AUTO) modeChanges++;
        mode = Mode.AUTO;
        overLimitFrames = 0;
        brightFrames = 0;
    }

    /** Applies noise-reduction and exposure keys. Call after AE mode and torch keys are set. */
    void apply(@NonNull CaptureRequest.Builder builder) {
        if (!enabled) return;
        if (fastNoiseReductionSupported) {
            builder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
            );
        }
        if (mode == Mode.PRIORITY && Build.VERSION.SDK_INT >= 36) {
            builder.set(
                    CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                    CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
            );
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, fixedExposureNs);
        } else if (mode == Mode.MANUAL) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, fixedExposureNs);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, fixedSensitivity);
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / frameRate);
        }
    }

    /**
     * Feeds a capture result. Returns true when the repeating request must be rebuilt because
     * the exposure mode changed.
     */
    boolean onCaptureResult(@NonNull CaptureResult result) {
        Long exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Integer flicker = result.get(CaptureResult.STATISTICS_SCENE_FLICKER);
        if (flicker != null && flicker != CaptureResult.STATISTICS_SCENE_FLICKER_NONE) {
            lastFlicker = flicker;
        }
        if (exposureNs == null || sensitivity == null) return false;
        statFrames++;
        statExposureTotalNs += exposureNs;
        statExposureMaxNs = Math.max(statExposureMaxNs, exposureNs);
        statSensitivityTotal += sensitivity;
        statSensitivityMax = Math.max(statSensitivityMax, sensitivity);
        if (!enabled || strategy == Strategy.NONE) return false;

        long limitNs = getExposureLimitNs();
        if (mode == Mode.AUTO) {
            overLimitFrames = exposureNs > limitNs * ENTER_MARGIN ? overLimitFrames + 1 : 0;
            if (overLimitFrames < ENTER_FRAMES) return false;
            overLimitFrames = 0;
            return enterLimitedMode(exposureNs, sensitivity, limitNs);
        }
        if (mode == Mode.PRIORITY && sensitivityRange != null) {
            // AE has dropped to base ISO: the scene is bright enough for regular AE again.
            boolean atBaseIso = sensitivity <= sensitivityRange.getLower() * 1.05f;
            brightFrames = atBaseIso ? brightFrames + 1 : 0;
            if (brightFrames >= EXIT_FRAMES) {
                reset();
                return true;
            }
        }
        return false;
    }

    private boolean enterLimitedMode(long exposureNs, int sensitivity, long limitNs) {
        if (strategy == Strategy.PRIORITY) {
            fixedExposureNs = clampExposure(limitNs);
            mode = Mode.PRIORITY;
            modeChanges++;
            return true;
        }
        if (sensitivityRange == null) return false;
        // Keep exposure x ISO constant so brightness does not jump.
        double product = (double) exposureNs * sensitivity;
        int iso = (int) Math.ceil(product / limitNs);
        iso = Math.max(sensitivityRange.getLower(), Math.min(sensitivityRange.getUpper(), iso));
        long exposure = (long) (product / iso);
        // If ISO hit its maximum, exposure stays longer than the limit (never longer than AE chose).
        exposure = Math.min(exposureNs, Math.max(limitNs, exposure));
        fixedExposureNs = clampExposure(exposure);
        fixedSensitivity = iso;
        mode = Mode.MANUAL;
        modeChanges++;
        return true;
    }

    long getExposureLimitNs() {
        boolean sixtyHz = lastFlicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ;
        if (strong) return sixtyHz ? STRONG_60HZ_NS : STRONG_50HZ_NS;
        return sixtyHz ? BALANCED_60HZ_NS : BALANCED_50HZ_NS;
    }

    private long clampExposure(long exposureNs) {
        long maxForFrame = 1_000_000_000L / frameRate;
        long value = Math.min(exposureNs, maxForFrame);
        if (exposureRange != null) {
            value = Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), value));
        }
        return value;
    }

    /** One-line summary of the last window of frames, then resets the window. */
    @NonNull
    String takeStatistics() {
        String text;
        if (statFrames == 0) {
            text = "exposure={n/a}";
        } else {
            text = String.format(
                    Locale.US,
                    "exposure={avgMs=%.2f, maxMs=%.2f, avgIso=%d, maxIso=%d, mode=%s, "
                            + "strategy=%s, limitMs=%.2f, fixedMs=%.2f, fixedIso=%d, "
                            + "flicker=%s, modeChanges=%d}",
                    statExposureTotalNs / (double) statFrames / 1_000_000d,
                    statExposureMaxNs / 1_000_000d,
                    statSensitivityTotal / statFrames,
                    statSensitivityMax,
                    mode,
                    enabled ? (strong ? "STRONG/" : "BALANCED/") + strategy : "OFF",
                    getExposureLimitNs() / 1_000_000d,
                    fixedExposureNs / 1_000_000d,
                    fixedSensitivity,
                    flickerName(lastFlicker),
                    modeChanges
            );
        }
        statFrames = 0;
        statExposureTotalNs = 0;
        statExposureMaxNs = 0;
        statSensitivityTotal = 0;
        statSensitivityMax = 0;
        return text;
    }

    @NonNull
    static String flickerName(int flicker) {
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ) return "50Hz";
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) return "60Hz";
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_NONE) return "none";
        return "unknown";
    }

    private static boolean contains(@Nullable int[] values, int target) {
        if (values == null) return false;
        for (int value : values) if (value == target) return true;
        return false;
    }
}
