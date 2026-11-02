package org.telegram.utils.recorder.round;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

final class RoundVideoDiagnostics {
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong(1);

    /** Recent lines kept in memory so the in-app diagnostic report can include them. */
    private static final int RECENT_LINES = 400;
    private static final int MAX_LINE_LENGTH = 1200;
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>(RECENT_LINES);

    private final long sessionId = NEXT_SESSION_ID.getAndIncrement();
    private final long startedAtMs = SystemClock.elapsedRealtime();

    long getSessionId() {
        return sessionId;
    }

    boolean isEnabled() {
        return BuildVars.LOGS_ENABLED;
    }

    void log(@NonNull String message) {
        String line = prefix() + message;
        remember(line);
        FileLog.d(line);
    }

    void error(@NonNull String message, @NonNull Throwable error) {
        String line = prefix() + message + ": " + error;
        remember("E " + line);
        FileLog.e(line);
        FileLog.e(error);
    }

    /** Returns the most recent recorder log lines, oldest first. */
    @NonNull
    static String recentLog() {
        StringBuilder builder = new StringBuilder();
        synchronized (RECENT) {
            for (String line : RECENT) builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static void remember(@NonNull String line) {
        if (line.length() > MAX_LINE_LENGTH) line = line.substring(0, MAX_LINE_LENGTH) + "…";
        String stamped = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + line;
        synchronized (RECENT) {
            if (RECENT.size() >= RECENT_LINES) RECENT.pollFirst();
            RECENT.addLast(stamped);
        }
    }

    @NonNull
    private String prefix() {
        return "RoundVideo[" + sessionId + "] t+"
                + (SystemClock.elapsedRealtime() - startedAtMs)
                + "ms [" + Thread.currentThread().getName() + "] ";
    }
}
