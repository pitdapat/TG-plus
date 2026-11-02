package org.telegram.messenger.tgplus.asr;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemClock;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.tgplus.DiagnosticsLog;
import org.telegram.messenger.tgplus.TgPlusDiagnostics;
import org.telegram.messenger.tgplus.TgPlusSettings;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads, unpacks, verifies and deletes the Parakeet model on the phone, and reports progress.
 *
 * The model lives in the app's private files folder (not the cache, which Android may clear) and is excluded
 * from backups. Downloads resume after an interruption. Only timings, sizes and error codes go to diagnostics.
 */
public final class ParakeetModelManager {

    public enum State {
        /** Not installed. A partial download may exist (see {@link #partialBytes()}). */
        MISSING,
        DOWNLOADING,
        UNPACKING,
        READY,
        /** The last attempt failed; {@link #error()} has the code. */
        ERROR
    }

    public interface Listener {
        /** Called on the UI thread when the state or progress changed. */
        void onModelChanged();
    }

    public static final String ERROR_WIFI_REQUIRED = "WIFI_REQUIRED";
    public static final String ERROR_NO_SPACE = "NO_SPACE";
    public static final String ERROR_OFFLINE = "NETWORK";
    public static final String ERROR_UNEXPECTED = "UNEXPECTED";
    public static final String ERROR_UNPACK = "UNPACK_FAILED";

    private static final Pattern CODE = Pattern.compile("^([A-Z][A-Z0-9_]{2,24})(:|$)");
    private static final long NOTIFY_INTERVAL_MS = 300;

    private static volatile ParakeetModelManager instance;

    public static ParakeetModelManager getInstance() {
        ParakeetModelManager local = instance;
        if (local == null) {
            synchronized (ParakeetModelManager.class) {
                local = instance;
                if (local == null) instance = local = new ParakeetModelManager();
            }
        }
        return local;
    }

    private final DispatchQueue queue = new DispatchQueue("tgplusModel");
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private volatile State state;
    private volatile int percent;
    private volatile String error;
    private volatile String errorDetail;
    private volatile boolean running;
    private long lastNotify;
    private int lastPercent = -1;

    private ParakeetModelManager() {
        state = isInstalled() ? State.READY : State.MISSING;
    }

    // Locations.

    private static File base() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "tgplus/asr");
    }

    public File dir() {
        return new File(base(), ParakeetModel.ID);
    }

    private File archiveFile() {
        return new File(base(), ParakeetModel.ARCHIVE_NAME);
    }

    private File partFile() {
        return new File(base(), ParakeetModel.ARCHIVE_NAME + ".part");
    }

    // State.

    public boolean isInstalled() {
        return ModelInstaller.isInstalled(dir(), ParakeetModel.FILES);
    }

    /** Current state; checks the disk again when nothing is running, so files deleted by the user are noticed. */
    public State state() {
        if (!running) {
            boolean installed = isInstalled();
            if (installed && state != State.READY) state = State.READY;
            else if (!installed && state == State.READY) state = State.MISSING;
        }
        return state;
    }

    /** Download or unpack progress, 0 to 100. */
    public int percent() {
        return percent;
    }

    public String error() {
        return error;
    }

    /** Developer-facing detail of the last error (library messages), truncated. */
    public String errorDetail() {
        return errorDetail;
    }

    /** Bytes already downloaded of an interrupted download; 0 if none. */
    public long partialBytes() {
        File part = partFile();
        return part.isFile() ? part.length() : 0;
    }

    public long installedBytes() {
        return isInstalled() ? ParakeetModel.installedBytes() : 0;
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // Actions.

    /**
     * Starts or resumes the download. Returns null if it started (or is already running or installed), or an
     * error code that explains why it did not: WIFI_REQUIRED (call again with allowMetered), NO_SPACE, NETWORK.
     */
    public String start(boolean allowMetered) {
        if (running || isInstalled()) return null;
        Context context = ApplicationLoader.applicationContext;
        if (!ApplicationLoader.isNetworkOnline()) return ERROR_OFFLINE;
        if (TgPlusSettings.isModelWifiOnly() && !allowMetered && isMetered(context)) return ERROR_WIFI_REQUIRED;
        long need = ParakeetModel.requiredFreeBytes() - partialBytes();
        long free = context.getFilesDir().getUsableSpace();
        if (free < need) {
            TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "download").fail(ERROR_NO_SPACE)
                .num("free_mb", free >> 20).num("need_mb", need >> 20).build());
            return ERROR_NO_SPACE;
        }
        running = true;
        cancelled.set(false);
        error = null;
        errorDetail = null;
        setState(State.DOWNLOADING, 0, true);
        final boolean metered = isMetered(context);
        queue.postRunnable(() -> run(metered));
        return null;
    }

    public void cancel() {
        cancelled.set(true);
    }

    /** Deletes the model and any partial download; frees the memory it uses if loaded. */
    public void delete() {
        cancelled.set(true);
        ParakeetEngine.getInstance().release();
        queue.postRunnable(() -> {
            ModelInstaller.deleteRecursively(dir());
            ModelInstaller.deleteRecursively(new File(base(), ParakeetModel.ID + ".tmp"));
            ModelInstaller.deleteRecursively(new File(base(), ParakeetModel.ID + ".old"));
            partFile().delete();
            archiveFile().delete();
            error = null;
            errorDetail = null;
            running = false;
            TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "delete").ok().build());
            setState(State.MISSING, 0, true);
        });
    }

    /** Re-reads every file and checks its SHA-256 (reads the whole model; takes several seconds). */
    public void verify(Utilities.Callback<String> done) {
        queue.postRunnable(() -> {
            long start = SystemClock.elapsedRealtime();
            String result;
            try {
                result = ModelInstaller.verifyAll(dir(), ParakeetModel.FILES);
            } catch (IOException e) {
                result = code(e);
            }
            final String code = result;
            TgPlusDiagnostics.log((code == null ? DiagnosticsLog.event("speech", "verify").ok()
                : DiagnosticsLog.event("speech", "verify").fail(codePart(code))).duration(SystemClock.elapsedRealtime() - start).build());
            AndroidUtilities.runOnUIThread(() -> done.run(code));
        });
    }

    // Work.

    private void run(boolean metered) {
        final long start = SystemClock.elapsedRealtime();
        String failure = null;
        try {
            ResumableDownloader downloader = new ResumableDownloader();
            File archive = archiveFile();
            downloader.download(ParakeetModel.ARCHIVE_URL, partFile(), archive, ParakeetModel.ARCHIVE_BYTES,
                ParakeetModel.ARCHIVE_SHA256, cancelled::get, (done, total) -> progress(State.DOWNLOADING, done, total));
            final long downloadMs = SystemClock.elapsedRealtime() - start;
            TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "download").ok().duration(downloadMs)
                .num("mb", ParakeetModel.ARCHIVE_BYTES >> 20)
                .num("resumed_mb", downloader.resumedFrom >> 20)
                .flag("metered", metered).build());

            setState(State.UNPACKING, 0, true);
            final long unpackStart = SystemClock.elapsedRealtime();
            try (InputStream in = new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(archive), 1 << 20))) {
                ModelInstaller.install(in, dir(), ParakeetModel.FILES, ParakeetModel.ID, cancelled::get,
                    (done, total) -> progress(State.UNPACKING, done, total));
            }
            archive.delete();
            TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "unpack").ok()
                .duration(SystemClock.elapsedRealtime() - unpackStart).num("mb", ParakeetModel.installedBytes() >> 20).build());
            running = false;
            setState(State.READY, 100, true);
            return;
        } catch (IOException e) {
            failure = code(e);
            errorDetail = detail(e);
            // A damaged archive or unpacked file is not worth keeping; an interrupted download is.
            if (failure.startsWith("DIGEST_MISMATCH") || failure.startsWith("SIZE_MISMATCH") || failure.startsWith("MISSING_FILE")
                || failure.startsWith(ERROR_UNPACK) || failure.startsWith("BAD_TAR") || failure.startsWith("DUPLICATE")) {
                archiveFile().delete();
            }
        } catch (Throwable t) {
            failure = ERROR_UNEXPECTED;
            errorDetail = detail(t);
        }
        final String code = codePart(failure);
        final boolean wasCancelled = "CANCELLED".equals(code);
        TgPlusDiagnostics.log(DiagnosticsLog.event("speech", "download").fail(code)
            .duration(SystemClock.elapsedRealtime() - start).num("partial_mb", partialBytes() >> 20).build());
        running = false;
        error = wasCancelled ? null : code;
        setState(wasCancelled ? State.MISSING : State.ERROR, 0, true);
    }

    private void progress(State phase, long done, long total) {
        int p = total <= 0 ? 0 : (int) Math.min(100, done * 100 / total);
        long now = SystemClock.elapsedRealtime();
        boolean changed = p != lastPercent;
        if (changed && (now - lastNotify >= NOTIFY_INTERVAL_MS || p == 100)) {
            lastNotify = now;
            lastPercent = p;
            setState(phase, p, true);
        }
    }

    private void setState(State newState, int newPercent, boolean notify) {
        state = newState;
        percent = newPercent;
        if (newState != State.DOWNLOADING && newState != State.UNPACKING) lastPercent = -1;
        if (notify) {
            AndroidUtilities.runOnUIThread(() -> {
                for (Listener l : listeners) l.onModelChanged();
            });
        }
    }

    private static boolean isMetered(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Error code for an exception: its own code if it has one, otherwise UNPACK_FAILED. */
    static String code(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return CODE.matcher(message).find() ? message : ERROR_UNPACK + ": " + message;
    }

    static String codePart(String failure) {
        if (failure == null) return ERROR_UNEXPECTED;
        Matcher m = CODE.matcher(failure);
        return m.find() ? m.group(1) : ERROR_UNEXPECTED;
    }

    private static String detail(Throwable t) {
        String text = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
