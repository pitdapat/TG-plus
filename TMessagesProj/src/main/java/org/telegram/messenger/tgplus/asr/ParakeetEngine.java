package org.telegram.messenger.tgplus.asr;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.tgplus.DiagnosticsLog;
import org.telegram.messenger.tgplus.TgPlusDiagnostics;

import java.io.File;

/**
 * Stub for the Parakeet TDT 0.6B v3 engine (int8 ONNX, about 640 MB, run with sherpa-onnx).
 *
 * Not implemented yet: no native runtime or model is bundled. Every call reports
 * {@link AsrEngine.State#NOT_IMPLEMENTED} so the settings and diagnostics screens can
 * show the real state, and so callers can be written against the final interface.
 */
public final class ParakeetEngine implements AsrEngine {

    public static final String ERROR_NOT_IMPLEMENTED = "NOT_IMPLEMENTED";
    public static final String MODEL_ID = "parakeet-tdt-0.6b-v3-int8";
    /** Approximate download size of the int8 sherpa-onnx export. */
    public static final long APPROX_MODEL_BYTES = 640L * 1024 * 1024;

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

    private ParakeetEngine() {}

    @Override
    public String id() {
        return MODEL_ID;
    }

    @Override
    public String displayName() {
        return "Parakeet TDT 0.6B v3";
    }

    @Override
    public Status getStatus() {
        return new Status(State.NOT_IMPLEMENTED, ERROR_NOT_IMPLEMENTED, 0);
    }

    @Override
    public void transcribeFile(File audio, String language, Callback callback) {
        notImplemented("transcribe_file", callback);
    }

    @Override
    public void transcribePcm(short[] pcm16k, int length, String language, Callback callback) {
        notImplemented("transcribe_pcm", callback);
    }

    private void notImplemented(String action, Callback callback) {
        TgPlusDiagnostics.log(DiagnosticsLog.event("speech", action).fail(ERROR_NOT_IMPLEMENTED).tag("engine", MODEL_ID).build());
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(new Result(null, ERROR_NOT_IMPLEMENTED, 0, 0, 0)));
        }
    }
}
