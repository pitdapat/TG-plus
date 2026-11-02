package org.telegram.messenger.tgplus.asr;

/** Turns decoded audio into the 16 kHz mono 16-bit samples the recognizer takes. */
public final class PcmConverter {

    public static final int TARGET_RATE = 16000;

    private PcmConverter() {}

    /**
     * Averages the channels of interleaved samples into mono. Reads {@code frames} frames starting at
     * {@code offset} (in samples) and writes them to {@code out} from {@code outOffset}.
     */
    public static void downmix(short[] interleaved, int offset, int frames, int channels, short[] out, int outOffset) {
        if (channels <= 1) {
            System.arraycopy(interleaved, offset, out, outOffset, frames);
            return;
        }
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            int base = offset + i * channels;
            for (int c = 0; c < channels; c++) sum += interleaved[base + c];
            out[outOffset + i] = (short) (sum / channels);
        }
    }

    /** Resamples mono audio to 16 kHz by averaging the source samples that fall into each output sample. */
    public static short[] toMono16k(short[] mono, int count, int sampleRate) {
        if (mono == null || count <= 0 || sampleRate <= 0) return new short[0];
        if (sampleRate == TARGET_RATE) {
            short[] copy = new short[count];
            System.arraycopy(mono, 0, copy, 0, count);
            return copy;
        }
        int outCount = (int) ((long) count * TARGET_RATE / sampleRate);
        if (outCount <= 0) return new short[0];
        short[] out = new short[outCount];
        double ratio = (double) sampleRate / TARGET_RATE;
        for (int i = 0; i < outCount; i++) {
            double from = i * ratio;
            double to = Math.min(count, from + ratio);
            int a = (int) from;
            int b = Math.max(a + 1, (int) Math.ceil(to));
            if (b > count) b = count;
            double sum = 0;
            double weight = 0;
            for (int j = a; j < b; j++) {
                double w = Math.min(j + 1, to) - Math.max(j, from);
                if (w <= 0) continue;
                sum += mono[j] * w;
                weight += w;
            }
            out[i] = (short) Math.round(weight > 0 ? sum / weight : 0);
        }
        return out;
    }
}
