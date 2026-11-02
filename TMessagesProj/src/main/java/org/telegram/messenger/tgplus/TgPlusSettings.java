package org.telegram.messenger.tgplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;

/** Telegram Plus settings. Secrets live in {@link SecureStore}, not here. */
public final class TgPlusSettings {

    private static final String PREFS = "tgplus_settings";

    public static final int TRANSCRIPTION_AUTOMATIC = 0;
    public static final int TRANSCRIPTION_ON_DEVICE = 1;
    public static final int TRANSCRIPTION_TELEGRAM = 2;

    public static final int TRANSLATION_TELEGRAM = 0;
    public static final int TRANSLATION_GEMINI = 1;

    public static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite";
    public static final String GEMINI_KEY_NAME = "gemini_api_key";

    private TgPlusSettings() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Speech (stored now; used once the on-device engine exists).

    public static int getTranscriptionRoute() {
        int value = prefs().getInt("transcription_route", TRANSCRIPTION_AUTOMATIC);
        return value >= TRANSCRIPTION_AUTOMATIC && value <= TRANSCRIPTION_TELEGRAM ? value : TRANSCRIPTION_AUTOMATIC;
    }

    public static void setTranscriptionRoute(int route) {
        prefs().edit().putInt("transcription_route", route).apply();
    }

    public static boolean isDictationEnabled() {
        return prefs().getBoolean("dictation_enabled", true);
    }

    public static void setDictationEnabled(boolean enabled) {
        prefs().edit().putBoolean("dictation_enabled", enabled).apply();
    }

    public static boolean isModelWifiOnly() {
        return prefs().getBoolean("model_wifi_only", true);
    }

    public static void setModelWifiOnly(boolean value) {
        prefs().edit().putBoolean("model_wifi_only", value).apply();
    }

    // Translation.

    public static int getTranslationProvider() {
        return prefs().getInt("translation_provider", TRANSLATION_TELEGRAM) == TRANSLATION_GEMINI ? TRANSLATION_GEMINI : TRANSLATION_TELEGRAM;
    }

    public static void setTranslationProvider(int provider) {
        prefs().edit().putInt("translation_provider", provider).apply();
    }

    public static String getGeminiModel() {
        String model = prefs().getString("gemini_model", DEFAULT_GEMINI_MODEL);
        return isValidModelId(model) ? model : DEFAULT_GEMINI_MODEL;
    }

    public static void setGeminiModel(String model) {
        if (TextUtils.isEmpty(model) || DEFAULT_GEMINI_MODEL.equals(model)) {
            prefs().edit().remove("gemini_model").apply();
        } else if (isValidModelId(model)) {
            prefs().edit().putString("gemini_model", model).apply();
        }
    }

    /** Model ids go into the request URL path, so only plain id characters are allowed. */
    public static boolean isValidModelId(String model) {
        return model != null && model.matches("[a-z0-9][a-z0-9.-]{0,63}");
    }

    public static boolean hasGeminiKey() {
        return SecureStore.has(GEMINI_KEY_NAME);
    }

    /** Gemini is used only when selected and a key is stored. */
    public static boolean isGeminiTranslationActive() {
        return getTranslationProvider() == TRANSLATION_GEMINI && hasGeminiKey();
    }

    // Diagnostics.

    /** Record chat open, translate and scroll timings (timings only, no content). */
    public static boolean isUiSpeedEnabled() {
        return prefs().getBoolean("ui_speed_enabled", true);
    }

    public static void setUiSpeedEnabled(boolean enabled) {
        prefs().edit().putBoolean("ui_speed_enabled", enabled).apply();
    }

    // App updates.

    public static boolean isAutoUpdateCheckEnabled() {
        return prefs().getBoolean("update_auto_check", true);
    }

    public static void setAutoUpdateCheckEnabled(boolean enabled) {
        prefs().edit().putBoolean("update_auto_check", enabled).apply();
    }

    /** Branch whose release is followed; empty means "the branch this build came from". */
    public static String getUpdateChannel() {
        String value = prefs().getString("update_channel", "");
        return value == null ? "" : value;
    }

    public static void setUpdateChannel(String channel) {
        prefs().edit().putString("update_channel", channel == null ? "" : channel).apply();
    }

    public static long getLastUpdateCheck() {
        return prefs().getLong("update_last_check", 0);
    }

    public static void setLastUpdateCheck(long time) {
        prefs().edit().putLong("update_last_check", time).apply();
    }

    public static int getSkippedUpdateBuild() {
        return prefs().getInt("update_skipped_build", 0);
    }

    public static void setSkippedUpdateBuild(int build) {
        prefs().edit().putInt("update_skipped_build", build).apply();
    }
}
