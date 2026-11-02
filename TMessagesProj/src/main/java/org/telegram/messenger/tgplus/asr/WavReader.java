package org.telegram.messenger.tgplus.asr;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads uncompressed 16-bit PCM WAV files into mono floats in [-1, 1]. Stereo is averaged to mono. */
public final class WavReader {

    public static final class Wav {
        public final float[] samples;
        public final int sampleRate;

        Wav(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }

        public long durationMs() {
            return sampleRate <= 0 ? 0 : samples.length * 1000L / sampleRate;
        }
    }

    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private WavReader() {}

    public static Wav read(File file) throws IOException {
        if (file.length() > MAX_BYTES) throw new IOException("UNSUPPORTED_FORMAT: WAV larger than " + MAX_BYTES + " bytes");
        try (InputStream in = new FileInputStream(file)) {
            return read(in);
        }
    }

    public static Wav read(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            bytes.write(buffer, 0, n);
            if (bytes.size() > MAX_BYTES) throw new IOException("UNSUPPORTED_FORMAT: WAV larger than " + MAX_BYTES + " bytes");
        }
        return parse(bytes.toByteArray());
    }

    static Wav parse(byte[] data) throws IOException {
        if (data.length < 44 || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
            || data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') {
            throw new IOException("UNSUPPORTED_FORMAT: not a WAV file");
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int format = -1, channels = 0, rate = 0, bits = 0;
        int dataOffset = -1;
        long dataLength = 0;
        int pos = 12;
        while (pos + 8 <= data.length) {
            String id = new String(data, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long length = b.getInt(pos + 4) & 0xffffffffL;
            int body = pos + 8;
            if (id.equals("fmt ")) {
                if (length < 16 || body + 16 > data.length) throw new IOException("UNSUPPORTED_FORMAT: bad fmt chunk");
                format = b.getShort(body) & 0xffff;
                channels = b.getShort(body + 2) & 0xffff;
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14) & 0xffff;
                if (format == 0xFFFE && length >= 26) format = b.getShort(body + 24) & 0xffff; // WAVE_FORMAT_EXTENSIBLE
            } else if (id.equals("data")) {
                dataOffset = body;
                // A streaming writer may leave the length at 0 or 0xFFFFFFFF: use what is there.
                dataLength = (length == 0 || length == 0xffffffffL || body + length > data.length) ? data.length - body : length;
                break;
            }
            pos = (int) Math.min(Integer.MAX_VALUE, (long) body + length + (length & 1));
        }
        if (format != 1) throw new IOException("UNSUPPORTED_FORMAT: WAV format " + format + " (only PCM)");
        if (bits != 16) throw new IOException("UNSUPPORTED_FORMAT: " + bits + "-bit WAV (only 16-bit)");
        if (channels < 1 || channels > 2 || rate < 8000 || rate > 192000) throw new IOException("UNSUPPORTED_FORMAT: " + channels + " channels at " + rate + " Hz");
        if (dataOffset < 0) throw new IOException("UNSUPPORTED_FORMAT: no data chunk");

        int frames = (int) (dataLength / (2L * channels));
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += b.getShort(dataOffset + (i * channels + c) * 2);
            out[i] = sum / (float) channels / 32768f;
        }
        return new Wav(out, rate);
    }
}
