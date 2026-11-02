package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.tgplus.asr.ParakeetModel;
import org.telegram.messenger.tgplus.asr.ParakeetModelManager;

/** User-facing text for speech model and engine error codes. */
public final class TgPlusSpeechErrors {

    private TgPlusSpeechErrors() {}

    public static String text(String code) {
        if (code == null) return getString(R.string.TgPlusSpeechErrorGeneric);
        if (code.startsWith("HTTP_")) return formatString(R.string.TgPlusSpeechErrorHttp, code.substring(5));
        switch (code) {
            case ParakeetModelManager.ERROR_NO_SPACE:
                return formatString(R.string.TgPlusSpeechErrorNoSpace, AndroidUtilities.formatFileSize(ParakeetModel.requiredFreeBytes()));
            case ParakeetModelManager.ERROR_OFFLINE:
            case "TIMEOUT":
                return getString(R.string.TgPlusSpeechErrorOffline);
            case "DIGEST_MISMATCH":
            case "SIZE_MISMATCH":
            case "MISSING_FILE":
            case "BAD_TAR":
            case "UNPACK_FAILED":
                return getString(R.string.TgPlusSpeechErrorCorrupt);
            case "LOW_MEMORY":
                return getString(R.string.TgPlusSpeechErrorLowMemory);
            case "NATIVE_LOAD":
                return getString(R.string.TgPlusSpeechErrorNativeLoad);
            case "MODEL_MISSING":
                return getString(R.string.TgPlusSpeechErrorMissing);
            case "MIC_SILENT":
                return getString(R.string.TgPlusDictationQuiet);
            case "AUDIO_UNSUPPORTED":
            case "AUDIO_DECODE":
                return getString(R.string.TgPlusSpeechErrorAudioFormat);
            case "AUDIO_TOO_LONG":
                return getString(R.string.TgPlusSpeechErrorAudioTooLong);
            case "AUDIO_DOWNLOAD":
                return getString(R.string.TgPlusSpeechErrorAudioDownload);
            case "STORAGE":
                return getString(R.string.TgPlusSpeechErrorStorage);
            default:
                return formatString(R.string.TgPlusSpeechErrorCode, code);
        }
    }
}
