package org.telegram.messenger.tgplus.asr;

import java.util.Arrays;

/** Collects recorded samples up to a fixed length. Safe to fill from one thread and read from another. */
public final class PcmBuffer {

    private short[] data;
    private int size;
    private final int max;

    public PcmBuffer(int maxSamples) {
        this.max = Math.max(0, maxSamples);
        this.data = new short[Math.min(this.max, 16000)];
    }

    /** Adds up to {@code count} samples and returns how many fit. */
    public synchronized int append(short[] samples, int count) {
        int room = Math.min(count, max - size);
        if (room <= 0) return 0;
        if (size + room > data.length) {
            data = Arrays.copyOf(data, Math.min(max, Math.max(data.length * 2, size + room)));
        }
        System.arraycopy(samples, 0, data, size, room);
        size += room;
        return room;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isFull() {
        return size >= max;
    }

    public synchronized short[] toArray() {
        return Arrays.copyOf(data, size);
    }

    /** Loudness of a block from 0 to 1 (root mean square, scaled so ordinary speech reaches about 0.5). */
    public static float level(short[] samples, int count) {
        if (count <= 0) return 0f;
        double sum = 0;
        for (int i = 0; i < count; i++) sum += (double) samples[i] * samples[i];
        double rms = Math.sqrt(sum / count);
        return (float) Math.min(1.0, rms / 6000.0);
    }
}
