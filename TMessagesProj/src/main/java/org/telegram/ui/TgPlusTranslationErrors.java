package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.R;
import org.telegram.messenger.tgplus.GeminiTranslator;

/** User-facing text for Gemini translation errors. */
public final class TgPlusTranslationErrors {

    private TgPlusTranslationErrors() {}

    public static String text(String error) {
        if (error == null) return getString(R.string.TranslationFailedAlert2);
        switch (error) {
            case GeminiTranslator.ERROR_NO_KEY: return getString(R.string.TgPlusGeminiErrorNoKey);
            case GeminiTranslator.ERROR_INVALID_KEY: return getString(R.string.TgPlusGeminiErrorInvalidKey);
            case GeminiTranslator.ERROR_QUOTA: return getString(R.string.TgPlusGeminiErrorQuota);
            case GeminiTranslator.ERROR_NETWORK: return getString(R.string.TgPlusErrorNetwork);
            case GeminiTranslator.ERROR_TIMEOUT: return getString(R.string.TgPlusErrorTimeout);
            case GeminiTranslator.ERROR_TOO_LONG: return getString(R.string.TgPlusGeminiErrorTooLong);
            case GeminiTranslator.ERROR_BLOCKED: return getString(R.string.TgPlusGeminiErrorBlocked);
            case GeminiTranslator.ERROR_MODEL: return getString(R.string.TgPlusGeminiErrorModel);
            case GeminiTranslator.ERROR_SERVER: return getString(R.string.TgPlusGeminiErrorServer);
            default: return formatString(R.string.TgPlusGeminiErrorOther, error);
        }
    }
}
