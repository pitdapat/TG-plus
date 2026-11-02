package org.telegram.messenger.tgplus.asr;

import java.io.File;

/**
 * On-device speech recognition engine. Phase 1 target: Parakeet TDT 0.6B v3 via sherpa-onnx.
 * Implementations run work off the main thread and deliver callbacks on the main thread.
 */
public interface AsrEngine {

    enum State {
        /** The model is not on the phone. */
        MODEL_MISSING,
        /** The model is being downloaded or unpacked. */
        DOWNLOADING,
        /** The model is installed and can be used. */
        READY,
        /** The engine cannot run on this phone (for example its native library does not load). */
        ERROR
    }

    final class Status {
        public final State state;
        /** Short machine-readable reason for diagnostics, e.g. "NATIVE_LOAD". */
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
        /** Error code (such as "LOW_MEMORY") or null on success. */
        public final String error;
        /** Extra detail for the developer-facing diagnostics, never shown as the transcript. */
        public final String detail;
        /** Length of the audio that was recognized. */
        public final long audioMs;
        /** Time spent reading or preparing the audio before recognition. */
        public final long decodeMs;
        /** Time spent in the recognizer itself. */
        public final long inferenceMs;
        /** Time spent loading the model for this call; 0 if it was already loaded. */
        public final long loadMs;
        /** Memory the process held right after recognition, in MB; -1 if unknown. */
        public final long memoryMb;
        /** Highest memory the process has held, in MB; -1 if unknown. */
        public final long peakMemoryMb;
        public final int threads;

        public Result(String text, String error, long audioMs, long decodeMs, long inferenceMs) {
            this(text, error, null, audioMs, decodeMs, inferenceMs, 0, -1, -1, 0);
        }

        public Result(String text, String error, String detail, long audioMs, long decodeMs, long inferenceMs,
                      long loadMs, long memoryMb, long peakMemoryMb, int threads) {
            this.text = text;
            this.error = error;
            this.detail = detail;
            this.audioMs = audioMs;
            this.decodeMs = decodeMs;
            this.inferenceMs = inferenceMs;
            this.loadMs = loadMs;
            this.memoryMb = memoryMb;
            this.peakMemoryMb = peakMemoryMb;
            this.threads = threads;
        }

        public boolean ok() {
            return error == null && text != null;
        }

        /** Audio seconds recognized per second of recognizer time, times ten (105 = 10.5x real time). */
        public long speedTimesTen() {
            return inferenceMs <= 0 ? 0 : audioMs * 10 / inferenceMs;
        }
    }

    interface Callback {
        void onResult(Result result);
    }

    String id();

    String displayName();

    Status getStatus();

    /** Transcribes a complete audio file. The spike reads WAV files; voice-note formats come with the next step. */
    void transcribeFile(File audio, String language, Callback callback);

    /** Transcribes 16 kHz mono PCM captured for dictation. */
    void transcribePcm(short[] pcm16k, int length, String language, Callback callback);

    /** Transcribes mono samples in [-1, 1] at any sample rate (the engine resamples). */
    void transcribeSamples(float[] samples, int sampleRate, String language, Callback callback);

    /** Frees the memory the loaded model holds. It is loaded again by the next call. */
    void release();
}
