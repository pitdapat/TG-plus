import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.telegram.messenger.tgplus.asr.ModelInstaller;
import org.telegram.messenger.tgplus.asr.ResumableDownloader;
import org.telegram.messenger.tgplus.asr.TextMetrics;
import org.telegram.messenger.tgplus.asr.WavReader;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/** Resumable download against a local server that misbehaves on purpose, plus WAV and text helpers. */
public class TgPlusDownloaderTest {
    static int checks;

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    static String sha(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** Server behaviours. */
    static volatile String mode = "normal";
    static final AtomicInteger requests = new AtomicInteger();
    static volatile String lastRange;
    static volatile byte[] payload;
    static volatile byte[] served; // what the server actually sends (differs from payload for corruption tests)

    public static void main(String[] args) throws Exception {
        payload = new byte[3_000_000];
        new Random(7).nextBytes(payload);
        served = payload;
        String digest = sha(payload);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/file", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                requests.incrementAndGet();
                String range = ex.getRequestHeaders().getFirst("Range");
                lastRange = range;
                byte[] data = served;
                try {
                    if (mode.equals("redirect") && !"final".equals(ex.getRequestURI().getQuery())) {
                        ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/file?final");
                        ex.sendResponseHeaders(302, -1);
                        return;
                    }
                    if (mode.equals("503")) {
                        ex.sendResponseHeaders(503, -1);
                        return;
                    }
                    if (mode.equals("too-much")) {
                        ex.sendResponseHeaders(200, 0); // chunked: length unknown to the client
                        OutputStream o = ex.getResponseBody();
                        o.write(data);
                        o.write(new byte[50_000]);
                        o.close();
                        return;
                    }
                    if (mode.equals("404")) {
                        ex.sendResponseHeaders(404, -1);
                        return;
                    }
                    long start = 0;
                    if (range != null && !mode.equals("ignore-range")) {
                        start = Long.parseLong(range.replace("bytes=", "").replace("-", ""));
                        if (mode.equals("416")) {
                            ex.getResponseHeaders().add("Content-Range", "bytes */" + data.length);
                            ex.sendResponseHeaders(416, -1);
                            return;
                        }
                        if (mode.equals("wrong-offset")) start = Math.max(0, start - 100);
                        ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
                        ex.sendResponseHeaders(206, data.length - start);
                    } else {
                        ex.sendResponseHeaders(200, data.length);
                    }
                    int end = data.length;
                    // "drop" and "drop-once" cut the connection after 1 MB of the body.
                    boolean drop = mode.equals("drop") || (mode.equals("drop-once") && requests.get() == 1);
                    OutputStream out = ex.getResponseBody();
                    if (drop) end = (int) Math.min(data.length, start + 1_000_000);
                    for (int off = (int) start; off < end; off += 65536) {
                        out.write(data, off, Math.min(65536, end - off));
                    }
                    if (drop) {
                        ex.getResponseBody().flush();
                        ex.getHttpContext(); // keep the exchange open then abort the socket by closing without finishing the body
                        ex.close();
                        return;
                    }
                    out.close();
                } catch (IOException ignored) {
                } finally {
                    ex.close();
                }
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + port + "/file";
        File dir = Files.createTempDirectory("tgplus-dl").toFile();

        try {
            // 1. Plain download.
            File part = new File(dir, "a.part"), target = new File(dir, "a.bin");
            long[] seen = new long[2];
            ResumableDownloader d = new ResumableDownloader();
            d.download(url, part, target, payload.length, digest, null, (done, total) -> { seen[0] = done; seen[1] = total; });
            check(Arrays.equals(Files.readAllBytes(target.toPath()), payload) && !part.exists(), "plain download is exact and removes .part");
            check(seen[0] == payload.length && seen[1] == payload.length && d.resumedFrom == 0, "progress reaches 100%, not resumed");

            // 2. Already complete: no request at all.
            int before = requests.get();
            new ResumableDownloader().download(url, part, target, payload.length, digest, null, null);
            check(requests.get() == before, "a verified file is not downloaded again");

            // 3. Cancel mid-way keeps the partial file; the next call resumes with a Range request.
            target.delete();
            boolean[] cancel = {false};
            int[] chunks = {0};
            try {
                new ResumableDownloader().download(url, part, target, payload.length, digest, () -> cancel[0], (done, total) -> {
                    if (++chunks[0] == 4) cancel[0] = true;
                });
                check(false, "cancel should throw");
            } catch (IOException e) {
                check(e.getMessage().startsWith("CANCELLED"), "cancel code: " + e.getMessage());
            }
            long partial = part.length();
            check(partial > 0 && partial < payload.length && !target.exists(), "partial file kept: " + partial);
            d = new ResumableDownloader();
            d.download(url, part, target, payload.length, digest, null, null);
            check(lastRange.equals("bytes=" + partial + "-") && d.resumedFrom == partial, "resumed with Range from " + partial + ": " + lastRange);
            check(Arrays.equals(Files.readAllBytes(target.toPath()), payload), "resumed download is exact (hash covers the first part too)");

            // 4. Connection drops once: automatic retry resumes and succeeds.
            target.delete(); part.delete();
            mode = "drop-once";
            requests.set(0);
            new ResumableDownloader().download(url, part, target, payload.length, digest, null, null);
            check(requests.get() >= 2 && Arrays.equals(Files.readAllBytes(target.toPath()), payload), "a dropped connection is retried and resumed: requests=" + requests.get());

            // 5. Server ignores Range: restarts cleanly from zero.
            target.delete();
            Files.write(part.toPath(), Arrays.copyOf(payload, 500_000));
            mode = "ignore-range";
            d = new ResumableDownloader();
            d.download(url, part, target, payload.length, digest, null, null);
            check(Arrays.equals(Files.readAllBytes(target.toPath()), payload) && d.resumedFrom == 0, "ignored Range -> full body used, no corruption");

            // 6. 416 and a wrong Content-Range offset: start over rather than append garbage.
            target.delete();
            Files.write(part.toPath(), Arrays.copyOf(payload, 700_000));
            mode = "416";
            requests.set(0);
            d = new ResumableDownloader();
            d.download(url, part, target, payload.length, digest, null, null);
            check(requests.get() == 2 && Arrays.equals(Files.readAllBytes(target.toPath()), payload), "a 416 makes the same call start over and succeed: requests=" + requests.get());
            mode = "normal";
            target.delete();
            Files.write(part.toPath(), Arrays.copyOf(payload, 700_000));
            mode = "wrong-offset";
            new ResumableDownloader().download(url, part, target, payload.length, digest, null, null);
            check(Arrays.equals(Files.readAllBytes(target.toPath()), payload), "a Content-Range that starts at the wrong offset is not trusted");
            mode = "normal";

            // 7. A part file that is bigger than expected is discarded.
            target.delete();
            Files.write(part.toPath(), new byte[payload.length + 10]);
            new ResumableDownloader().download(url, part, target, payload.length, digest, null, null);
            check(Arrays.equals(Files.readAllBytes(target.toPath()), payload), "an oversized .part is discarded");

            // 8. Corrupted bytes on the server: rejected, nothing kept.
            target.delete(); part.delete();
            served = payload.clone();
            served[1_500_000] ^= 1;
            check(fails(() -> new ResumableDownloader().download(url, part, target, payload.length, digest, null, null), "DIGEST_MISMATCH"), "one flipped bit is detected");
            check(!target.exists() && !part.exists(), "a bad download leaves no file behind");
            served = payload;

            // 8b. A server that keeps sending past the expected size is stopped, and nothing is kept.
            mode = "too-much"; target.delete(); part.delete();
            check(fails(() -> new ResumableDownloader().download(url, part, target, payload.length, digest, null, null), "SIZE_MISMATCH"), "extra bytes are refused");
            mode = "normal";

            // 9. Wrong size announced by the server.
            mode = "normal";
            check(fails(() -> new ResumableDownloader().download(url, part, target, payload.length + 5, digest, null, null), "SIZE_MISMATCH"), "wrong size is rejected");
            part.delete();

            // 10. HTTP errors: 404 is final, 503 is retried a few times then reported.
            mode = "404"; requests.set(0);
            check(fails(() -> new ResumableDownloader().download(url, part, target, payload.length, digest, null, null), "HTTP_404") && requests.get() == 1, "404 fails at once");
            mode = "503"; requests.set(0);
            check(fails(() -> new ResumableDownloader().download(url, part, target, payload.length, digest, null, null), "HTTP_503") && requests.get() == 3, "503 is retried 3 times: " + requests.get());
            mode = "normal";

            // 11. Redirect keeps working (GitHub redirects to a CDN).
            mode = "redirect"; target.delete(); part.delete();
            new ResumableDownloader().download(url, part, target, payload.length, digest, null, null);
            check(Arrays.equals(Files.readAllBytes(target.toPath()), payload), "redirected download works");
            mode = "normal";

            // 12. Cancel during the initial hash of a large partial file.
            target.delete();
            Files.write(part.toPath(), Arrays.copyOf(payload, 2_000_000));
            check(fails(() -> new ResumableDownloader().download(url, part, target, payload.length, digest, () -> true, null), "CANCELLED") && part.length() == 2_000_000, "cancel before starting keeps the partial file");

            // 13. Unreachable server gets a NETWORK code, not a raw exception.
            server.stop(0);
            part.delete();
            IOException failure = null;
            try {
                new ResumableDownloader().download(url, part, target, payload.length, digest, null, null);
            } catch (IOException e) {
                failure = e;
            }
            check(failure != null && (failure.getMessage().startsWith("NETWORK") || failure.getMessage().startsWith("TIMEOUT")), "unreachable server: " + failure);
        } finally {
            server.stop(0);
            ModelInstaller.deleteRecursively(dir);
        }

        // --- WavReader.
        byte[] wav = wav(16000, 1, new short[]{0, 16384, -16384, 32767, -32768});
        WavReader.Wav w = WavReader.read(new java.io.ByteArrayInputStream(wav));
        check(w.sampleRate == 16000 && w.samples.length == 5 && w.samples[1] == 0.5f && w.samples[2] == -0.5f && w.samples[4] == -1f, "16-bit mono samples scale to [-1, 1]");
        w = WavReader.read(new java.io.ByteArrayInputStream(wav(44100, 2, new short[]{1000, 3000, -2000, 2000})));
        check(w.samples.length == 2 && Math.abs(w.samples[0] - 2000 / 32768f) < 1e-6 && w.samples[1] == 0f && w.durationMs() == 0, "stereo is averaged to mono");
        byte[] streaming = wav(22050, 1, new short[]{100, 200, 300});
        ByteBuffer.wrap(streaming).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 0xffffffff);
        check(WavReader.read(new java.io.ByteArrayInputStream(streaming)).samples.length == 3, "a streaming WAV with unknown length is read to the end");
        byte[] withList = wav(16000, 1, new short[]{1, 2});
        byte[] extra = new byte[withList.length + 12];
        System.arraycopy(withList, 0, extra, 0, 36);
        System.arraycopy(new byte[]{'L', 'I', 'S', 'T', 3, 0, 0, 0, 'a', 'b', 'c', 0}, 0, extra, 36, 12);
        System.arraycopy(withList, 36, extra, 48, withList.length - 36);
        check(WavReader.read(new java.io.ByteArrayInputStream(extra)).samples.length == 2, "unknown chunks (odd length, padded) are skipped");
        check(fails(() -> WavReader.read(new java.io.ByteArrayInputStream("not a wav file at all, definitely not a wav file".getBytes())), "UNSUPPORTED_FORMAT"), "not a WAV");
        byte[] eightBit = wav(16000, 1, new short[]{1, 2});
        eightBit[34] = 8;
        check(fails(() -> WavReader.read(new java.io.ByteArrayInputStream(eightBit)), "UNSUPPORTED_FORMAT"), "8-bit WAV is refused, not misread");

        // --- Text comparison.
        check(TextMetrics.wordErrorRate("Ask not what your country can do.", "ask NOT what your country can do") == 0, "case and punctuation do not count");
        check(Math.abs(TextMetrics.wordErrorRate("one two three four", "one too three four") - 0.25) < 1e-9, "one substitution in four words");
        check(Math.abs(TextMetrics.wordErrorRate("one two three four", "one three four") - 0.25) < 1e-9, "one deletion");
        check(Math.abs(TextMetrics.wordErrorRate("one two", "one two three four") - 1.0) < 1e-9, "two insertions in two words");
        check(TextMetrics.wordErrorRate("don't stop", "don't stop") == 0 && TextMetrics.words("it's 'quoted' here").equals(Arrays.asList("it's", "quoted", "here")), "apostrophes inside words are kept");
        check(TextMetrics.wordErrorRate("", "") == 0 && TextMetrics.wordErrorRate("", "x") == 1 && TextMetrics.wordErrorRate("a", "") == 1, "empty texts");
        check(TextMetrics.wordErrorRate("Alles hat ein Ende", "alles hat ein ende") == 0 && TextMetrics.words("país qué").size() == 2, "non-English letters are words");

        System.out.println(checks + " Telegram Plus downloader and helper checks passed");
    }

    static byte[] wav(int rate, int channels, short[] samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + samples.length * 2).put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        b.putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(samples.length * 2);
        for (short s : samples) b.putShort(s);
        return b.array();
    }

    interface Thrower {
        void run() throws Exception;
    }

    static boolean fails(Thrower r, String code) {
        try {
            r.run();
            return false;
        } catch (IOException e) {
            return e.getMessage() != null && e.getMessage().startsWith(code);
        } catch (Exception e) {
            return false;
        }
    }
}
