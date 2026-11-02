package org.telegram.messenger.tgplus.asr;

/**
 * Prepares a short dictated clip for the recognizer. Very short clips with speech right at the edges, and
 * quiet recordings, often come back empty, so the clip gets a little silence at both ends and a quiet
 * recording is brought up to a normal level.
 */
public final class DictationAudio {

    public static final int LEAD_MS = 300;
    public static final int TAIL_MS = 800;
    /** Recordings with a peak below this (of full scale) are amplified. */
    public static final float QUIET_PEAK = 0.30f;
    public static final float TARGET_PEAK = 0.50f;
    public static final float MAX_GAIN = 20f;
    /** Below this peak the microphone effectively heard nothing. */
    public static final float SILENT_PEAK = 0.015f;

    private DictationAudio() {}

    public static final class Prepared {
        public final float[] samples;
        public final float peak;
        public final float gain;

        Prepared(float[] samples, float peak, float gain) {
            this.samples = samples;
            this.peak = peak;
            this.gain = gain;
        }

        public boolean silent() {
            return peak < SILENT_PEAK;
        }
    }

    /** Largest absolute sample as a fraction of full scale. */
    public static float peak(short[] pcm, int length) {
        int max = 0;
        for (int i = 0; i < length; i++) {
            int v = Math.abs((int) pcm[i]);
            if (v > max) max = v;
        }
        return max / 32768f;
    }

    public static Prepared prepare(short[] pcm, int length, int sampleRate) {
        float peak = peak(pcm, length);
        float gain = 1f;
        if (peak > 0f && peak < QUIET_PEAK && peak >= SILENT_PEAK / 4) {
            gain = Math.min(MAX_GAIN, TARGET_PEAK / peak);
        }
        int lead = (int) ((long) LEAD_MS * sampleRate / 1000);
        int tail = (int) ((long) TAIL_MS * sampleRate / 1000);
        float[] out = new float[lead + length + tail];
        for (int i = 0; i < length; i++) {
            float v = pcm[i] / 32768f * gain;
            out[lead + i] = v > 1f ? 1f : (v < -1f ? -1f : v);
        }
        return new Prepared(out, peak, gain);
    }
}
