package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.tgplus.DiagnosticsLog;
import org.telegram.messenger.tgplus.TgPlusDiagnostics;
import org.telegram.messenger.tgplus.TgPlusSettings;
import org.telegram.messenger.tgplus.UpdateChecker;
import org.telegram.messenger.tgplus.UpdatePolicy;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.AlertsCreator;

import java.io.File;

/** Dialogs for checking, downloading and installing Telegram Plus updates from GitHub Releases. */
public final class TgPlusUpdateFlow {

    private static boolean autoCheckRunning;

    private TgPlusUpdateFlow() {}

    public static String describeInstalled() {
        int build = UpdateChecker.installedBuild();
        String branch = UpdateChecker.installedBranch();
        String sha = UpdateChecker.installedSha();
        if (build <= 0) return getString(R.string.TgPlusUpdateLocalBuild);
        StringBuilder out = new StringBuilder("#").append(build);
        if (!TextUtils.isEmpty(branch)) out.append(" · ").append(branch);
        if (!TextUtils.isEmpty(sha)) out.append(" · ").append(sha);
        return out.toString();
    }

    public static String describeCandidate(UpdatePolicy.Candidate c) {
        return "#" + c.buildNumber + " · " + c.release.channel() + " · " + c.shortSha;
    }

    public static String errorText(String error) {
        if (error == null) return "";
        switch (error) {
            case UpdateChecker.ERROR_NETWORK: return getString(R.string.TgPlusErrorNetwork);
            case UpdateChecker.ERROR_TIMEOUT: return getString(R.string.TgPlusErrorTimeout);
            case UpdateChecker.ERROR_RATE_LIMIT: return getString(R.string.TgPlusUpdateErrorRateLimit);
            case UpdateChecker.ERROR_SIZE:
            case UpdateChecker.ERROR_DIGEST: return getString(R.string.TgPlusUpdateErrorCorrupt);
            case UpdateChecker.ERROR_PACKAGE:
            case UpdateChecker.ERROR_SIGNATURE: return getString(R.string.TgPlusUpdateErrorSignature);
            case UpdateChecker.ERROR_STORAGE: return getString(R.string.TgPlusUpdateErrorStorage);
            default: return formatString(R.string.TgPlusErrorCode, error);
        }
    }

    /** Manual check from settings: shows progress, then the outcome. */
    public static void checkManually(Activity activity, Utilities.Callback<UpdateChecker.CheckResult> onFinished) {
        if (activity == null) return;
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        UpdateChecker.check("manual", result -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (onFinished != null) onFinished.run(result);
            showResult(activity, result, true);
        });
    }

    /** At most once per day, if enabled; prompts only when a new build is available. */
    public static void maybeAutoCheck(Activity activity) {
        if (activity == null || autoCheckRunning || !TgPlusSettings.isAutoUpdateCheckEnabled()) return;
        if (UpdateChecker.installedBuild() <= 0) return;
        long since = System.currentTimeMillis() - TgPlusSettings.getLastUpdateCheck();
        if (since >= 0 && since < UpdateChecker.AUTO_CHECK_INTERVAL_MS) return;
        autoCheckRunning = true;
        UpdateChecker.check("auto", result -> {
            autoCheckRunning = false;
            if (result.status != UpdatePolicy.Status.UPDATE_AVAILABLE || result.candidate == null) return;
            if (result.candidate.buildNumber == TgPlusSettings.getSkippedUpdateBuild()) return;
            if (activity.isFinishing()) return;
            showResult(activity, result, false);
        });
    }

    private static void showResult(Activity activity, UpdateChecker.CheckResult result, boolean manual) {
        if (activity == null || activity.isFinishing()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.TgPlusUpdates));
        if (!result.ok()) {
            builder.setMessage(formatString(R.string.TgPlusUpdateCheckFailed, errorText(result.error)));
            builder.setPositiveButton(getString(R.string.OK), null);
            builder.show();
            return;
        }
        UpdatePolicy.Candidate c = result.candidate;
        switch (result.status) {
            case NO_RELEASE:
                builder.setMessage(formatString(R.string.TgPlusUpdateNoRelease, result.channel));
                builder.setPositiveButton(getString(R.string.OK), null);
                break;
            case UP_TO_DATE:
                builder.setMessage(formatString(R.string.TgPlusUpdateUpToDate, describeInstalled()));
                builder.setPositiveButton(getString(R.string.OK), null);
                break;
            case INSTALLED_NEWER:
                builder.setMessage(formatString(R.string.TgPlusUpdateInstalledNewer, describeInstalled(), describeCandidate(c)));
                builder.setPositiveButton(getString(R.string.OK), null);
                builder.setNegativeButton(getString(R.string.TgPlusUpdateInstallAnyway), (d, w) -> downloadAndInstall(activity, c));
                break;
            case LOCAL_BUILD:
            case UPDATE_AVAILABLE:
            default:
                String size = c.apk.size > 0 ? AndroidUtilities.formatFileSize(c.apk.size) : "?";
                int message = result.status == UpdatePolicy.Status.LOCAL_BUILD ? R.string.TgPlusUpdateFromLocal : R.string.TgPlusUpdateAvailable;
                builder.setMessage(formatString(message, describeCandidate(c), size, describeInstalled()));
                builder.setPositiveButton(getString(R.string.TgPlusUpdateDownload), (d, w) -> downloadAndInstall(activity, c));
                if (manual) {
                    builder.setNegativeButton(getString(R.string.Cancel), null);
                } else {
                    builder.setNegativeButton(getString(R.string.TgPlusUpdateLater), null);
                    builder.setNeutralButton(getString(R.string.TgPlusUpdateSkip), (d, w) -> TgPlusSettings.setSkippedUpdateBuild(c.buildNumber));
                }
                break;
        }
        builder.show();
    }

    public static void downloadAndInstall(Activity activity, UpdatePolicy.Candidate candidate) {
        if (activity == null || candidate == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            // Ask first, so the download is not wasted.
            AlertsCreator.createApkRestrictedDialog(activity, null).show();
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
        progress.setMessage(formatString(R.string.TgPlusUpdateDownloading, describeCandidate(candidate)));
        progress.setCanCancel(true);
        final UpdateChecker.Download[] download = new UpdateChecker.Download[1];
        progress.setNegativeButton(getString(R.string.Cancel), (d, w) -> {
            if (download[0] != null) download[0].cancel();
        });
        progress.show();
        download[0] = UpdateChecker.download(candidate, new UpdateChecker.DownloadListener() {
            @Override
            public void onProgress(int percent) {
                progress.setProgress(percent);
            }

            @Override
            public void onDone(File file, String error) {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (activity.isFinishing()) return;
                if (file == null) {
                    if (UpdateChecker.ERROR_CANCELLED.equals(error)) return;
                    new AlertDialog.Builder(activity)
                        .setTitle(getString(R.string.TgPlusUpdates))
                        .setMessage(formatString(R.string.TgPlusUpdateDownloadFailed, errorText(error)))
                        .setPositiveButton(getString(R.string.OK), null)
                        .show();
                    return;
                }
                install(activity, file);
            }
        });
    }

    public static void install(Activity activity, File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", apk);
            } else {
                uri = Uri.fromFile(apk);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            TgPlusDiagnostics.log(DiagnosticsLog.event("update", "install").ok().build());
        } catch (Exception e) {
            TgPlusDiagnostics.log(DiagnosticsLog.event("update", "install").fail("INSTALL_INTENT").build());
            new AlertDialog.Builder(activity)
                .setTitle(getString(R.string.TgPlusUpdates))
                .setMessage(formatString(R.string.TgPlusUpdateDownloadFailed, formatString(R.string.TgPlusErrorCode, "INSTALL_INTENT")))
                .setPositiveButton(getString(R.string.OK), null)
                .show();
        }
    }
}
