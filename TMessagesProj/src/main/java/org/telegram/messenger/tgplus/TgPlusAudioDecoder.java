package org.telegram.messenger.tgplus;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.telegram.messenger.tgplus.asr.PcmConverter;
import org.telegram.messenger.tgplus.asr.TranscriptionRouting;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Decodes the audio track of a video file (such as a round video) with the phone's own decoders. */
public final class TgPlusAudioDecoder {

    private static final long TIMEOUT_US = 10_000;

    private TgPlusAudioDecoder() {}

    /**
     * Returns 16 kHz mono samples, or null if the file has no readable audio. Decoding stops a little
     * after the longest length that is transcribed, so the caller can tell it is too long.
     */
    public static short[] decodeTo16k(File file) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0) return null;
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : PcmConverter.TARGET_RATE;
            short[] mono = new short[rate * 10];
            int count = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat changed = codec.getOutputFormat();
                    if (changed.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (changed.containsKey(MediaFormat.KEY_SAMPLE_RATE) && changed.getInteger(MediaFormat.KEY_SAMPLE_RATE) != rate) {
                        // The rate is fixed by the first output; a change midway is not expected in these files.
                        return null;
                    }
                } else if (out >= 0) {
                    if (info.size > 0 && channels > 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(out);
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        short[] pcm = new short[info.size / 2];
                        buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm);
                        int frames = pcm.length / channels;
                        if (count + frames > mono.length) {
                            mono = Arrays.copyOf(mono, Math.max(mono.length * 2, count + frames));
                        }
                        PcmConverter.downmix(pcm, 0, frames, channels, mono, count);
                        count += frames;
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    if (count > (long) (TranscriptionRouting.MAX_AUDIO_SECONDS + 1) * rate) outputDone = true;
                }
            }
            if (count == 0) return null;
            return PcmConverter.toMono16k(mono, count, rate);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignore) {}
            extractor.release();
        }
    }
}
