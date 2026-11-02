package org.telegram.messenger.tgplus.asr;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Unpacks a model from a (decompressed) tar stream into a folder, verifying every file.
 *
 * Only the files in the allow-list are written, always under a name taken from the allow-list and never from
 * the archive, so a malicious archive cannot write anywhere else. The folder appears only when all files are
 * present and correct: files go to "<folder>.tmp" first, then the folder is swapped in.
 *
 * Failures are IOExceptions whose message starts with a short code ("DIGEST_MISMATCH: ...").
 */
public final class ModelInstaller {

    public interface Cancel {
        boolean isCancelled();
    }

    public interface Progress {
        void onProgress(long bytesWritten, long bytesTotal);
    }

    private ModelInstaller() {}

    public static void install(InputStream tar, File folder, List<ParakeetModel.Entry> files, String marker,
                               Cancel cancel, Progress progress) throws IOException {
        File parent = folder.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("STORAGE: cannot create " + parent);
        File temp = new File(parent, folder.getName() + ".tmp");
        File old = new File(parent, folder.getName() + ".old");
        deleteRecursively(temp);
        deleteRecursively(old);
        if (!temp.mkdirs()) throw new IOException("STORAGE: cannot create " + temp);

        boolean installed = false;
        try {
            Map<String, ParakeetModel.Entry> wanted = new HashMap<>();
            long total = 0;
            for (ParakeetModel.Entry e : files) {
                wanted.put(e.archivePath, e);
                total += e.size;
            }
            Set<String> done = new HashSet<>();
            long written = 0;
            byte[] buffer = new byte[256 * 1024];

            try (TarReader reader = new TarReader(tar)) {
                TarReader.Entry entry;
                while ((entry = reader.next()) != null && done.size() < wanted.size()) {
                    ParakeetModel.Entry target = entry.regularFile ? wanted.get(entry.name) : null;
                    if (target == null) continue;
                    if (!done.add(target.archivePath)) throw new IOException("DUPLICATE: " + target.fileName);
                    if (entry.size != target.size) throw new IOException("SIZE_MISMATCH: " + target.fileName + " is " + entry.size + " bytes, expected " + target.size);

                    MessageDigest digest = sha256();
                    long size = 0;
                    try (OutputStream out = new FileOutputStream(new File(temp, target.fileName))) {
                        int n;
                        while ((n = reader.read(buffer, 0, buffer.length)) > 0) {
                            if (cancel != null && cancel.isCancelled()) throw new IOException("CANCELLED");
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                            size += n;
                            written += n;
                            if (progress != null) progress.onProgress(written, total);
                        }
                    }
                    if (size != target.size) throw new IOException("SIZE_MISMATCH: " + target.fileName + " has " + size + " bytes, expected " + target.size);
                    String actual = hex(digest.digest());
                    if (!actual.equals(target.sha256)) throw new IOException("DIGEST_MISMATCH: " + target.fileName);
                }
            }
            for (ParakeetModel.Entry e : files) {
                if (!done.contains(e.archivePath)) throw new IOException("MISSING_FILE: " + e.archivePath);
            }

            StringBuilder text = new StringBuilder(marker == null ? "" : marker).append('\n');
            for (ParakeetModel.Entry e : files) text.append(e.fileName).append(' ').append(e.size).append(' ').append(e.sha256).append('\n');
            try (OutputStream out = new FileOutputStream(new File(temp, ParakeetModel.MARKER_NAME))) {
                out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            }

            // Swap the finished folder in. If the second rename fails, put the old one back.
            boolean hadOld = folder.exists();
            if (hadOld && !folder.renameTo(old)) throw new IOException("STORAGE: cannot replace " + folder);
            if (!temp.renameTo(folder)) {
                if (hadOld) old.renameTo(folder);
                throw new IOException("STORAGE: cannot move " + temp + " to " + folder);
            }
            installed = true;
            deleteRecursively(old);
        } finally {
            if (!installed) deleteRecursively(temp);
        }
    }

    /** True if the folder was fully verified when installed and every file still has its expected size. */
    public static boolean isInstalled(File folder, List<ParakeetModel.Entry> files) {
        if (!new File(folder, ParakeetModel.MARKER_NAME).isFile()) return false;
        for (ParakeetModel.Entry e : files) {
            File f = new File(folder, e.fileName);
            if (!f.isFile() || f.length() != e.size) return false;
        }
        return true;
    }

    /** Re-reads every file and checks its SHA-256. Slow (reads the whole model); for diagnostics. */
    public static String verifyAll(File folder, List<ParakeetModel.Entry> files) throws IOException {
        for (ParakeetModel.Entry e : files) {
            File f = new File(folder, e.fileName);
            if (!f.isFile()) return "MISSING_FILE: " + e.fileName;
            if (f.length() != e.size) return "SIZE_MISMATCH: " + e.fileName;
            if (!hashFile(f).equals(e.sha256)) return "DIGEST_MISMATCH: " + e.fileName;
        }
        return null;
    }

    public static String hashFile(File file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[256 * 1024];
        try (InputStream in = new java.io.FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) for (File c : children) deleteRecursively(c);
        file.delete();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
