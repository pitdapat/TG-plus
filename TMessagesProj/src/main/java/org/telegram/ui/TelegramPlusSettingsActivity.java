package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.tgplus.GeminiTranslator;
import org.telegram.messenger.tgplus.SecureStore;
import org.telegram.messenger.tgplus.TgPlusDiagnostics;
import org.telegram.messenger.tgplus.TgPlusSettings;
import org.telegram.messenger.tgplus.UpdateChecker;
import org.telegram.messenger.tgplus.asr.ParakeetEngine;
import org.telegram.messenger.tgplus.asr.ParakeetModel;
import org.telegram.messenger.tgplus.asr.ParakeetModelManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/** Settings > Telegram Plus. All Telegram Plus settings live here. */
public class TelegramPlusSettingsActivity extends UniversalFragment {

    private static final int BUTTON_TRANSCRIPTION_ROUTE = 1;
    private static final int BUTTON_DICTATION = 2;
    private static final int BUTTON_SPEECH_MODEL = 3;
    private static final int BUTTON_MODEL_WIFI_ONLY = 4;
    private static final int BUTTON_TRANSLATION_PROVIDER = 10;
    private static final int BUTTON_GEMINI_KEY = 11;
    private static final int BUTTON_GEMINI_MODEL = 12;
    private static final int BUTTON_TRANSLATE_TO = 13;
    private static final int BUTTON_TEST_TRANSLATION = 14;
    private static final int BUTTON_TELEGRAM_TRANSLATE_BUTTON = 15;
    private static final int BUTTON_DO_NOT_TRANSLATE = 16;
    private static final int BUTTON_CHECK_UPDATES = 20;
    private static final int BUTTON_UPDATE_CHANNEL = 21;
    private static final int BUTTON_AUTO_UPDATE = 22;
    private static final int BUTTON_FEATURE_DIAGNOSTICS = 30;
    private static final int BUTTON_PUSH_DIAGNOSTICS = 31;
    private static final int BUTTON_UI_SPEED = 32;

    private static final String GEMINI_KEY_URL = "https://aistudio.google.com/apikey";

    private List<String> knownChannels = new ArrayList<>();
    private boolean testingTranslation;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.TgPlusTitle);
    }

    private final ParakeetModelManager.Listener modelListener = () -> {
        if (fragmentView != null && listView != null) listView.adapter.update(false);
    };

    @Override
    public void onResume() {
        super.onResume();
        ParakeetModelManager.getInstance().addListener(modelListener);
        if (listView != null) listView.adapter.update(false);
    }

    @Override
    public void onPause() {
        ParakeetModelManager.getInstance().removeListener(modelListener);
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        ParakeetModelManager.getInstance().removeListener(modelListener);
        super.onFragmentDestroy();
    }

    private String routeName(int route) {
        switch (route) {
            case TgPlusSettings.TRANSCRIPTION_ON_DEVICE: return getString(R.string.TgPlusRouteOnDevice);
            case TgPlusSettings.TRANSCRIPTION_TELEGRAM: return getString(R.string.TgPlusRouteTelegram);
            default: return getString(R.string.TgPlusRouteAutomatic);
        }
    }

    private String providerName(int provider) {
        return getString(provider == TgPlusSettings.TRANSLATION_GEMINI ? R.string.TgPlusProviderGemini : R.string.TgPlusProviderTelegram);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // Speech
        items.add(UItem.asHeader(getString(R.string.TgPlusSpeech)));
        items.add(UItem.asButton(BUTTON_TRANSCRIPTION_ROUTE, R.drawable.msg_voicechat, getString(R.string.TgPlusVoiceTranscription), routeName(TgPlusSettings.getTranscriptionRoute())));
        items.add(UItem.asCheck(BUTTON_DICTATION, getString(R.string.TgPlusDictationButton)).setChecked(TgPlusSettings.isDictationEnabled()));
        items.add(UItem.asButton(BUTTON_SPEECH_MODEL, R.drawable.msg_download, getString(R.string.TgPlusSpeechModel), modelRowValue()));
        items.add(UItem.asCheck(BUTTON_MODEL_WIFI_ONLY, getString(R.string.TgPlusModelWifiOnly)).setChecked(TgPlusSettings.isModelWifiOnly()));
        items.add(UItem.asShadow(formatString(R.string.TgPlusSpeechInfo, ParakeetEngine.getInstance().displayName())));

        // Translation
        items.add(UItem.asHeader(getString(R.string.TgPlusTranslation)));
        int provider = TgPlusSettings.getTranslationProvider();
        items.add(UItem.asButton(BUTTON_TRANSLATION_PROVIDER, R.drawable.msg_translate, getString(R.string.TgPlusTranslationProvider), providerName(provider)));
        items.add(UItem.asButton(BUTTON_GEMINI_KEY, R.drawable.msg_permissions, getString(R.string.TgPlusGeminiKey),
            getString(TgPlusSettings.hasGeminiKey() ? R.string.TgPlusKeySet : R.string.TgPlusKeyNotSet)));
        items.add(UItem.asButton(BUTTON_GEMINI_MODEL, R.drawable.msg_bot, getString(R.string.TgPlusGeminiModel), TgPlusSettings.getGeminiModel()));
        items.add(UItem.asButton(BUTTON_TRANSLATE_TO, R.drawable.msg_language, getString(R.string.TgPlusTranslateTo),
            TranslateAlert2.capitalFirst(TranslateAlert2.languageName(TranslateAlert2.getToLanguage()))));
        items.add(UItem.asButton(BUTTON_TEST_TRANSLATION, R.drawable.msg_retry, getString(R.string.TgPlusTestTranslation),
            testingTranslation ? getString(R.string.TgPlusRunning) : testValue(TgPlusDiagnostics.TEST_TRANSLATION)));
        if (provider == TgPlusSettings.TRANSLATION_GEMINI && !TgPlusSettings.hasGeminiKey()) {
            items.add(UItem.asShadow(getString(R.string.TgPlusGeminiNeedsKey)));
        }
        items.add(UItem.asShadow(getString(R.string.TgPlusTranslationInfo)));

        // Telegram's own Language settings decide whether the Translate button appears at all.
        items.add(UItem.asHeader(getString(R.string.TgPlusTelegramLanguageSettings)));
        items.add(UItem.asButton(BUTTON_TELEGRAM_TRANSLATE_BUTTON, R.drawable.msg2_language, getString(R.string.TgPlusShowTranslateButton), translateButtonValue()));
        items.add(UItem.asButton(BUTTON_DO_NOT_TRANSLATE, R.drawable.msg_cancel, getString(R.string.TgPlusDoNotTranslate), doNotTranslateValue()));
        items.add(UItem.asShadow(languageSettingsInfo()));

        // App updates
        items.add(UItem.asHeader(getString(R.string.TgPlusUpdates)));
        items.add(UItem.asButton(BUTTON_CHECK_UPDATES, R.drawable.msg_download, getString(R.string.TgPlusCheckForUpdates), TgPlusUpdateFlow.describeInstalled()));
        items.add(UItem.asButton(BUTTON_UPDATE_CHANNEL, R.drawable.msg_link, getString(R.string.TgPlusUpdateChannel), UpdateChecker.effectiveChannel()));
        items.add(UItem.asCheck(BUTTON_AUTO_UPDATE, getString(R.string.TgPlusAutoUpdateCheck)).setChecked(TgPlusSettings.isAutoUpdateCheckEnabled()));
        items.add(UItem.asShadow(formatString(R.string.TgPlusUpdatesInfo, UpdateChecker.repository(), TgPlusDiagnostics.formatTime(TgPlusSettings.getLastUpdateCheck()))));

        // Diagnostics
        items.add(UItem.asHeader(getString(R.string.TgPlusDiagnostics)));
        items.add(UItem.asButton(BUTTON_FEATURE_DIAGNOSTICS, R.drawable.msg_info, getString(R.string.TgPlusFeatureDiagnostics)));
        items.add(UItem.asButton(BUTTON_PUSH_DIAGNOSTICS, R.drawable.msg_report, getString(R.string.PushDiagTitle)));
        items.add(UItem.asCheck(BUTTON_UI_SPEED, getString(R.string.TgPlusMeasureUiSpeed)).setChecked(TgPlusSettings.isUiSpeedEnabled()));
        items.add(UItem.asShadow(getString(R.string.TgPlusDiagnosticsInfo) + "\n\n" + getString(R.string.TgPlusUiSpeedInfo)));
    }

    private String modelRowValue() {
        ParakeetModelManager m = ParakeetModelManager.getInstance();
        switch (m.state()) {
            case DOWNLOADING:
                return formatString(R.string.TgPlusModelDownloading, m.percent());
            case UNPACKING:
                return formatString(R.string.TgPlusModelUnpacking, m.percent());
            case READY:
                return formatString(R.string.TgPlusModelReady, AndroidUtilities.formatFileSize(m.installedBytes()));
            case ERROR:
                return getString(R.string.TgPlusModelFailed);
            default:
                return m.partialBytes() > 0
                    ? formatString(R.string.TgPlusModelPartial, AndroidUtilities.formatFileSize(m.partialBytes()))
                    : formatString(R.string.TgPlusModelNotDownloaded, AndroidUtilities.formatFileSize(ParakeetModel.ARCHIVE_BYTES));
        }
    }

    /** One dialog whose content and buttons follow the model's state. */
    private void showSpeechModelDialog() {
        if (getParentActivity() == null) return;
        ParakeetModelManager m = ParakeetModelManager.getInstance();
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(getString(R.string.TgPlusSpeechModel));
        switch (m.state()) {
            case DOWNLOADING:
            case UNPACKING:
                b.setMessage(formatString(R.string.TgPlusModelProgressMessage,
                    getString(m.state() == ParakeetModelManager.State.DOWNLOADING ? R.string.TgPlusModelPhaseDownloading : R.string.TgPlusModelPhaseUnpacking), m.percent()));
                b.setPositiveButton(getString(R.string.TgPlusStop), (d, w) -> m.cancel());
                b.setNegativeButton(getString(R.string.TgPlusClose), null);
                break;
            case READY:
                b.setMessage(formatString(R.string.TgPlusModelReadyMessage, ParakeetModel.DISPLAY_NAME, AndroidUtilities.formatFileSize(m.installedBytes())));
                b.setPositiveButton(getString(R.string.TgPlusClose), null);
                b.setNeutralButton(getString(R.string.TgPlusVerifyFiles), (d, w) -> verifyModel());
                b.setNegativeButton(getString(R.string.TgPlusDeleteModel), (d, w) -> confirmDeleteModel());
                break;
            case ERROR: {
                StringBuilder text = new StringBuilder(TgPlusSpeechErrors.text(m.error()));
                if (m.errorDetail() != null) text.append("\n\n").append(m.errorDetail());
                b.setMessage(text.toString());
                b.setPositiveButton(getString(R.string.TgPlusRetry), (d, w) -> startModelDownload(false));
                b.setNeutralButton(getString(R.string.TgPlusDeleteModel), (d, w) -> m.delete());
                b.setNegativeButton(getString(R.string.TgPlusClose), null);
                break;
            }
            default: {
                String message = formatString(R.string.TgPlusModelMissingMessage, ParakeetModel.DISPLAY_NAME,
                    AndroidUtilities.formatFileSize(ParakeetModel.ARCHIVE_BYTES), AndroidUtilities.formatFileSize(ParakeetModel.installedBytes()),
                    AndroidUtilities.formatFileSize(ParakeetModel.requiredFreeBytes()));
                if (m.partialBytes() > 0) message += formatString(R.string.TgPlusModelAlreadyHave, AndroidUtilities.formatFileSize(m.partialBytes()));
                b.setMessage(message);
                b.setPositiveButton(getString(m.partialBytes() > 0 ? R.string.TgPlusResume : R.string.TgPlusDownload), (d, w) -> startModelDownload(false));
                b.setNegativeButton(getString(R.string.Cancel), null);
            }
        }
        showDialog(b.create());
    }

    private void startModelDownload(boolean allowMetered) {
        String error = ParakeetModelManager.getInstance().start(allowMetered);
        if (ParakeetModelManager.ERROR_WIFI_REQUIRED.equals(error)) {
            if (getParentActivity() == null) return;
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.TgPlusSpeechModel))
                .setMessage(formatString(R.string.TgPlusNotOnWifi, AndroidUtilities.formatFileSize(ParakeetModel.ARCHIVE_BYTES)))
                .setPositiveButton(getString(R.string.TgPlusDownloadAnyway), (d, w) -> startModelDownload(true))
                .setNegativeButton(getString(R.string.Cancel), null)
                .create());
        } else if (error != null) {
            BulletinFactory.of(this).createErrorBulletin(TgPlusSpeechErrors.text(error)).show();
        }
        if (listView != null) listView.adapter.update(true);
    }

    private void confirmDeleteModel() {
        if (getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.TgPlusSpeechModel))
            .setMessage(getString(R.string.TgPlusDeleteModelConfirm))
            .setPositiveButton(getString(R.string.TgPlusDeleteModel), (d, w) -> ParakeetModelManager.getInstance().delete())
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
    }

    private void verifyModel() {
        if (getParentActivity() == null) return;
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        ParakeetModelManager.getInstance().verify(code -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (fragmentView == null) return;
            if (code == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.TgPlusModelVerified)).show();
            } else {
                BulletinFactory.of(this).createErrorBulletin(formatString(R.string.TgPlusModelVerifyFailed, code)).show();
            }
        });
    }

    private String translateButtonValue() {
        if (!getMessagesController().isTranslationsManualEnabled()) return getString(R.string.TgPlusTurnedOffByTelegram);
        return getString(getMessagesController().getTranslateController().isContextTranslateEnabled() ? R.string.TgPlusOn : R.string.TgPlusOff);
    }

    private String doNotTranslateValue() {
        ArrayList<String> names = new ArrayList<>();
        for (String code : RestrictedLanguagesSelectActivity.getRestrictedLanguages()) {
            String name = TranslateAlert2.capitalFirst(TranslateAlert2.languageName(code));
            names.add(name == null ? code : name);
        }
        if (names.isEmpty()) return getString(R.string.TgPlusNone);
        java.util.Collections.sort(names);
        if (names.size() > 2) return names.get(0) + ", " + names.get(1) + " +" + (names.size() - 2);
        return TextUtils.join(", ", names);
    }

    /** Explains how Telegram's Language settings interact with Telegram Plus translation. */
    private CharSequence languageSettingsInfo() {
        StringBuilder out = new StringBuilder();
        boolean geminiActive = TgPlusSettings.isGeminiTranslationActive();
        if (!getMessagesController().isTranslationsManualEnabled()) {
            out.append(getString(R.string.TgPlusTranslateDisabledByTelegram)).append("\n\n");
        } else if (!getMessagesController().getTranslateController().isContextTranslateEnabled()) {
            out.append(getString(geminiActive ? R.string.TgPlusTranslateButtonOffGemini : R.string.TgPlusTranslateButtonOff)).append("\n\n");
        }
        String to = TranslateAlert2.getToLanguage();
        if (to != null && !RestrictedLanguagesSelectActivity.getRestrictedLanguages().contains(to.split("_")[0])) {
            out.append(formatString(R.string.TgPlusTargetNotInDoNotTranslate, TranslateAlert2.capitalFirst(TranslateAlert2.languageName(to)))).append("\n\n");
        }
        out.append(getString(R.string.TgPlusLanguageSettingsInfo));
        return out.toString();
    }

    private String testValue(String id) {
        TgPlusDiagnostics.TestResult r = TgPlusDiagnostics.getTest(id);
        if (r == null) return "";
        switch (r.result) {
            case TgPlusDiagnostics.RESULT_PASS: return getString(R.string.TgPlusTestPassed);
            case TgPlusDiagnostics.RESULT_FAIL: return getString(R.string.TgPlusTestFailed);
            default: return getString(R.string.TgPlusTestSkipped);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case BUTTON_TRANSCRIPTION_ROUTE:
                showChoice(getString(R.string.TgPlusVoiceTranscription), new CharSequence[]{
                    getString(R.string.TgPlusRouteAutomaticLong),
                    getString(R.string.TgPlusRouteOnDeviceLong),
                    getString(R.string.TgPlusRouteTelegramLong)
                }, which -> TgPlusSettings.setTranscriptionRoute(which));
                break;
            case BUTTON_DICTATION:
                TgPlusSettings.setDictationEnabled(!TgPlusSettings.isDictationEnabled());
                ((TextCheckCell) view).setChecked(TgPlusSettings.isDictationEnabled());
                break;
            case BUTTON_SPEECH_MODEL:
                showSpeechModelDialog();
                break;
            case BUTTON_MODEL_WIFI_ONLY:
                TgPlusSettings.setModelWifiOnly(!TgPlusSettings.isModelWifiOnly());
                ((TextCheckCell) view).setChecked(TgPlusSettings.isModelWifiOnly());
                break;
            case BUTTON_TRANSLATION_PROVIDER:
                showChoice(getString(R.string.TgPlusTranslationProvider), new CharSequence[]{
                    getString(R.string.TgPlusProviderTelegramLong),
                    getString(R.string.TgPlusProviderGeminiLong)
                }, which -> {
                    TgPlusSettings.setTranslationProvider(which);
                    if (which == TgPlusSettings.TRANSLATION_GEMINI && !TgPlusSettings.hasGeminiKey()) {
                        showKeyDialog();
                    }
                });
                break;
            case BUTTON_GEMINI_KEY:
                showKeyDialog();
                break;
            case BUTTON_GEMINI_MODEL:
                showModelDialog();
                break;
            case BUTTON_TRANSLATE_TO:
                showLanguageDialog();
                break;
            case BUTTON_TEST_TRANSLATION:
                runTranslationTest();
                break;
            case BUTTON_CHECK_UPDATES:
                TgPlusUpdateFlow.checkManually(getParentActivity(), result -> {
                    if (result.ok()) knownChannels = result.channels;
                    if (fragmentView != null) listView.adapter.update(true);
                });
                break;
            case BUTTON_UPDATE_CHANNEL:
                showChannelDialog();
                break;
            case BUTTON_AUTO_UPDATE:
                TgPlusSettings.setAutoUpdateCheckEnabled(!TgPlusSettings.isAutoUpdateCheckEnabled());
                ((TextCheckCell) view).setChecked(TgPlusSettings.isAutoUpdateCheckEnabled());
                break;
            case BUTTON_TELEGRAM_TRANSLATE_BUTTON:
                presentFragment(new LanguageSelectActivity());
                break;
            case BUTTON_DO_NOT_TRANSLATE:
                presentFragment(new RestrictedLanguagesSelectActivity());
                break;
            case BUTTON_FEATURE_DIAGNOSTICS:
                presentFragment(new TgPlusDiagnosticsActivity());
                break;
            case BUTTON_PUSH_DIAGNOSTICS:
                presentFragment(new PushDiagnosticsActivity());
                break;
            case BUTTON_UI_SPEED:
                TgPlusSettings.setUiSpeedEnabled(!TgPlusSettings.isUiSpeedEnabled());
                ((TextCheckCell) view).setChecked(TgPlusSettings.isUiSpeedEnabled());
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private interface Choice {
        void chosen(int which);
    }

    private void showChoice(CharSequence title, CharSequence[] options, Choice choice) {
        if (getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(title)
            .setItems(options, (dialog, which) -> {
                choice.chosen(which);
                listView.adapter.update(true);
            })
            .create());
    }

    private EditTextBoldCursor createInput(Context context, String text, String hint, boolean secret) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, getResourceProvider()));
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setPadding(dp(16), dp(11), dp(16), dp(11));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, getResourceProvider()));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()), 0.06f));
        editText.setBackground(background);
        if (text != null) editText.setText(text);
        return editText;
    }

    private void showKeyDialog() {
        Context context = getParentActivity();
        if (context == null) return;
        boolean hasKey = TgPlusSettings.hasGeminiKey();
        EditTextBoldCursor editText = createInput(context, null, getString(R.string.TgPlusGeminiKeyHint), true);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 9));

        String current = hasKey ? SecureStore.get(TgPlusSettings.GEMINI_KEY_NAME) : null;
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(getString(R.string.TgPlusGeminiKey));
        builder.setMessage(current != null
            ? formatString(R.string.TgPlusGeminiKeyCurrent, SecureStore.hint(current))
            : getString(R.string.TgPlusGeminiKeyInfo));
        builder.makeCustomMaxHeight();
        builder.setView(container);
        builder.setWidth(Math.min(dp(320), AndroidUtilities.displaySize.x * 85 / 100));
        // Buttons do not close the dialog by themselves, so an invalid key can be corrected in place.
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            String key = editText.getText().toString().trim();
            if (key.isEmpty() || key.length() > 200 || key.contains(" ")) {
                AndroidUtilities.shakeView(editText);
                return;
            }
            if (!SecureStore.put(TgPlusSettings.GEMINI_KEY_NAME, key)) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.TgPlusKeySaveFailed)).show();
                return;
            }
            TgPlusSettings.setTranslationProvider(TgPlusSettings.TRANSLATION_GEMINI);
            dialog.dismiss();
            listView.adapter.update(true);
            runTranslationTest();
        });
        if (hasKey) {
            builder.setNeutralButton(getString(R.string.TgPlusRemoveKey), (dialog, which) -> {
                SecureStore.remove(TgPlusSettings.GEMINI_KEY_NAME);
                dialog.dismiss();
                listView.adapter.update(true);
            });
        } else {
            builder.setNeutralButton(getString(R.string.TgPlusGetKey), (dialog, which) -> {
                dialog.dismiss();
                Browser.openUrl(context, GEMINI_KEY_URL);
            });
        }
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        showDialog(dialog);
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 200);
    }

    private void showModelDialog() {
        Context context = getParentActivity();
        if (context == null) return;
        EditTextBoldCursor editText = createInput(context, TgPlusSettings.getGeminiModel(), TgPlusSettings.DEFAULT_GEMINI_MODEL, false);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 9));
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(getString(R.string.TgPlusGeminiModel));
        builder.setMessage(formatString(R.string.TgPlusGeminiModelInfo, TgPlusSettings.DEFAULT_GEMINI_MODEL));
        builder.makeCustomMaxHeight();
        builder.setView(container);
        builder.setWidth(Math.min(dp(320), AndroidUtilities.displaySize.x * 85 / 100));
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            String model = editText.getText().toString().trim();
            if (!TgPlusSettings.isValidModelId(model)) {
                AndroidUtilities.shakeView(editText);
                return;
            }
            TgPlusSettings.setGeminiModel(model);
            dialog.dismiss();
            listView.adapter.update(true);
        });
        builder.setNeutralButton(getString(R.string.TgPlusResetDefault), (dialog, which) -> {
            TgPlusSettings.setGeminiModel(null);
            dialog.dismiss();
            listView.adapter.update(true);
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        showDialog(dialog);
    }

    private void showLanguageDialog() {
        ArrayList<TranslateController.Language> languages = TranslateController.getLanguages();
        CharSequence[] names = new CharSequence[languages.size()];
        for (int i = 0; i < languages.size(); i++) {
            TranslateController.Language l = languages.get(i);
            names[i] = l.ownDisplayName != null && !l.ownDisplayName.equals(l.displayName) ? l.displayName + " · " + l.ownDisplayName : l.displayName;
        }
        showChoice(getString(R.string.TgPlusTranslateTo), names, which -> TranslateAlert2.setToLanguage(languages.get(which).code));
    }

    private void showChannelDialog() {
        if (getParentActivity() == null) return;
        if (knownChannels.isEmpty()) {
            AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progress.show();
            UpdateChecker.check("channels", result -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (!result.ok()) {
                    BulletinFactory.of(this).createErrorBulletin(TgPlusUpdateFlow.errorText(result.error)).show();
                    return;
                }
                knownChannels = result.channels;
                showChannelChoices();
            });
        } else {
            showChannelChoices();
        }
    }

    private void showChannelChoices() {
        String own = UpdateChecker.installedBranch();
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<String> values = new ArrayList<>();
        labels.add(TextUtils.isEmpty(own) ? getString(R.string.TgPlusChannelDefault) : formatString(R.string.TgPlusChannelOwn, own));
        values.add("");
        for (String channel : knownChannels) {
            labels.add(channel);
            values.add(channel);
        }
        showChoice(getString(R.string.TgPlusUpdateChannel), labels.toArray(new CharSequence[0]), which -> {
            TgPlusSettings.setUpdateChannel(values.get(which));
            TgPlusSettings.setSkippedUpdateBuild(0);
        });
    }

    private void runTranslationTest() {
        if (testingTranslation) return;
        if (!TgPlusSettings.hasGeminiKey()) {
            showKeyDialog();
            return;
        }
        testingTranslation = true;
        listView.adapter.update(true);
        TgPlusSelfTests.run(getParentActivity(), TgPlusDiagnostics.TEST_TRANSLATION, () -> {
            testingTranslation = false;
            if (fragmentView == null) return;
            listView.adapter.update(true);
            TgPlusDiagnostics.TestResult r = TgPlusDiagnostics.getTest(TgPlusDiagnostics.TEST_TRANSLATION);
            if (r == null) return;
            if (TgPlusDiagnostics.RESULT_PASS.equals(r.result)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.TgPlusTranslationWorks)).show();
            } else {
                String code = r.summary == null ? "" : r.summary.split(",")[0];
                BulletinFactory.of(this).createErrorBulletin(TgPlusTranslationErrors.text(code)).show();
            }
        });
    }
}
