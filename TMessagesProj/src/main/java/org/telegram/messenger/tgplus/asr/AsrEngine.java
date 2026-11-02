package org.telegram.messenger.tgplus.asr;

import java.io.File;

/**
 * On-device speech recognition engine. Phase 1 target: Parakeet TDT 0.6B v3 via sherpa-onnx.
 * Implementations run work off the main thread and deliver callbacks on the main thread.
 */
public interface AsrEngine {

    enum State {
        /** The engine is not built into this app yet. */
        NOT_IMPLEMENTED,
        /** The engine exists but its model has not been downloaded. */
        MODEL_MISSING,
        DOWNLOADING,
        READY,
        ERROR
    }

    final class Status {
        public final State state;
        /** Short machine-readable reason for diagnostics, e.g. "NOT_IMPLEMENTED". */
        public final String code;
        public final long modelBytes;

        public Status(State state, String code, long modelBytes) {
            this.state = state;
            this.code = code;
            this.modelBytes = modelBytes;
        }

        public boolean isReady() {
            return state == State.READY;
        }
    }

    final class Result {
        public final String text;
        public final String error;
        public final long audioMs, decodeMs, inferenceMs;

        public Result(String text, String error, long audioMs, long decodeMs, long inferenceMs) {
            this.text = text;
            this.error = error;
            this.audioMs = audioMs;
            this.decodeMs = decodeMs;
            this.inferenceMs = inferenceMs;
        }

        public boolean ok() {
            return error == null && text != null;
        }
    }

    interface Callback {
        void onResult(Result result);
    }

    String id();

    String displayName();

    Status getStatus();

    /** Transcribes a complete voice/audio file (Opus/OGG or similar). */
    void transcribeFile(File audio, String language, Callback callback);

    /** Transcribes 16 kHz mono PCM captured for dictation. */
    void transcribePcm(short[] pcm16k, int length, String language, Callback callback);
}
