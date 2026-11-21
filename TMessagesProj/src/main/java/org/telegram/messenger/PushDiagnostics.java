package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Local, bounded status only. Never stores tokens, payloads, chat IDs or message text. */
public final class PushDiagnostics {
    private static final String PREFS = "tgplus_push_diagnostics";
    private static long receiptSequence;
    /** Number of entries kept in the local event history. */
    public static final int MAX_EVENTS = 200;
    /** Longest event detail kept, in characters. */
    public static final int MAX_DETAIL = 120;
    /** How long the self-test waits for a push after re-registering. */
    public static final long SELF_TEST_WINDOW_MS = 5 * 60 * 1000L;
    /** Longest time a push callback waits for its notification work (FCM allows about 20s). */
    public static final long CALLBACK_TIMEOUT_MS = 15 * 1000L;

    private PushDiagnostics() {}

    private static SharedPreferences prefs() {
        Context context = ApplicationLoader.applicationContext;
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String fingerprint(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String accountKey(int account, long userId) {
        return "account_" + account + "_";
    }

    public static String safeServerError(String error) {
        return error != null && error.matches("[A-Z][A-Z0-9_]{0,79}") ? error : "OTHER_ERROR";
    }

    public static String safeFirebaseError(Throwable error) {
        for (int i = 0; error != null && i < 5; i++, error = error.getCause()) {
            String message = error.getMessage();
            if (message == null) continue;
            for (String code : new String[]{"API_KEY_ANDROID_APP_BLOCKED", "API_KEY_SERVICE_BLOCKED",
                    "SERVICE_NOT_AVAILABLE", "TOO_MANY_REGISTRATIONS", "FIS_AUTH_ERROR", "BAD_CONFIG"}) {
                if (message.contains(code)) return code;
            }
        }
        return "TOKEN_REQUEST_FAILED";
    }

    /** Keeps only characters that cannot carry tokens, names or message text. */
    public static String safeDetail(String detail) {
        if (detail == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < detail.length() && out.length() < MAX_DETAIL; i++) {
            char c = detail.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == ':' || c == '.' || c == '-' || c == ' ' || c == '=') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Appends one entry to the bounded event history. Codes and details must not be private. */
    public static synchronized void event(String code, String detail) {
        SharedPreferences p = prefs();
        if (p == null) return;
        appendEvent(p.edit(), p, code, detail).apply();
    }

    private static SharedPreferences.Editor appendEvent(SharedPreferences.Editor editor, SharedPreferences p,
                                                        String code, String detail) {
        String entry = System.currentTimeMillis() + "|" + safeDetail(code) + "|" + safeDetail(detail);
        String existing = p.getString("events", "");
        String[] lines = existing.isEmpty() ? new String[0] : existing.split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - (MAX_EVENTS - 1)); i < lines.length; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append(entry);
        return editor.putString("events", out.toString());
    }

    /** Event history, oldest first, as "epochMillis|CODE|detail" lines. */
    public static synchronized String[] events() {
        SharedPreferences p = prefs();
        String value = p == null ? "" : p.getString("events", "");
        return value.isEmpty() ? new String[0] : value.split("\n");
    }

    /** What happened to the latest push after it was decrypted (or why it was dropped). */
    public static synchronized void pushOutcome(String outcome) {
        SharedPreferences p = prefs();
        if (p == null) return;
        String safe = safeDetail(outcome);
        SharedPreferences.Editor editor = p.edit().putString("last_outcome", safe)
                .putLong("last_outcome_at", System.currentTimeMillis());
        if (p.getLong("selftest_push_at", 0) > 0 && p.getString("selftest_outcome", "").isEmpty()) {
            editor.putString("selftest_outcome", safe);
        }
        appendEvent(editor, p, "PUSH_OUTCOME", safe).apply();
    }

    public static synchronized void callbackTimedOut() {
        SharedPreferences p = prefs();
        if (p == null) return;
        appendEvent(p.edit().putLong("timeout_count", p.getLong("timeout_count", 0) + 1),
                p, "CALLBACK_TIMEOUT", (CALLBACK_TIMEOUT_MS / 1000) + "s").apply();
    }

    /** Starts the self-test window; the caller then re-requests the token and re-registers. */
    public static synchronized void selfTestStarted() {
        SharedPreferences p = prefs();
        if (p == null) return;
        appendEvent(p.edit().putLong("selftest_started_at", System.currentTimeMillis())
                .remove("selftest_push_at").remove("selftest_outcome"), p, "SELFTEST_START", "").apply();
    }

    public static synchronized void tokenRequestStarted() {
        SharedPreferences p = prefs();
        if (p != null) appendEvent(p.edit().putString("token_state", "requesting")
                .putLong("token_request_at", System.currentTimeMillis()).remove("token_error"),
                p, "TOKEN_REQUEST", "").apply();
    }

    public static synchronized void tokenReady(String token) {
        if (token == null || token.isEmpty()) {
            tokenFailed(null);
            return;
        }
        SharedPreferences p = prefs();
        if (p == null) return;
        String fp = fingerprint(token);
        boolean changed = !fp.equals(p.getString("token_fingerprint", ""));
        appendEvent(p.edit().putString("token_state", "ready").putString("token_fingerprint", fp)
                .putLong("token_result_at", System.currentTimeMillis()).remove("token_error"),
                p, "TOKEN_READY", changed ? "new " + fp.substring(0, 8) : "same " + fp.substring(0, 8)).apply();
    }

    public static synchronized void tokenFailed(Throwable error) {
        SharedPreferences p = prefs();
        if (p == null) return;
        String code = safeFirebaseError(error);
        appendEvent(p.edit().putString("token_state", "failed").putString("token_error", code)
                .putLong("token_result_at", System.currentTimeMillis()), p, "TOKEN_FAILED", code).apply();
    }

    public static synchronized void noPlayServices() {
        SharedPreferences p = prefs();
        if (p != null) appendEvent(p.edit().putString("token_state", "no_play_services")
                .putLong("token_result_at", System.currentTimeMillis()), p, "NO_PLAY_SERVICES", "").apply();
    }

    public static synchronized void registrationStarted(int account, long userId, String token) {
        SharedPreferences p = prefs();
        if (p == null) return;
        String key = accountKey(account, userId);
        appendEvent(p.edit().putString(key + "owner", fingerprint(Long.toString(userId)))
                .putString(key + "state", "requesting").putString(key + "token", fingerprint(token))
                .putLong(key + "at", System.currentTimeMillis()).remove(key + "error"),
                p, "REGISTER_START", "account " + account).apply();
    }

    public static synchronized void registrationResult(int account, long userId, String token,
                                                       boolean accepted, int code, String error) {
        SharedPreferences p = prefs();
        if (p == null) return;
        String key = accountKey(account, userId);
        // Ignore stale responses after a token/account change or clearing diagnostics.
        if (!p.getString(key + "owner", "").equals(fingerprint(Long.toString(userId)))
                || !p.getString(key + "token", "").equals(fingerprint(token))) return;
        // Preserve which token/account the server actually answered for.
        String failure = code + ": " + safeServerError(error);
        appendEvent(p.edit().putString(key + "state", accepted ? "accepted" : "failed")
                .putString(key + "token", fingerprint(token)).putLong(key + "at", System.currentTimeMillis())
                .putString(key + "error", accepted ? "" : failure),
                p, accepted ? "REGISTER_OK" : "REGISTER_FAILED",
                "account " + account + (accepted ? "" : " " + failure)).apply();
    }

    public static long received(long sentAt, int priority, int originalPriority) {
        return received(sentAt, priority, originalPriority, "");
    }

    /** {@code context} describes app/screen/connection state and is sanitized like any detail. */
    public static synchronized long received(long sentAt, int priority, int originalPriority, String context) {
        long receipt = ++receiptSequence;
        SharedPreferences p = prefs();
        if (p == null) return receipt;
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = p.edit().putLong("receipt_id", receipt)
                .putLong("received_at", now).putLong("sent_at", sentAt)
                .putInt("priority", priority).putInt("original_priority", originalPriority)
                .putLong("received_count", p.getLong("received_count", 0) + 1)
                .remove("callback_finished_at");
        long testStart = p.getLong("selftest_started_at", 0);
        if (testStart > 0 && p.getLong("selftest_push_at", 0) == 0 && now - testStart <= SELF_TEST_WINDOW_MS) {
            editor.putLong("selftest_push_at", now);
        }
        String delay = sentAt > 0 && now >= sentAt ? " delay=" + ((now - sentAt) / 1000) + "s" : "";
        String extra = safeDetail(context);
        appendEvent(editor, p, "PUSH_RECEIVED", "priority=" + priority + " original=" + originalPriority + delay
                + (extra.isEmpty() ? "" : " " + extra)).apply();
        return receipt;
    }

    public static synchronized void callbackFinished(long receipt) {
        SharedPreferences p = prefs();
        if (p != null && p.getLong("receipt_id", -1) == receipt) {
            long now = System.currentTimeMillis();
            long took = now - p.getLong("received_at", now);
            appendEvent(p.edit().putLong("callback_finished_at", now), p, "CALLBACK_DONE", took + "ms").apply();
        }
    }

    public static synchronized Snapshot snapshot(int account, long userId) {
        return new Snapshot(prefs(), accountKey(account, userId), fingerprint(Long.toString(userId)));
    }

    public static synchronized void clear() {
        SharedPreferences p = prefs();
        if (p != null) p.edit().clear().apply();
    }

    public static final class Snapshot {
        public final String tokenState, tokenError, registrationState, registrationError;
        public final long tokenRequestAt, tokenResultAt, registrationAt, receivedAt, sentAt, finishedAt, receivedCount;
        public final int priority, originalPriority;
        public final String lastOutcome, selfTestState, selfTestOutcome;
        public final long lastOutcomeAt, timeoutCount, selfTestStartedAt, selfTestPushAt;

        private Snapshot(SharedPreferences p, String key, String owner) {
            boolean currentOwner = p != null && owner.equals(p.getString(key + "owner", ""));
            tokenState = p == null ? "not_checked" : p.getString("token_state", "not_checked");
            tokenError = p == null ? "" : p.getString("token_error", "");
            String state = !currentOwner ? "not_checked" : p.getString(key + "state", "not_checked");
            if (p != null && !state.equals("not_checked") && !p.getString(key + "token", "").equals(p.getString("token_fingerprint", ""))) {
                state = "stale";
            }
            registrationState = state;
            registrationError = !currentOwner ? "" : p.getString(key + "error", "");
            tokenRequestAt = p == null ? 0 : p.getLong("token_request_at", 0);
            tokenResultAt = p == null ? 0 : p.getLong("token_result_at", 0);
            registrationAt = !currentOwner ? 0 : p.getLong(key + "at", 0);
            receivedAt = p == null ? 0 : p.getLong("received_at", 0);
            sentAt = p == null ? 0 : p.getLong("sent_at", 0);
            finishedAt = p == null ? 0 : p.getLong("callback_finished_at", 0);
            receivedCount = p == null ? 0 : p.getLong("received_count", 0);
            priority = p == null ? 0 : p.getInt("priority", 0);
            originalPriority = p == null ? 0 : p.getInt("original_priority", 0);
            lastOutcome = p == null ? "" : p.getString("last_outcome", "");
            lastOutcomeAt = p == null ? 0 : p.getLong("last_outcome_at", 0);
            timeoutCount = p == null ? 0 : p.getLong("timeout_count", 0);
            selfTestStartedAt = p == null ? 0 : p.getLong("selftest_started_at", 0);
            selfTestPushAt = p == null ? 0 : p.getLong("selftest_push_at", 0);
            selfTestOutcome = p == null ? "" : p.getString("selftest_outcome", "");
            if (selfTestStartedAt == 0) {
                selfTestState = "not_run";
            } else if (selfTestPushAt > 0) {
                selfTestState = "push_received";
            } else if (System.currentTimeMillis() - selfTestStartedAt > SELF_TEST_WINDOW_MS) {
                selfTestState = "no_push";
            } else {
                selfTestState = "waiting";
            }
        }
    }
}
