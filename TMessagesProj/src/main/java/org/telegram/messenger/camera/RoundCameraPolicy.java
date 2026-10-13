package org.telegram.messenger.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;

/** Experimental round-video controls. No vendor/private APIs are assumed. */
public final class RoundCameraPolicy {
    private RoundCameraPolicy() {}

    public static Range<Integer> chooseFps(CameraCharacteristics c, boolean round) {
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) return null;
        int[][] values = new int[ranges.length][2];
        for (int i = 0; i < ranges.length; i++) {
            values[i][0] = ranges[i].getLower();
            values[i][1] = ranges[i].getUpper();
        }
        int index = RoundFpsSelector.choose(values, round ? 30 : 60, 30);
        return index < 0 ? null : ranges[index];
    }

    private static boolean supports(int[] modes, int wanted) {
        if (modes != null) for (int mode : modes) if (mode == wanted) return true;
        return false;
    }

    public static void applySupportedControls(CaptureRequest.Builder b, CameraCharacteristics c) {
        if (supports(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
        // Prefer optical stabilization; restore electronic stabilization where OIS
        // is unavailable, requesting only one stabilization mode at a time.
        if (supports(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            if (supports(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            }
        } else if (supports(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            if (supports(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            }
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }
    }
}
