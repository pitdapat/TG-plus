package org.telegram.utils.recorder.round;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.utils.settings.SharedSettings;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * In-app camera diagnostic for round videos. Collects camera capabilities, what the recorder
 * would select, and live per-frame exposure/frame-timing measurements for the front and back
 * cameras with regular auto exposure versus motion priority. Produces a plain-text report.
 *
 * <p>The caller must hold the CAMERA permission. Results are delivered on the main thread.
 */
public final class RoundVideoCameraProbe {

    /** Receives progress and the final report on the main thread. */
    public interface Listener {
        void onProgress(@NonNull String status);

        void onFinished(@NonNull String report);
    }

    private static final long WARMUP_MS = 700;
    private static final long AUTO_PHASE_MS = 3_000;
    private static final long MOTION_PHASE_MS = 4_000;
    private static final long FPS60_PHASE_MS = 2_500;
    private static final long OPEN_TIMEOUT_MS = 5_000;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    public RoundVideoCameraProbe(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Starts the probe on a background thread. */
    public void start() {
        new Thread(this::runSafely, "RoundVideoProbe").start();
    }

    /** Stops after the current step; a partial report is still delivered. */
    public void cancel() {
        cancelled = true;
    }

    /** Log lines that repeat on every bubble and say nothing new; left out of the short report. */
    private static final String[] NOISY_LOG_MARKERS = {
            "camera capabilities:",
            "camera candidate accepted:",
            "fps selection:",
            "audio output format:",
            "video output format:",
            "first video sample prepared:",
            "audio encoder configure:",
            "audio timestamp alignment:",
            "first audio input:",
            "encoder swap:",
            "codec latency:",
            "GL initialized:",
    };
    private static final int SHORT_LOG_LINES = 60;
    private static final int SHORT_LINE_LENGTH = 220;

    /**
     * Short version of a full report that fits a single copy/paste: drops per-size lists and
     * the cameras that weren't live-tested, and keeps only the useful tail of the recorder log.
     */
    @NonNull
    public static String toShortReport(@NonNull String full) {
        String[] lines = full.split("\n", -1);
        java.util.HashSet<String> liveIds = new java.util.HashSet<>();
        for (String line : lines) {
            int at = line.indexOf("LIVE TEST: ");
            int cam = line.indexOf("(camera ");
            if (at >= 0 && cam > at) {
                int end = line.indexOf(')', cam);
                if (end > cam) liveIds.add(line.substring(cam + 8, end).trim());
            }
        }
        StringBuilder out = new StringBuilder(full.length() / 3);
        out.append("[SHORT REPORT - the full one can be shared as a file]\n");
        boolean inSkippedCamera = false;
        boolean inLog = false;
        java.util.ArrayList<String> logLines = new java.util.ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("--- camera ")) {
                String id = line.substring(11, Math.max(11, line.indexOf(':')));
                inSkippedCamera = !liveIds.contains(id.trim());
                out.append(line).append(inSkippedCamera ? " (details in full report)" : "").append('\n');
                continue;
            }
            if (line.startsWith("=====")) {
                inSkippedCamera = false;
                inLog = line.contains("RECENT RECORDER LOG");
                if (inLog) continue;
            }
            if (inLog) {
                if (line.startsWith("== END")) continue;
                boolean noisy = false;
                for (String marker : NOISY_LOG_MARKERS) {
                    if (line.contains(marker)) {
                        noisy = true;
                        break;
                    }
                }
                if (!noisy && !line.isEmpty()) {
                    logLines.add(line.length() > SHORT_LINE_LENGTH
                            ? line.substring(0, SHORT_LINE_LENGTH) + "…" : line);
                }
                continue;
            }
            if (inSkippedCamera || line.startsWith("previewSizes(maxFps)=")) continue;
            out.append(line).append('\n');
        }
        out.append("\n===== RECORDER LOG (last ").append(Math.min(SHORT_LOG_LINES, logLines.size()))
                .append(" of ").append(logLines.size()).append(" useful lines) =====\n");
        int from = Math.max(0, logLines.size() - SHORT_LOG_LINES);
        for (int i = from; i < logLines.size(); i++) out.append(logLines.get(i)).append('\n');
        out.append("== END ==\n");
        return out.toString();
    }

    /** Recent round-video recorder log lines kept in memory. */
    @NonNull
    public static String getRecentRecorderLog() {
        return RoundVideoDiagnostics.recentLog();
    }

    private void runSafely() {
        StringBuilder report = new StringBuilder(32 * 1024);
        cameraThread = new HandlerThread("RoundVideoProbeCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        try {
            run(report);
        } catch (Throwable t) {
            report.append("\n!! PROBE FAILED: ").append(Log.getStackTraceString(t)).append('\n');
        } finally {
            cameraThread.quitSafely();
        }
        String text = report.toString();
        mainHandler.post(() -> listener.onFinished(text));
    }

    private void progress(@NonNull String status) {
        mainHandler.post(() -> listener.onProgress(status));
    }

    private void run(@NonNull StringBuilder r) throws CameraAccessException {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        progress("Reading camera capabilities…");
        appendHeader(r);

        String[] ids = manager.getCameraIdList();
        section(r, "CAMERAS (" + ids.length + ")");
        for (String id : ids) {
            appendCamera(r, id, manager.getCameraCharacteristics(id));
        }

        for (RoundVideoSession.CameraFacing facing : RoundVideoSession.CameraFacing.values()) {
            if (cancelled) break;
            String id = pickCamera(manager, facing);
            section(r, "LIVE TEST: " + facing + (id == null ? "" : " (camera " + id + ")"));
            if (id == null) {
                r.append("no camera with this facing\n");
                continue;
            }
            try {
                liveTest(r, manager, id, facing);
            } catch (Throwable t) {
                r.append("!! live test failed: ").append(t).append('\n');
            }
        }

        section(r, "RECENT RECORDER LOG (record a bubble before running the test to fill this)");
        String log = RoundVideoDiagnostics.recentLog();
        r.append(log.isEmpty() ? "(empty)\n" : log);
        r.append("\n== END OF REPORT ==\n");
    }

    // ---------------------------------------------------------------- header + static info

    private void appendHeader(@NonNull StringBuilder r) {
        r.append("TG-plus round video camera report\n");
        r.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                .format(new Date())).append('\n');
        r.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append("), Android ")
                .append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT)
                .append('\n');
        r.append("app: ").append(BuildVars.BUILD_VERSION_STRING)
                .append(", buildType=").append(BuildConfig.BUILD_TYPE)
                .append(", debug=").append(BuildConfig.DEBUG_VERSION).append('\n');
        section(r, "SETTINGS");
        r.append("camera2Recorder=").append(SharedSettings.roundVideoCamera2Enabled.get())
                .append(", output=").append(SharedSettings.roundVideoOutputResolution.get())
                .append(", cameraResolution=").append(SharedSettings.roundVideoCameraResolution.get())
                .append(", fps=").append(SharedSettings.roundVideoFrameRate.get().getValue())
                .append(", bitrate=").append(SharedSettings.roundVideoVideoBitrate.get())
                .append('\n');
        r.append("motionPriority=").append(SharedSettings.roundVideoMotionPriorityLevel.get())
                .append(", stabilization=").append(SharedSettings.roundVideoStabilization.get())
                .append(", composition=").append(SharedSettings.roundVideoComposition.get())
                .append(", lastCamera=").append(SharedSettings.roundVideoLastCamera.get())
                .append('\n');
        try {
            int account = UserConfig.selectedAccount;
            MessagesController controller = MessagesController.getInstance(account);
            r.append("legacy recorder: usesCamera2=").append(SharedConfig.isUsingCamera2(account))
                    .append(", serverRoundSize=").append(controller.roundVideoSize)
                    .append(", serverVideoKbps=").append(controller.roundVideoBitrate)
                    .append(", serverAudioKbps=").append(controller.roundAudioBitrate)
                    .append('\n');
        } catch (Throwable t) {
            r.append("legacy recorder: unavailable (").append(t).append(")\n");
        }
    }

    private void appendCamera(
            @NonNull StringBuilder r,
            @NonNull String id,
            @NonNull CameraCharacteristics c
    ) {
        r.append("\n--- camera ").append(id).append(": ")
                .append(facingName(c.get(CameraCharacteristics.LENS_FACING)))
                .append(", level=").append(hardwareLevelName(
                        c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
                .append('\n');
        r.append("capabilities: ").append(capabilityNames(
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))).append('\n');
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Size pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        r.append("sensor: active=").append(active == null ? "?" : active.width() + "x" + active.height())
                .append(", pixels=").append(pixels)
                .append(", physicalMm=").append(physical)
                .append(", orientation=").append(c.get(CameraCharacteristics.SENSOR_ORIENTATION))
                .append(", focalMm=").append(Arrays.toString(
                        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
                .append('\n');
        Range<Long> exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        r.append("exposureRangeMs=").append(exposure == null ? "?" : String.format(Locale.US,
                        "%.4f..%.1f", exposure.getLower() / 1e6, exposure.getUpper() / 1e6))
                .append(", isoRange=").append(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))
                .append(", maxAnalogIso=").append(c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY))
                .append('\n');
        r.append("aeFpsRanges=").append(Arrays.toString(
                        c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)))
                .append(", aeModes=").append(Arrays.toString(
                        c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)))
                .append(", antibanding=").append(Arrays.toString(
                        c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)))
                .append('\n');
        if (Build.VERSION.SDK_INT >= 36) {
            r.append("aePriorityModes=").append(Arrays.toString(
                    c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES))).append('\n');
        }
        r.append("noiseReductionModes=").append(Arrays.toString(
                        c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)))
                .append(", videoStabilizationModes=").append(Arrays.toString(
                        c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)))
                .append(", oisModes=").append(Arrays.toString(
                        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)))
                .append('\n');
        r.append("flash=").append(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                .append(", maxDigitalZoom=").append(c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM));
        if (Build.VERSION.SDK_INT >= 30) {
            r.append(", zoomRatioRange=").append(c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE));
        }
        if (Build.VERSION.SDK_INT >= 28) {
            r.append(", physicalIds=").append(c.getPhysicalCameraIds());
        }
        r.append('\n');
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            r.append("previewSizes(maxFps)=");
            if (sizes != null) {
                for (Size size : sizes) {
                    long minNs = map.getOutputMinFrameDuration(SurfaceTexture.class, size);
                    r.append(size.getWidth()).append('x').append(size.getHeight());
                    if (minNs > 0) r.append('@').append(Math.round(1e9 / minNs));
                    r.append(' ');
                }
            }
            r.append('\n');
        }
        RoundVideoSession.OutputResolution output = SharedSettings.roundVideoOutputResolution.get();
        for (RoundVideoSession.CameraResolution tier : RoundVideoSession.CameraResolution.values()) {
            r.append("recorder pick ").append(output).append('/').append(tier).append(": ")
                    .append(RoundVideoCameraController.describeOutputSelection(c, output, tier))
                    .append('\n');
        }
        r.append("motionPriorityStrategy=").append(RoundVideoExposureController.detectStrategy(c))
                .append('\n');
    }

    // ---------------------------------------------------------------- live test

    @Nullable
    private static String pickCamera(
            @NonNull CameraManager manager,
            @NonNull RoundVideoSession.CameraFacing facing
    ) throws CameraAccessException {
        int required = facing == RoundVideoSession.CameraFacing.FRONT
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        String first = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing == null || lensFacing != required) continue;
            if (first == null) first = id;
            // Same preference as the recorder: on the back, a camera with a flash unit wins.
            if (facing == RoundVideoSession.CameraFacing.BACK
                    && Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                return id;
            }
            if (facing == RoundVideoSession.CameraFacing.FRONT) return id;
        }
        return first;
    }

    @SuppressLint("MissingPermission")
    private void liveTest(
            @NonNull StringBuilder r,
            @NonNull CameraManager manager,
            @NonNull String id,
            @NonNull RoundVideoSession.CameraFacing facing
    ) throws Exception {
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            r.append("no stream map\n");
            return;
        }
        Size size = chooseAnalysisSize(map.getOutputSizes(ImageFormat.YUV_420_888));
        if (size == null) {
            r.append("no YUV output size\n");
            return;
        }
        r.append("analysis stream: YUV ").append(size).append('\n');

        progress("Opening " + facing.name().toLowerCase(Locale.US) + " camera…");
        final CameraDevice[] deviceHolder = new CameraDevice[1];
        final String[] openError = new String[1];
        CountDownLatch opened = new CountDownLatch(1);
        manager.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                deviceHolder[0] = camera;
                opened.countDown();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                openError[0] = "disconnected (camera in use by another app?)";
                camera.close();
                opened.countDown();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                openError[0] = "error " + error;
                camera.close();
                opened.countDown();
            }
        }, cameraHandler);
        if (!opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS) || deviceHolder[0] == null) {
            r.append("!! camera open failed: ").append(openError[0] == null ? "timeout" : openError[0]).append('\n');
            return;
        }
        CameraDevice device = deviceHolder[0];
        ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                ImageFormat.YUV_420_888, 4);
        LumaMeter luma = new LumaMeter();
        reader.setOnImageAvailableListener(luma, cameraHandler);
        CameraCaptureSession session = null;
        try {
            final CameraCaptureSession[] sessionHolder = new CameraCaptureSession[1];
            CountDownLatch configured = new CountDownLatch(1);
            device.createCaptureSession(
                    Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession s) {
                            sessionHolder[0] = s;
                            configured.countDown();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                            configured.countDown();
                        }
                    },
                    cameraHandler
            );
            if (!configured.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS) || sessionHolder[0] == null) {
                r.append("!! capture session failed\n");
                return;
            }
            session = sessionHolder[0];
            String name = facing.name().toLowerCase(Locale.US);

            progress("Testing " + name + " camera: regular auto exposure…");
            runPhase(r, "A. regular auto exposure @30 (old behaviour)",
                    device, session, reader.getSurface(), c, 30,
                    RoundVideoSession.MotionPriority.OFF, AUTO_PHASE_MS, luma);
            if (cancelled) return;

            progress("Testing " + name + " camera: motion priority (balanced)…");
            runPhase(r, "B1. motion priority BALANCED @30 (default)",
                    device, session, reader.getSurface(), c, 30,
                    RoundVideoSession.MotionPriority.BALANCED, MOTION_PHASE_MS, luma);
            if (cancelled) return;

            progress("Testing " + name + " camera: motion priority (strong)…");
            runPhase(r, "B2. motion priority STRONG @30",
                    device, session, reader.getSurface(), c, 30,
                    RoundVideoSession.MotionPriority.STRONG, MOTION_PHASE_MS, luma);
            if (cancelled) return;

            Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            long minNs = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size);
            if (RoundVideoCameraController.findBestFpsRange(ranges, 60) != null
                    && (minNs <= 0 || minNs <= 1_000_000_000L / 60)) {
                progress("Testing " + name + " camera: 60 fps…");
                runPhase(r, "C. regular auto exposure @60",
                        device, session, reader.getSurface(), c, 60,
                        RoundVideoSession.MotionPriority.OFF, FPS60_PHASE_MS, luma);
            } else {
                r.append("\n[C. 60 fps] not supported by this camera at the analysis size\n");
            }
        } finally {
            if (session != null) {
                try {
                    session.stopRepeating();
                } catch (Exception ignore) {
                }
                session.close();
            }
            device.close();
            reader.close();
        }
    }

    private void runPhase(
            @NonNull StringBuilder r,
            @NonNull String title,
            @NonNull CameraDevice device,
            @NonNull CameraCaptureSession session,
            @NonNull Surface target,
            @NonNull CameraCharacteristics c,
            int fps,
            @NonNull RoundVideoSession.MotionPriority motionPriority,
            long durationMs,
            @NonNull LumaMeter luma
    ) throws Exception {
        RoundVideoExposureController exposure = new RoundVideoExposureController(c, motionPriority, fps);
        Range<Integer> fpsRange = RoundVideoCameraController.findBestFpsRange(
                c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES), fps);
        PhaseStats stats = new PhaseStats(SystemClock.elapsedRealtime() + WARMUP_MS);
        final long phaseStartMs = SystemClock.elapsedRealtime();
        final String[] requestError = new String[1];

        CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(
                    @NonNull CameraCaptureSession s,
                    @NonNull CaptureRequest request,
                    @NonNull TotalCaptureResult result
            ) {
                stats.add(result);
                if (exposure.onCaptureResult(result)) {
                    if (exposure.getMode() != RoundVideoExposureController.Mode.AUTO
                            && stats.modeEnteredAfterMs < 0) {
                        stats.modeEnteredAfterMs = SystemClock.elapsedRealtime() - phaseStartMs;
                    }
                    try {
                        s.setRepeatingRequest(
                                buildRequest(device, target, c, fpsRange, exposure), this, cameraHandler);
                    } catch (Exception e) {
                        requestError[0] = "mode " + exposure.getMode() + " rejected: " + e;
                        exposure.reset();
                        try {
                            s.setRepeatingRequest(
                                    buildRequest(device, target, c, fpsRange, exposure), this, cameraHandler);
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
        };
        cameraHandler.post(luma::reset);
        session.setRepeatingRequest(buildRequest(device, target, c, fpsRange, exposure), callback, cameraHandler);
        sleep(durationMs);
        // Stop and read results on the camera thread so no callback races the summary.
        CountDownLatch done = new CountDownLatch(1);
        final String[] summary = new String[1];
        cameraHandler.post(() -> {
            try {
                session.stopRepeating();
            } catch (Exception ignore) {
            }
            summary[0] = stats.describe(fpsRange, exposure, luma.average());
            done.countDown();
        });
        done.await(2, TimeUnit.SECONDS);
        r.append('\n').append('[').append(title).append("]\n")
                .append(summary[0] == null ? "(no summary)\n" : summary[0]);
        if (requestError[0] != null) r.append("!! ").append(requestError[0]).append('\n');
        sleep(300);
    }

    @NonNull
    private static CaptureRequest buildRequest(
            @NonNull CameraDevice device,
            @NonNull Surface target,
            @NonNull CameraCharacteristics c,
            @Nullable Range<Integer> fpsRange,
            @NonNull RoundVideoExposureController exposure
    ) throws CameraAccessException {
        // Mirrors the recorder's repeating request so measurements match real recordings.
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        b.addTarget(target);
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
        int[] afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
        if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        exposure.apply(b);
        return b.build();
    }

    @Nullable
    private static Size chooseAnalysisSize(@Nullable Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size size : sizes) {
            int shortSide = Math.min(size.getWidth(), size.getHeight());
            if (shortSide < 360) continue;
            if (best == null) {
                best = size;
                continue;
            }
            int error = Math.abs(shortSide - 720);
            int bestError = Math.abs(Math.min(best.getWidth(), best.getHeight()) - 720);
            boolean fourByThree = size.getWidth() * 3 == size.getHeight() * 4
                    || size.getHeight() * 3 == size.getWidth() * 4;
            if (error < bestError || error == bestError && fourByThree) best = size;
        }
        return best == null ? sizes[0] : best;
    }

    // ---------------------------------------------------------------- measurements

    private static final class PhaseStats {
        final long measureFromMs;
        long frames;
        long firstTimestampNs;
        long lastTimestampNs;
        long intervalCount;
        long intervalTotalNs;
        double intervalSquaredTotalNs;
        long intervalMaxNs;
        long gapsOver50Ms;
        long exposureTotalNs;
        long exposureMinNs = Long.MAX_VALUE;
        long exposureMaxNs;
        long isoTotal;
        int isoMin = Integer.MAX_VALUE;
        int isoMax;
        long frameDurationTotalNs;
        long frameDurationCount;
        Integer lastAeState;
        Integer lastNoiseReduction;
        Integer lastAeMode;
        int flicker = -1;
        long modeEnteredAfterMs = -1;

        PhaseStats(long measureFromMs) {
            this.measureFromMs = measureFromMs;
        }

        void add(@NonNull CaptureResult result) {
            if (SystemClock.elapsedRealtime() < measureFromMs) return;
            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null) return;
            frames++;
            if (firstTimestampNs == 0) {
                firstTimestampNs = ts;
            } else if (ts > lastTimestampNs) {
                long interval = ts - lastTimestampNs;
                intervalCount++;
                intervalTotalNs += interval;
                intervalSquaredTotalNs += (double) interval * interval;
                intervalMaxNs = Math.max(intervalMaxNs, interval);
                if (interval > 50_000_000L) gapsOver50Ms++;
            }
            lastTimestampNs = ts;
            Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (exposure != null) {
                exposureTotalNs += exposure;
                exposureMinNs = Math.min(exposureMinNs, exposure);
                exposureMaxNs = Math.max(exposureMaxNs, exposure);
            }
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (iso != null) {
                isoTotal += iso;
                isoMin = Math.min(isoMin, iso);
                isoMax = Math.max(isoMax, iso);
            }
            Long frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
            if (frameDuration != null) {
                frameDurationTotalNs += frameDuration;
                frameDurationCount++;
            }
            lastAeState = result.get(CaptureResult.CONTROL_AE_STATE);
            lastAeMode = result.get(CaptureResult.CONTROL_AE_MODE);
            lastNoiseReduction = result.get(CaptureResult.NOISE_REDUCTION_MODE);
            Integer f = result.get(CaptureResult.STATISTICS_SCENE_FLICKER);
            if (f != null) flicker = f;
        }

        @NonNull
        String describe(
                @Nullable Range<Integer> fpsRange,
                @NonNull RoundVideoExposureController exposure,
                float luma
        ) {
            if (frames < 2) return "frames=" + frames + " (not enough data)\n";
            double seconds = (lastTimestampNs - firstTimestampNs) / 1e9;
            double avgMs = intervalTotalNs / (double) intervalCount / 1e6;
            double variance = intervalSquaredTotalNs / intervalCount
                    - Math.pow(intervalTotalNs / (double) intervalCount, 2);
            double jitterMs = Math.sqrt(Math.max(0, variance)) / 1e6;
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US,
                    "fpsRange=%s measuredFps=%.1f frameIntervalMs{avg=%.1f jitter=%.1f max=%.1f} gaps>50ms=%d%n",
                    fpsRange, (frames - 1) / Math.max(seconds, 1e-6), avgMs, jitterMs,
                    intervalMaxNs / 1e6, gapsOver50Ms));
            if (exposureMaxNs > 0) {
                double avgExposureMs = exposureTotalNs / (double) frames / 1e6;
                b.append(String.format(Locale.US,
                        "shutter{avg=%.2fms (1/%d s) min=%.2fms max=%.2fms} iso{avg=%d min=%d max=%d}%n",
                        avgExposureMs, Math.round(1000 / Math.max(avgExposureMs, 0.001)),
                        exposureMinNs / 1e6, exposureMaxNs / 1e6,
                        isoTotal / frames, isoMin, isoMax));
            } else {
                b.append("shutter/iso: not reported by this camera\n");
            }
            b.append(String.format(Locale.US,
                    "frameDurationMs=%.1f aeMode=%s aeState=%s noiseReduction=%s flicker=%s brightness(Y)=%.0f/255%n",
                    frameDurationCount == 0 ? 0 : frameDurationTotalNs / (double) frameDurationCount / 1e6,
                    lastAeMode, aeStateName(lastAeState), noiseReductionName(lastNoiseReduction),
                    RoundVideoExposureController.flickerName(flicker), luma));
            if (exposure.isEnabled()) {
                b.append("motionPriority: strategy=").append(exposure.getStrategy())
                        .append(" finalMode=").append(exposure.getMode())
                        .append(" limitMs=").append(String.format(Locale.US, "%.2f",
                                exposure.getExposureLimitNs() / 1e6))
                        .append(" enteredAfterMs=").append(modeEnteredAfterMs)
                        .append(modeEnteredAfterMs < 0 && exposure.getStrategy() != RoundVideoExposureController.Strategy.NONE
                                ? " (not needed: auto exposure already faster than the limit)" : "")
                        .append('\n');
            }
            return b.toString();
        }
    }

    /** Average luma of the Y plane, sampled sparsely; runs on the camera thread. */
    private static final class LumaMeter implements ImageReader.OnImageAvailableListener {
        private long total;
        private long count;
        private int frame;

        void reset() {
            total = 0;
            count = 0;
        }

        float average() {
            return count == 0 ? 0f : total / (float) count;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image;
            try {
                image = reader.acquireLatestImage();
            } catch (IllegalStateException e) {
                return;
            }
            if (image == null) return;
            try {
                if (frame++ % 3 != 0) return;
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                int rowStride = plane.getRowStride();
                int pixelStride = plane.getPixelStride();
                int width = image.getWidth();
                int height = image.getHeight();
                long sum = 0;
                int samples = 0;
                for (int y = 0; y < height; y += 16) {
                    int row = y * rowStride;
                    for (int x = 0; x < width; x += 16) {
                        int index = row + x * pixelStride;
                        if (index >= buffer.limit()) break;
                        sum += buffer.get(index) & 0xFF;
                        samples++;
                    }
                }
                if (samples > 0) {
                    total += sum / samples;
                    count++;
                }
            } finally {
                image.close();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void section(@NonNull StringBuilder r, @NonNull String title) {
        r.append("\n===== ").append(title).append(" =====\n");
    }

    private void sleep(long ms) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + ms;
        while (!cancelled) {
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0) return;
            Thread.sleep(Math.min(left, 100));
        }
    }

    private static boolean contains(@Nullable int[] values, int target) {
        if (values == null) return false;
        for (int value : values) if (value == target) return true;
        return false;
    }

    @NonNull
    private static String facingName(@Nullable Integer facing) {
        if (facing == null) return "unknown";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "FRONT";
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "BACK";
        return "EXTERNAL";
    }

    @NonNull
    private static String hardwareLevelName(@Nullable Integer level) {
        if (level == null) return "unknown";
        switch (level) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return String.valueOf(level);
        }
    }

    @NonNull
    private static String capabilityNames(@Nullable int[] capabilities) {
        if (capabilities == null) return "[]";
        String[] names = {
                "BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW",
                "PRIVATE_REPROCESSING", "READ_SENSOR_SETTINGS", "BURST_CAPTURE", "YUV_REPROCESSING",
                "DEPTH_OUTPUT", "HIGH_SPEED_VIDEO", "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA",
                "MONOCHROME", "SECURE_IMAGE_DATA", "SYSTEM_CAMERA", "OFFLINE_PROCESSING",
                "ULTRA_HIGH_RESOLUTION_SENSOR", "REMOSAIC_REPROCESSING", "DYNAMIC_RANGE_TEN_BIT",
                "STREAM_USE_CASE", "COLOR_SPACE_PROFILES"
        };
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < capabilities.length; i++) {
            if (i > 0) b.append(", ");
            int value = capabilities[i];
            b.append(value >= 0 && value < names.length ? names[value] : String.valueOf(value));
        }
        return b.append(']').toString();
    }

    @NonNull
    private static String aeStateName(@Nullable Integer state) {
        if (state == null) return "null";
        switch (state) {
            case CaptureResult.CONTROL_AE_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AE_STATE_SEARCHING: return "SEARCHING";
            case CaptureResult.CONTROL_AE_STATE_CONVERGED: return "CONVERGED";
            case CaptureResult.CONTROL_AE_STATE_LOCKED: return "LOCKED";
            case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED: return "FLASH_REQUIRED";
            case CaptureResult.CONTROL_AE_STATE_PRECAPTURE: return "PRECAPTURE";
            default: return String.valueOf(state);
        }
    }

    @NonNull
    private static String noiseReductionName(@Nullable Integer mode) {
        if (mode == null) return "null";
        switch (mode) {
            case CaptureResult.NOISE_REDUCTION_MODE_OFF: return "OFF";
            case CaptureResult.NOISE_REDUCTION_MODE_FAST: return "FAST";
            case CaptureResult.NOISE_REDUCTION_MODE_HIGH_QUALITY: return "HIGH_QUALITY";
            case CaptureResult.NOISE_REDUCTION_MODE_MINIMAL: return "MINIMAL";
            case CaptureResult.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG: return "ZERO_SHUTTER_LAG";
            default: return String.valueOf(mode);
        }
    }
}
