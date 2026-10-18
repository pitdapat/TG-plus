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

    public static synchronized void tokenRequestStarted() {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString("token_state", "requesting")
                .putLong("token_request_at", System.currentTimeMillis()).remove("token_error").apply();
    }

    public static synchronized void tokenReady(String token) {
        if (token == null || token.isEmpty()) {
            tokenFailed(null);
            return;
        }
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString("token_state", "ready")
                .putString("token_fingerprint", fingerprint(token))
                .putLong("token_result_at", System.currentTimeMillis()).remove("token_error").apply();
    }

    public static synchronized void tokenFailed(Throwable error) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString("token_state", "failed")
                .putString("token_error", safeFirebaseError(error))
                .putLong("token_result_at", System.currentTimeMillis()).apply();
    }

    public static synchronized void noPlayServices() {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString("token_state", "no_play_services")
                .putLong("token_result_at", System.currentTimeMillis()).apply();
    }

    public static synchronized void registrationStarted(int account, long userId, String token) {
        SharedPreferences p = prefs();
        if (p == null) return;
        String key = accountKey(account, userId);
        p.edit().putString(key + "owner", fingerprint(Long.toString(userId)))
                .putString(key + "state", "requesting").putString(key + "token", fingerprint(token))
                .putLong(key + "at", System.currentTimeMillis()).remove(key + "error").apply();
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
        p.edit().putString(key + "state", accepted ? "accepted" : "failed")
                .putString(key + "token", fingerprint(token)).putLong(key + "at", System.currentTimeMillis())
                .putString(key + "error", accepted ? "" : code + ": " + safeServerError(error)).apply();
    }

    public static synchronized long received(long sentAt, int priority, int originalPriority) {
        long receipt = ++receiptSequence;
        SharedPreferences p = prefs();
        if (p != null) p.edit().putLong("receipt_id", receipt)
                .putLong("received_at", System.currentTimeMillis()).putLong("sent_at", sentAt)
                .putInt("priority", priority).putInt("original_priority", originalPriority)
                .putLong("received_count", p.getLong("received_count", 0) + 1)
                .remove("callback_finished_at").apply();
        return receipt;
    }

    public static synchronized void callbackFinished(long receipt) {
        SharedPreferences p = prefs();
        if (p != null && p.getLong("receipt_id", -1) == receipt) {
            p.edit().putLong("callback_finished_at", System.currentTimeMillis()).apply();
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
        }
    }
}
