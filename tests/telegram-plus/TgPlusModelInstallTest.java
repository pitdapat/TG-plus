import org.telegram.messenger.tgplus.asr.ModelInstaller;
import org.telegram.messenger.tgplus.asr.ParakeetModel;
import org.telegram.messenger.tgplus.asr.TarReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Tar reading and verified model installation, with synthetic archives. */
public class TgPlusModelInstallTest {
    static int checks;

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    static String hex(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // Tar writer: ustar headers, plus GNU long-name and pax extension records.
    static byte[] header(String name, long size, char type, String prefix) {
        byte[] h = new byte[512];
        put(h, 0, 100, name);
        put(h, 100, 8, "0000644");
        put(h, 108, 8, "0000000");
        put(h, 116, 8, "0000000");
        put(h, 124, 12, String.format("%011o", size));
        put(h, 136, 12, "00000000000");
        Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = (byte) type;
        put(h, 257, 6, "ustar");
        put(h, 263, 2, "00");
        if (prefix != null) put(h, 345, 155, prefix);
        long sum = 0;
        for (byte b : h) sum += b & 0xff;
        put(h, 148, 7, String.format("%06o", sum));
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    static void put(byte[] h, int off, int len, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(b.length, len - 1 + (len == 12 ? 1 : 0) - (len == 12 ? 0 : 0)));
    }

    static void entry(ByteArrayOutputStream out, String name, byte[] data, char type) throws IOException {
        out.write(header(name, data.length, type, null));
        out.write(data);
        out.write(new byte[(512 - data.length % 512) % 512]);
    }

    static byte[] longName(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        entry(out, "././@LongLink", (name + "\0").getBytes(StandardCharsets.UTF_8), 'L');
        return out.toByteArray();
    }

    static byte[] finish(ByteArrayOutputStream out) throws IOException {
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static ParakeetModel.Entry model(String path, String file, byte[] data) throws Exception {
        return new ParakeetModel.Entry(path, file, data.length, hex(data));
    }

    static File tempDir() throws IOException {
        return Files.createTempDirectory("tgplus-model").toFile();
    }

    static boolean installFails(byte[] tar, File folder, List<ParakeetModel.Entry> files, String code) {
        try {
            ModelInstaller.install(new ByteArrayInputStream(tar), folder, files, "m", null, null);
            return false;
        } catch (IOException e) {
            return e.getMessage().startsWith(code);
        }
    }

    public static void main(String[] args) throws Exception {
        // --- TarReader.
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        byte[] a = random(1000, 1), b = random(512, 2), c = new byte[0];
        entry(t, "dir/", new byte[0], '5');
        entry(t, "dir/a.bin", a, '0');
        entry(t, "dir/b.bin", b, '0');
        entry(t, "dir/empty", c, '0');
        TarReader reader = new TarReader(new ByteArrayInputStream(finish(t)));
        TarReader.Entry e = reader.next();
        check(e != null && e.name.equals("dir/") && !e.regularFile, "directory entry");
        e = reader.next();
        check(e.name.equals("dir/a.bin") && e.size == 1000 && e.regularFile, "first file header");
        e = reader.next(); // skip unread data of a.bin including padding
        check(e.name.equals("dir/b.bin") && e.size == 512, "skips unread data and padding");
        byte[] got = new byte[512];
        int n = 0, r;
        while ((r = reader.read(got, n, got.length - n)) > 0) n += r;
        check(n == 512 && Arrays.equals(got, b), "reads exact content, block-sized file has no padding");
        e = reader.next();
        check(e.name.equals("dir/empty") && e.size == 0, "empty file");
        check(reader.next() == null, "end of archive");

        // Long names (GNU) and ustar prefix.
        String longPath = "very/" + String.join("/", Collections(30, "segment")) + "/file.bin";
        t = new ByteArrayOutputStream();
        t.write(longName(longPath));
        entry(t, "truncated-name", a, '0');
        t.write(header("tail.bin", 3, '0', "some/prefix"));
        t.write(new byte[]{1, 2, 3});
        t.write(new byte[509]);
        reader = new TarReader(new ByteArrayInputStream(finish(t)));
        e = reader.next();
        check(e.name.equals(longPath) && e.size == 1000, "GNU long name: " + e.name);
        e = reader.next();
        check(e.name.equals("some/prefix/tail.bin"), "ustar prefix: " + e.name);

        // Damaged archives are rejected with a code, never silently accepted.
        byte[] good = finish(entryTar("x.bin", a));
        byte[] badSum = good.clone();
        badSum[10] ^= 1;
        check(throwsCode(() -> new TarReader(new ByteArrayInputStream(badSum)).next(), "BAD_TAR"), "checksum error detected");
        byte[] cut = Arrays.copyOf(good, 700);
        check(throwsCode(() -> {
            TarReader tr = new TarReader(new ByteArrayInputStream(cut));
            tr.next();
            tr.next();
        }, "BAD_TAR") || throwsEof(cut), "truncated archive detected");
        check(new TarReader(new ByteArrayInputStream(new byte[0])).next() == null, "empty stream is an empty archive");

        // --- ModelInstaller.
        byte[] enc = random(300_000, 10), dec = random(50_000, 11), tok = "<unk> 0\nhello 1\n".getBytes(StandardCharsets.UTF_8);
        ParakeetModel.Entry fe = model("pkg/encoder.onnx", "encoder.onnx", enc);
        ParakeetModel.Entry fd = model("pkg/decoder.onnx", "decoder.onnx", dec);
        ParakeetModel.Entry ft = model("pkg/tokens.txt", "tokens.txt", tok);
        List<ParakeetModel.Entry> files = Arrays.asList(fe, fd, ft);

        t = new ByteArrayOutputStream();
        entry(t, "pkg/", new byte[0], '5');
        entry(t, "pkg/README.md", random(777, 12), '0');          // not wanted: ignored
        entry(t, "pkg/tokens.txt", tok, '0');                       // order in archive differs from the list
        entry(t, "../escape.txt", random(10, 13), '0');             // not wanted: must never be written
        entry(t, "pkg/encoder.onnx", enc, '0');
        entry(t, "pkg/decoder.onnx", dec, '0');
        byte[] okTar = finish(t);

        File root = tempDir();
        File folder = new File(root, "model");
        long[] lastProgress = new long[2];
        ModelInstaller.install(new ByteArrayInputStream(okTar), folder, files, "parakeet", null, (done, total) -> {
            lastProgress[0] = done;
            lastProgress[1] = total;
        });
        check(ModelInstaller.isInstalled(folder, files), "installed model is recognised");
        check(Arrays.equals(Files.readAllBytes(new File(folder, "encoder.onnx").toPath()), enc), "encoder content");
        check(lastProgress[0] == lastProgress[1] && lastProgress[1] == enc.length + dec.length + tok.length, "progress reaches 100%");
        List<String> names = new ArrayList<>(Arrays.asList(folder.list()));
        java.util.Collections.sort(names);
        check(names.equals(Arrays.asList(".verified", "decoder.onnx", "encoder.onnx", "tokens.txt")), "only wanted files + marker: " + names);
        check(!new File(root, "escape.txt").exists() && !new File(folder, "README.md").exists(), "unwanted and path-traversal entries ignored");
        check(!new File(root, "model.tmp").exists() && !new File(root, "model.old").exists(), "no temp folders left");
        check(ModelInstaller.verifyAll(folder, files) == null, "verifyAll passes on a good install");

        // Damage after install is noticed.
        Files.write(new File(folder, "decoder.onnx").toPath(), random(50_000, 99));
        check(ModelInstaller.isInstalled(folder, files), "isInstalled is a cheap size check");
        check(ModelInstaller.verifyAll(folder, files).startsWith("DIGEST_MISMATCH"), "verifyAll finds changed content");
        Files.write(new File(folder, "decoder.onnx").toPath(), Arrays.copyOf(dec, 10));
        check(!ModelInstaller.isInstalled(folder, files), "size change makes the model not installed");

        // A failed install must leave an existing good install untouched and clean up after itself.
        File folder2 = new File(root, "model2");
        ModelInstaller.install(new ByteArrayInputStream(okTar), folder2, files, "m", null, null);
        byte[] before = Files.readAllBytes(new File(folder2, "encoder.onnx").toPath());
        ByteArrayOutputStream corrupt = new ByteArrayOutputStream();
        byte[] enc2 = enc.clone();
        enc2[1234] ^= 0x55;
        entry(corrupt, "pkg/encoder.onnx", enc2, '0');
        entry(corrupt, "pkg/decoder.onnx", dec, '0');
        entry(corrupt, "pkg/tokens.txt", tok, '0');
        check(installFails(finish(corrupt), folder2, files, "DIGEST_MISMATCH"), "corrupted file is rejected");
        check(Arrays.equals(Files.readAllBytes(new File(folder2, "encoder.onnx").toPath()), before) && ModelInstaller.isInstalled(folder2, files), "previous install survives a failed one");
        check(!new File(root, "model2.tmp").exists(), "failed install cleans its temp folder");

        File folder3 = new File(root, "model3");
        check(installFails(finish(entryTar("pkg/encoder.onnx", enc)), folder3, files, "MISSING_FILE"), "missing files are rejected");
        check(!folder3.exists() && !new File(root, "model3.tmp").exists(), "nothing is left behind");

        ByteArrayOutputStream wrongSize = new ByteArrayOutputStream();
        entry(wrongSize, "pkg/encoder.onnx", Arrays.copyOf(enc, enc.length - 1), '0');
        check(installFails(finish(wrongSize), folder3, files, "SIZE_MISMATCH"), "wrong size is rejected before reading it");

        ByteArrayOutputStream dup = new ByteArrayOutputStream();
        entry(dup, "pkg/encoder.onnx", enc, '0');
        entry(dup, "pkg/encoder.onnx", enc, '0');
        check(installFails(finish(dup), folder3, files, "DUPLICATE"), "duplicate entries are rejected");

        ByteArrayOutputStream link = new ByteArrayOutputStream();
        entry(link, "pkg/encoder.onnx", new byte[0], '2'); // symlink entry with the wanted name
        entry(link, "pkg/decoder.onnx", dec, '0');
        entry(link, "pkg/tokens.txt", tok, '0');
        check(installFails(finish(link), folder3, files, "MISSING_FILE"), "links are never treated as files");

        boolean[] cancel = {false};
        check(throwsCode(() -> ModelInstaller.install(new ByteArrayInputStream(okTar), new File(root, "model4"), files, "m", () -> cancel[0] = true, null), "CANCELLED"), "cancel stops the install");
        check(!new File(root, "model4").exists() && !new File(root, "model4.tmp").exists(), "cancel leaves nothing behind");

        // Constants: format of the pinned values (their correctness is checked against the real files separately).
        for (ParakeetModel.Entry f : ParakeetModel.FILES) {
            check(f.sha256.matches("[0-9a-f]{64}") && f.size > 0 && f.archivePath.startsWith("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/"), "pinned entry " + f.fileName);
        }
        check(ParakeetModel.ARCHIVE_SHA256.matches("[0-9a-f]{64}") && ParakeetModel.ARCHIVE_URL.startsWith("https://"), "pinned archive");
        check(ParakeetModel.installedBytes() > 600_000_000L && ParakeetModel.requiredFreeBytes() > ParakeetModel.ARCHIVE_BYTES + ParakeetModel.installedBytes(), "size maths");

        ModelInstaller.deleteRecursively(root);
        System.out.println(checks + " Telegram Plus model install checks passed");
    }

    static List<String> Collections(int n, String s) {
        ArrayList<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(s + i);
        return l;
    }

    static ByteArrayOutputStream entryTar(String name, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        entry(out, name, data, '0');
        return out;
    }

    interface Thrower {
        void run() throws Exception;
    }

    static boolean throwsCode(Thrower r, String code) {
        try {
            r.run();
            return false;
        } catch (IOException e) {
            return e.getMessage() != null && e.getMessage().startsWith(code);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean throwsEof(byte[] data) {
        try {
            TarReader tr = new TarReader(new ByteArrayInputStream(data));
            tr.next();
            tr.next();
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
