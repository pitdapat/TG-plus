package org.telegram.messenger.tgplus.asr;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.tgplus.DiagnosticsLog;
import org.telegram.messenger.tgplus.TgPlusDiagnostics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Parakeet TDT 0.6B v3 (int8) running on the phone through sherpa-onnx.
 *
 * The model needs about 1 GB of memory while loaded, so it is loaded on first use and released again after
 * {@link #IDLE_RELEASE_MS} without use. All native work happens on one background thread; callbacks run on the
 * main thread. Diagnostics get timings and memory figures only, never the audio or the recognized text.
 */
public final class ParakeetEngine implements AsrEngine {

    public static final String MODEL_ID = ParakeetModel.ID;

    public static final String ERROR_MODEL_MISSING = "MODEL_MISSING";
    public static final String ERROR_NATIVE_LOAD = "NATIVE_LOAD";
    public static final String ERROR_LOW_MEMORY = "LOW_MEMORY";
    public static final String ERROR_OUT_OF_MEMORY = "OUT_OF_MEMORY";
    public static final String ERROR_ENGINE = "ENGINE_ERROR";
    public static final String ERROR_UNSUPPORTED = "UNSUPPORTED_FORMAT";
    public static final String ERROR_EMPTY_AUDIO = "EMPTY_AUDIO";

    /** Free the model after this long without use. */
    static final long IDLE_RELEASE_MS = 60_000;
    /** Refuse to load the model when the phone has less than this available; the system would kill the app. */
    static final long MIN_AVAILABLE_BYTES = 1600L * 1024 * 1024;
    /** Longest audio recognized in one call. */
    static final long MAX_AUDIO_MS = 10L * 60 * 1000;

    private static volatile ParakeetEngine instance;

    public static ParakeetEngine getInstance() {
        ParakeetEngine local = instance;
        if (local == null) {
            synchronized (ParakeetEngine.class) {
                local = instance;
                if (local == null) instance = local = new ParakeetEngine();
            }
        }
        return local;
    }

    private final DispatchQueue queue = new DispatchQueue("tgplusAsr");
    private final Runnable idleRelease = this::releaseOnQueue;

    // Touched only on the queue.
    private OfflineRecognizer recognizer;
    private int recognizerThreads;

    /** Set when the native library cannot be loaded on this phone; the engine then stays in ERROR. */
    private volatile String fatalCode;
    private volatile String fatalDetail;
    private volatile boolean loaded;

    private ParakeetEngine() {}

    @Override
    public String id() {
        return MODEL_ID;
    }

    @Override
    public String displayName() {
        return ParakeetModel.DISPLAY_NAME;
    }

    /** True while the model is held in memory. */
    public boolean isLoaded() {
        return loaded;
    }

    /** Developer-facing detail of the failure that put the engine in ERROR, or null. */
    public String errorDetail() {
        return fatalDetail;
    }

    @Override
    public Status getStatus() {
        if (fatalCode != null) return new Status(State.ERROR, fatalCode, 0);
        ParakeetModelManager manager = ParakeetModelManager.getInstance();
        switch (manager.state()) {
            case READY:
                return new Status(State.READY, loaded ? "LOADED" : "READY", manager.installedBytes());
            case DOWNLOADING:
            case UNPACKING:
                return new Status(State.DOWNLOADING, manager.state().name(), manager.partialBytes());
            case ERROR:
                return new Status(State.MODEL_MISSING, manager.error() == null ? "ERROR" : manager.error(), manager.partialBytes());
            default:
                return new Status(State.MODEL_MISSING, ERROR_MODEL_MISSING, manager.partialBytes());
        }
    }

    @Override
    public void transcribeFile(File audio, String language, Callback callback) {
        queue.postRunnable(() -> {
            long start = SystemClock.elapsedRealtime();
            WavReader.Wav wav;
            try {
                if (audio == null || !audio.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav")) {
                    throw new IOException(ERROR_UNSUPPORTED + ": only WAV files so far; voice-note formats come with the next step");
                }
                wav = WavReader.read(audio);
            } catch (IOException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                deliver(callback, new Result(null, message.startsWith(ERROR_UNSUPPORTED) ? ERROR_UNSUPPORTED : ERROR_ENGINE, message, 0, 0, 0, 0, -1, -1, 0));
                return;
            }
            recognize(wav.samples, wav.sampleRate, SystemClock.elapsedRealtime() - start, callback);
        });
    }

    @Override
    public void transcribePcm(short[] pcm16k, int length, String language, Callback callback) {
        final int n = Math.max(0, Math.min(length, pcm16k == null ? 0 : pcm16k.length));
        final float[] samples = new float[n];
        for (int i = 0; i < n; i++) samples[i] = pcm16k[i] / 32768f;
        transcribeSamples(samples, 16000, language, callback);
    }

    @Override
    public void transcribeSamples(float[] samples, int sampleRate, String language, Callback callback) {
        queue.postRunnable(() -> recognize(samples, sampleRate, 0, callback));
    }

    @Override
    public void release() {
        queue.postRunnable(this::releaseOnQueue);
    }

    // Everything below runs on the queue.

    private void recognize(float[] samples, int sampleRate, long decodeMs, Callback callback) {
        final int rate = sampleRate;
        final long audioMs = rate <= 0 || samples == null ? 0 : samples.length * 1000L / rate;
        if (samples == null || samples.length == 0 || rate <= 0) {
            deliver(callback, fail(ERROR_EMPTY_AUDIO, null, 0, decodeMs, 0, 0));
            return;
        }
        if (audioMs > MAX_AUDIO_MS) {
            deliver(callback, fail(ERROR_UNSUPPORTED, "audio longer than " + MAX_AUDIO_MS / 1000 + " s", audioMs, decodeMs, 0, 0));
            return;
        }
        if (fatalCode != null) {
            deliver(callback, new Result(null, fatalCode, fatalDetail, audioMs, decodeMs, 0, 0, -1, -1, 0));
            return;
        }
        File dir = ParakeetModelManager.getInstance().dir();
        if (!ParakeetModelManager.getInstance().isInstalled()) {
            deliver(callback, fail(ERROR_MODEL_MISSING, null, audioMs, decodeMs, 0, 0));
            return;
        }

        queue.cancelRunnable(idleRelease);
        long loadMs = 0;
        if (recognizer == null) {
            long available = availableMemory();
            if (available >= 0 && available < MIN_AVAILABLE_BYTES) {
                TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "load").fail(ERROR_LOW_MEMORY).num("avail_mb", available >> 20).build());
                deliver(callback, fail(ERROR_LOW_MEMORY, "available " + (available >> 20) + " MB, need " + (MIN_AVAILABLE_BYTES >> 20), audioMs, decodeMs, 0, 0));
                return;
            }
            long loadStart = SystemClock.elapsedRealtime();
            try {
                recognizerThreads = threadCount();
                recognizer = create(dir, recognizerThreads);
            } catch (Throwable t) {
                recognizer = null;
                String code = classify(t);
                if (ERROR_NATIVE_LOAD.equals(code)) {
                    fatalCode = code;
                    fatalDetail = describe(t);
                }
                TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "load").fail(code)
                    .duration(SystemClock.elapsedRealtime() - loadStart).tag("cause", t.getClass().getSimpleName()).build());
                deliver(callback, new Result(null, code, describe(t), audioMs, decodeMs, 0, 0, memoryMb(), peakMemoryMb(), recognizerThreads));
                return;
            }
            loadMs = SystemClock.elapsedRealtime() - loadStart;
            loaded = true;
            TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "load").ok().duration(loadMs)
                .num("threads", recognizerThreads).num("rss_mb", memoryMb()).num("peak_mb", peakMemoryMb()).num("avail_mb", available >> 20).build());
        }

        OfflineStream stream = null;
        try {
            stream = recognizer.createStream();
            stream.acceptWaveform(samples, rate);
            long inferStart = SystemClock.elapsedRealtime();
            recognizer.decode(stream);
            long inferenceMs = SystemClock.elapsedRealtime() - inferStart;
            String text = recognizer.getResult(stream).getText();
            text = text == null ? "" : text.trim();
            Result result = new Result(text, null, null, audioMs, decodeMs, inferenceMs, loadMs, memoryMb(), peakMemoryMb(), recognizerThreads);
            TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "transcribe").ok().duration(inferenceMs)
                .num("audio_ms", audioMs).num("speed_x10", result.speedTimesTen()).num("chars", text.length())
                .num("rss_mb", result.memoryMb).num("threads", recognizerThreads).build());
            deliver(callback, result);
        } catch (Throwable t) {
            String code = classify(t);
            TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "transcribe").fail(code).num("audio_ms", audioMs)
                .tag("cause", t.getClass().getSimpleName()).build());
            // After an out-of-memory error the native state is not trustworthy: drop the model.
            releaseOnQueue();
            deliver(callback, new Result(null, code, describe(t), audioMs, decodeMs, 0, loadMs, memoryMb(), peakMemoryMb(), recognizerThreads));
        } finally {
            if (stream != null) {
                try {
                    stream.release();
                } catch (Throwable ignore) {
                }
            }
            if (recognizer != null) queue.postRunnable(idleRelease, IDLE_RELEASE_MS);
        }
    }

    private static OfflineRecognizer create(File dir, int threads) {
        OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
        transducer.setEncoder(new File(dir, ParakeetModel.ENCODER.fileName).getAbsolutePath());
        transducer.setDecoder(new File(dir, ParakeetModel.DECODER.fileName).getAbsolutePath());
        transducer.setJoiner(new File(dir, ParakeetModel.JOINER.fileName).getAbsolutePath());

        OfflineModelConfig model = new OfflineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(new File(dir, ParakeetModel.TOKENS.fileName).getAbsolutePath());
        model.setNumThreads(threads);
        model.setProvider("cpu");
        model.setModelType("nemo_transducer");
        model.setDebug(false);

        FeatureConfig feature = new FeatureConfig();
        feature.setSampleRate(16000);
        feature.setFeatureDim(128);

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(feature);
        config.setModelConfig(model);
        config.setDecodingMethod("greedy_search");
        // No AssetManager: the model is read from files.
        return new OfflineRecognizer(null, config);
    }

    private void releaseOnQueue() {
        queue.cancelRunnable(idleRelease);
        if (recognizer != null) {
            try {
                recognizer.release();
            } catch (Throwable ignore) {
            }
            recognizer = null;
            loaded = false;
            TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "release").ok().num("rss_mb", memoryMb()).build());
        }
    }

    private static Result fail(String code, String detail, long audioMs, long decodeMs, long loadMs, int threads) {
        return new Result(null, code, detail, audioMs, decodeMs, 0, loadMs, memoryMb(), peakMemoryMb(), threads);
    }

    private static void deliver(Callback callback, Result result) {
        if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
    }

    /** Four threads on phones with eight or more cores, fewer on smaller ones. */
    static int threadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(4, cores / 2));
    }

    static String classify(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof UnsatisfiedLinkError || c instanceof NoClassDefFoundError || c instanceof ExceptionInInitializerError) return ERROR_NATIVE_LOAD;
            if (c instanceof OutOfMemoryError) return ERROR_OUT_OF_MEMORY;
        }
        return ERROR_ENGINE;
    }

    public static String describe(Throwable t) {
        String text = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private static long availableMemory() {
        try {
            ActivityManager am = (ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.availMem;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Resident memory of this process now, in MB. */
    public static long memoryMb() {
        return procStatusMb("VmRSS:");
    }

    /** Highest resident memory of this process so far, in MB. */
    public static long peakMemoryMb() {
        return procStatusMb("VmHWM:");
    }

    private static long procStatusMb(String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key)) {
                    String digits = line.substring(key.length()).replaceAll("[^0-9]", "");
                    return digits.isEmpty() ? -1 : Long.parseLong(digits) / 1024;
                }
            }
        } catch (Throwable ignore) {
        }
        return -1;
    }
}
