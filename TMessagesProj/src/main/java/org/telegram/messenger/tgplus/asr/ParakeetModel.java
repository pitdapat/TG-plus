package org.telegram.messenger.tgplus.asr;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Parakeet TDT 0.6B v3 model package (int8 ONNX export for sherpa-onnx) and everything pinned about it.
 *
 * The app downloads the one tarball that sherpa-onnx publishes for it, checks the tarball's SHA-256, then
 * unpacks only the files listed here and checks each one again. Anything else in the archive is ignored.
 * Values were measured from the published file and match the SHA-256 GitHub reports for the release asset.
 */
public final class ParakeetModel {

    public static final String ID = "parakeet-tdt-0.6b-v3-int8";
    public static final String DISPLAY_NAME = "Parakeet TDT 0.6B v3";

    /** Version of the sherpa-onnx runtime this model is run with. Keep in sync with gradle/sherpa-onnx.gradle. */
    public static final String RUNTIME_VERSION = "2.0.1";

    public static final String ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2";
    public static final String ARCHIVE_NAME = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2";
    public static final long ARCHIVE_BYTES = 487170055L;
    public static final String ARCHIVE_SHA256 = "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf";

    private static final String DIR = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/";

    /** A file inside the archive and where it ends up on the phone. */
    public static final class Entry {
        /** Path inside the tar archive. */
        public final String archivePath;
        /** File name inside the model folder on the phone. */
        public final String fileName;
        public final long size;
        public final String sha256;

        public Entry(String archivePath, String fileName, long size, String sha256) {
            this.archivePath = archivePath;
            this.fileName = fileName;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public static final Entry ENCODER = new Entry(DIR + "encoder.int8.onnx", "encoder.int8.onnx", 652184281L,
        "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247");
    public static final Entry DECODER = new Entry(DIR + "decoder.int8.onnx", "decoder.int8.onnx", 11845275L,
        "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e");
    public static final Entry JOINER = new Entry(DIR + "joiner.int8.onnx", "joiner.int8.onnx", 6355277L,
        "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3");
    public static final Entry TOKENS = new Entry(DIR + "tokens.txt", "tokens.txt", 93939L,
        "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d");
    /** A 3.9 s English recording shipped in the archive; used by the self-test. 24 kHz, 16-bit, mono. */
    public static final Entry SAMPLE = new Entry(DIR + "test_wavs/en.wav", "sample-en.wav", 184608L,
        "148b936b43ce7c546a866e64da059f0458aee2d65e617f16e9d94f06e8d99ed6");

    public static final String SAMPLE_TEXT = "Ask not what your country can do for you, ask what you can do for your country.";

    public static final List<Entry> FILES = Collections.unmodifiableList(Arrays.asList(ENCODER, DECODER, JOINER, TOKENS, SAMPLE));

    /** Written after every file has been verified; a folder without it is not trusted. */
    public static final String MARKER_NAME = ".verified";

    /** Bytes the unpacked files take on the phone. */
    public static long installedBytes() {
        long total = 0;
        for (Entry e : FILES) total += e.size;
        return total;
    }

    /** Free space needed to download and unpack: the archive and the files exist side by side for a while. */
    public static long requiredFreeBytes() {
        return ARCHIVE_BYTES + installedBytes() + 100L * 1024 * 1024;
    }

    private ParakeetModel() {}
}
