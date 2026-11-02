package org.telegram.messenger.tgplus.asr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads one file of known size and SHA-256 over HTTPS, resuming a partial download with a Range request.
 * The SHA-256 covers the whole file, so a resumed download is hashed from its first byte.
 *
 * Failures are IOExceptions whose message starts with a short code: CANCELLED, HTTP_<status>, NETWORK,
 * TIMEOUT, SIZE_MISMATCH, DIGEST_MISMATCH. A cancelled or interrupted download keeps its ".part" file for the
 * next attempt; a file that fails verification is deleted.
 */
public final class ResumableDownloader {

    public interface Cancel {
        boolean isCancelled();
    }

    public interface Progress {
        void onProgress(long bytesDone, long bytesTotal);
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_ATTEMPTS = 3;

    /** Bytes of the partial file that were already there when the last download started (0 if it started fresh). */
    public volatile long resumedFrom;

    /**
     * Downloads {@code url} to {@code part}, verifies it and renames it to {@code target}.
     * Does nothing if {@code target} already exists with the right size and hash.
     */
    public void download(String url, File part, File target, long expectedBytes, String expectedSha256,
                         Cancel cancel, Progress progress) throws IOException {
        resumedFrom = 0;
        if (target.isFile() && target.length() == expectedBytes && ModelInstaller.hashFile(target).equals(expectedSha256)) {
            if (progress != null) progress.onProgress(expectedBytes, expectedBytes);
            return;
        }
        File parent = part.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("STORAGE: cannot create " + parent);

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (cancel != null && cancel.isCancelled()) throw new IOException("CANCELLED");
            try {
                attempt(url, part, expectedBytes, cancel, progress, attempt == 1);
                break;
            } catch (RestartException e) {
                // Server answered something that cannot be resumed from this offset; start over.
                part.delete();
                last = new IOException("NETWORK: " + e.getMessage());
            } catch (IOException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                // Verification and cancellation are final; transient network errors are retried, keeping the data so far.
                if (message.startsWith("CANCELLED") || message.startsWith("HTTP_4") || message.startsWith("HTTP_INSECURE")
                    || message.startsWith("STORAGE") || message.startsWith("SIZE_MISMATCH") || message.startsWith("DIGEST_MISMATCH")) throw e;
                last = coded(e);
                if (attempt < MAX_ATTEMPTS) sleep(attempt * 1000L, cancel);
            }
            if (attempt == MAX_ATTEMPTS) throw last;
        }

        if (part.length() != expectedBytes) {
            part.delete();
            throw new IOException("SIZE_MISMATCH: got " + part.length() + " bytes, expected " + expectedBytes);
        }
        if (!ModelInstaller.hashFile(part).equals(expectedSha256)) {
            part.delete();
            throw new IOException("DIGEST_MISMATCH: archive");
        }
        target.delete();
        if (!part.renameTo(target)) throw new IOException("STORAGE: cannot move " + part + " to " + target);
    }

    private static final class RestartException extends IOException {
        RestartException(String message) {
            super(message);
        }
    }

    private void attempt(String url, File part, long expectedBytes, Cancel cancel, Progress progress, boolean first) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        if (have > expectedBytes) {
            part.delete();
            have = 0;
        }
        // Hash what we already have so the final digest covers the whole file.
        MessageDigest digest = ModelInstaller.sha256();
        if (have > 0) {
            try (InputStream in = new FileInputStream(part)) {
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (cancel != null && cancel.isCancelled()) throw new IOException("CANCELLED");
                    digest.update(buffer, 0, n);
                }
            }
        }
        if (first) resumedFrom = have;

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "TelegramPlus-ModelDownload");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");

            int status = connection.getResponseCode();
            if (!"https".equals(connection.getURL().getProtocol()) && !isLoopback(connection.getURL())) {
                throw new IOException("HTTP_INSECURE: redirected to " + connection.getURL().getProtocol());
            }
            if (status == 416) throw new RestartException("range not satisfiable at " + have);
            boolean append;
            if (status == 206) {
                Matcher m = CONTENT_RANGE.matcher(String.valueOf(connection.getHeaderField("Content-Range")));
                if (!m.matches() || Long.parseLong(m.group(1)) != have) throw new RestartException("unexpected Content-Range");
                if (!m.group(3).equals("*") && Long.parseLong(m.group(3)) != expectedBytes) throw new IOException("SIZE_MISMATCH: server file is " + m.group(3) + " bytes, expected " + expectedBytes);
                append = true;
            } else if (status == 200) {
                // The server ignored the Range header, or this is a fresh download: take the whole body.
                long length = connection.getContentLengthLong();
                if (length >= 0 && length != expectedBytes) throw new IOException("SIZE_MISMATCH: server file is " + length + " bytes, expected " + expectedBytes);
                if (have > 0) {
                    have = 0;
                    digest = ModelInstaller.sha256();
                    if (first) resumedFrom = 0;
                }
                append = false;
            } else {
                throw new IOException("HTTP_" + status);
            }

            long done = have;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (cancel != null && cancel.isCancelled()) throw new IOException("CANCELLED");
                    if (done + n > expectedBytes) throw new IOException("SIZE_MISMATCH: more data than expected " + expectedBytes);
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, expectedBytes);
                }
            }
            if (done != expectedBytes) throw new IOException("NETWORK: connection ended after " + done + " of " + expectedBytes + " bytes");
        } finally {
            connection.disconnect();
        }
    }

    /** Keeps errors that already start with a code ("HTTP_503: ..."); labels the rest as NETWORK or TIMEOUT. */
    static IOException coded(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.matches("^[A-Z][A-Z0-9_]{2,24}(:.*)?$")) return e;
        String kind = e instanceof SocketTimeoutException ? "TIMEOUT" : "NETWORK";
        return new IOException(kind + ": " + e.getClass().getSimpleName() + (message.isEmpty() ? "" : " " + message));
    }

    private static boolean isLoopback(URL url) {
        String host = url.getHost();
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    private static void sleep(long ms, Cancel cancel) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancel != null && cancel.isCancelled()) throw new IOException("CANCELLED");
            try {
                Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("CANCELLED");
            }
        }
    }
}
