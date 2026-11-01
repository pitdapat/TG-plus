package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.tgplus.TgPlusDiagnostics;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

/** Settings > Telegram Plus > Feature diagnostics: self-tests and reports. */
public class TgPlusDiagnosticsActivity extends UniversalFragment {

    private static final int BUTTON_TEST_BASE = 100;
    private static final int BUTTON_RUN_ALL = 1;
    private static final int BUTTON_COPY = 2;
    private static final int BUTTON_DOWNLOAD = 3;
    private static final int BUTTON_SHARE = 4;
    private static final int BUTTON_CLEAR = 5;

    private static final int REQUEST_SAVE_REPORT = 4071;

    private final HashSet<String> running = new HashSet<>();
    private boolean runningAll;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.TgPlusFeatureDiagnostics);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) listView.adapter.update(false);
    }

    private String testLabel(String id) {
        switch (id) {
            case TgPlusDiagnostics.TEST_TRANSLATION: return getString(R.string.TgPlusTestTranslationName);
            case TgPlusDiagnostics.TEST_UPDATES: return getString(R.string.TgPlusTestUpdatesName);
            case TgPlusDiagnostics.TEST_MICROPHONE: return getString(R.string.TgPlusTestMicrophoneName);
            case TgPlusDiagnostics.TEST_SPEECH_MODEL: return getString(R.string.TgPlusTestSpeechName);
            case TgPlusDiagnostics.TEST_DICTATION: return getString(R.string.TgPlusTestDictationName);
            default: return id;
        }
    }

    private String testValue(String id) {
        if (running.contains(id)) return getString(R.string.TgPlusRunning);
        TgPlusDiagnostics.TestResult r = TgPlusDiagnostics.getTest(id);
        if (r == null) return getString(R.string.TgPlusTestNotRun);
        switch (r.result) {
            case TgPlusDiagnostics.RESULT_PASS: return getString(R.string.TgPlusTestPassed);
            case TgPlusDiagnostics.RESULT_FAIL: return getString(R.string.TgPlusTestFailed);
            default: return getString(R.string.TgPlusTestSkipped);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.TgPlusSelfTests)));
        StringBuilder details = new StringBuilder();
        for (int i = 0; i < TgPlusDiagnostics.TESTS.size(); i++) {
            String id = TgPlusDiagnostics.TESTS.get(i);
            items.add(UItem.asButton(BUTTON_TEST_BASE + i, testLabel(id), testValue(id)));
            TgPlusDiagnostics.TestResult r = TgPlusDiagnostics.getTest(id);
            if (r != null && !TextUtils.isEmpty(r.summary)) {
                if (details.length() > 0) details.append("\n\n");
                details.append(testLabel(id)).append(" (").append(TgPlusDiagnostics.formatTime(r.time)).append("): ").append(r.summary);
            }
        }
        items.add(UItem.asButton(BUTTON_RUN_ALL, R.drawable.msg_retry, getString(runningAll ? R.string.TgPlusRunning : R.string.TgPlusRunAllTests)).accent());
        items.add(UItem.asShadow(details.length() > 0 ? details.toString() : getString(R.string.TgPlusSelfTestsInfo)));

        items.add(UItem.asHeader(getString(R.string.TgPlusReports)));
        items.add(UItem.asButton(BUTTON_COPY, R.drawable.msg_copy, getString(R.string.TgPlusCopyShortReport)));
        items.add(UItem.asButton(BUTTON_DOWNLOAD, R.drawable.msg_download, getString(R.string.TgPlusDownloadFullReport)));
        items.add(UItem.asButton(BUTTON_SHARE, R.drawable.msg_share, getString(R.string.TgPlusShareFullReport)));
        items.add(UItem.asButton(BUTTON_CLEAR, R.drawable.msg_delete, getString(R.string.TgPlusClearDiagnostics)).red());
        items.add(UItem.asShadow(getString(R.string.TgPlusReportsInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        int testIndex = item.id - BUTTON_TEST_BASE;
        if (testIndex >= 0 && testIndex < TgPlusDiagnostics.TESTS.size()) {
            runTest(TgPlusDiagnostics.TESTS.get(testIndex));
            return;
        }
        switch (item.id) {
            case BUTTON_RUN_ALL:
                runAll();
                break;
            case BUTTON_COPY:
                TgPlusDiagnostics.buildReport(false, report -> {
                    AndroidUtilities.addToClipboard(report);
                    if (fragmentView != null) BulletinFactory.of(this).createCopyBulletin(getString(R.string.TgPlusShortReportCopied)).show();
                });
                break;
            case BUTTON_DOWNLOAD:
                saveFullReport();
                break;
            case BUTTON_SHARE:
                shareFullReport();
                break;
            case BUTTON_CLEAR:
                showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(getString(R.string.TgPlusClearDiagnostics))
                    .setMessage(getString(R.string.TgPlusClearDiagnosticsConfirm))
                    .setPositiveButton(getString(R.string.TgPlusClear), (d, w) -> {
                        TgPlusDiagnostics.clear();
                        listView.adapter.update(true);
                    })
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .create());
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void runTest(String id) {
        if (runningAll || running.contains(id)) return;
        running.add(id);
        listView.adapter.update(true);
        TgPlusSelfTests.run(getParentActivity(), id, () -> {
            running.remove(id);
            if (fragmentView != null) listView.adapter.update(true);
        });
    }

    private void runAll() {
        if (runningAll || !running.isEmpty()) return;
        runningAll = true;
        listView.adapter.update(true);
        TgPlusSelfTests.runAll(getParentActivity(), () -> {
            if (fragmentView != null) listView.adapter.update(true);
        }, () -> {
            runningAll = false;
            if (fragmentView != null) listView.adapter.update(true);
        });
    }

    private void saveFullReport() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, TgPlusDiagnostics.reportFileName());
            startActivityForResult(intent, REQUEST_SAVE_REPORT);
        } catch (Exception e) {
            // No document picker on this device: fall back to sharing the file.
            shareFullReport();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_SAVE_REPORT) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        TgPlusDiagnostics.writeFullReport(uri, ok -> {
            if (fragmentView == null) return;
            if (ok) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.TgPlusFullReportSaved)).show();
            } else {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.TgPlusFullReportFailed)).show();
            }
        });
    }

    private void shareFullReport() {
        TgPlusDiagnostics.writeShareFile(file -> {
            Activity activity = getParentActivity();
            if (activity == null) return;
            if (file == null) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.TgPlusFullReportFailed)).show();
                return;
            }
            try {
                Uri uri = Build.VERSION.SDK_INT >= 24
                    ? FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file)
                    : Uri.fromFile(file);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(Intent.createChooser(intent, getString(R.string.TgPlusShareFullReport)));
            } catch (Exception e) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.TgPlusFullReportFailed)).show();
            }
        });
    }
}
