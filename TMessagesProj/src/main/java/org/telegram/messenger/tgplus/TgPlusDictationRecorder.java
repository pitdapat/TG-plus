package org.telegram.messenger.tgplus;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.tgplus.asr.PcmBuffer;

/** Records the microphone at 16 kHz mono into memory for dictation. */
public final class TgPlusDictationRecorder {

    public interface Listener {
        /** Main thread. Loudness of the latest block, 0 to 1. */
        void onLevel(float level);

        /** Main thread. The maximum length was reached and recording stopped by itself. */
        void onLimit();
    }

    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_SECONDS = 120;

    private AudioRecord recorder;
    private Thread thread;
    private volatile boolean running;
    private PcmBuffer buffer;

    /** Starts recording. Returns null on success or an error code such as MIC_INIT. */
    public String start(Listener listener) {
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return "MIC_UNSUPPORTED";
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLE_RATE * 2));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = null;
                return "MIC_INIT";
            }
            buffer = new PcmBuffer(SAMPLE_RATE * MAX_SECONDS);
            recorder.startRecording();
        } catch (Throwable t) {
            release();
            return "MIC_ERROR";
        }
        running = true;
        final AudioRecord local = recorder;
        final PcmBuffer target = buffer;
        thread = new Thread(() -> {
            short[] block = new short[1600];
            boolean hitLimit = false;
            while (running) {
                int read = local.read(block, 0, block.length);
                if (read < 0) break;
                if (read == 0) continue;
                target.append(block, read);
                final float level = PcmBuffer.level(block, read);
                AndroidUtilities.runOnUIThread(() -> {
                    if (running) listener.onLevel(level);
                });
                if (target.isFull()) {
                    hitLimit = true;
                    break;
                }
            }
            if (hitLimit && running) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (running) listener.onLimit();
                });
            }
        }, "TgPlusDictation");
        thread.start();
        return null;
    }

    /** Stops recording and returns everything recorded; empty if nothing was recorded. */
    public short[] stop() {
        running = false;
        try {
            if (thread != null) thread.join(1000);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        short[] result = buffer == null ? new short[0] : buffer.toArray();
        release();
        return result;
    }

    /** Stops recording and drops the audio. */
    public void cancel() {
        stop();
    }

    private void release() {
        running = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Throwable ignore) {
            }
            try {
                recorder.release();
            } catch (Throwable ignore) {
            }
            recorder = null;
        }
    }
}
