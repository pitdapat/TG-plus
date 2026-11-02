package org.telegram.messenger.tgplus;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.PushDiagnostics;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.tgplus.asr.AsrEngine;
import org.telegram.messenger.tgplus.asr.ParakeetEngine;
import org.telegram.messenger.tgplus.asr.ParakeetModel;
import org.telegram.messenger.tgplus.asr.ParakeetModelManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Telegram Plus feature diagnostics: event log, self-test results and the two reports.
 * Stores metadata only, in private storage excluded from backup (see tgplus_backup_rules.xml).
 */
public final class TgPlusDiagnostics {

    private static final String PREFS = "tgplus_diagnostics";
    private static final String DIR = "tgplus_diagnostics";
    private static final int MAX_TEST_OUTPUT = 500;

    public static final String TEST_TRANSLATION = "translation";
    public static final String TEST_SPEECH_MODEL = "speech_model";
    public static final String TEST_MICROPHONE = "microphone";
    public static final String TEST_DICTATION = "dictation";
    public static final String TEST_UPDATES = "updates";
    public static final List<String> TESTS = Arrays.asList(TEST_TRANSLATION, TEST_UPDATES, TEST_MICROPHONE, TEST_SPEECH_MODEL, TEST_DICTATION);

    public static final String RESULT_PASS = "pass";
    public static final String RESULT_FAIL = "fail";
    public static final String RESULT_SKIPPED = "skipped";

    private static final DispatchQueue queue = new DispatchQueue("tgplusDiagnostics");
    private static DiagnosticsLog log;

    private TgPlusDiagnostics() {}

    private static synchronized DiagnosticsLog log() {
        if (log == null) {
            File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), DIR);
            log = new DiagnosticsLog(new File(dir, "events.log"));
        }
        return log;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Never throws and never blocks the caller. */
    public static void log(DiagnosticsLog.Event event) {
        if (event == null || ApplicationLoader.applicationContext == null) return;
        queue.postRunnable(() -> log().append(event));
    }

    // Self-tests.

    public static final class TestResult {
        public final String id, result, summary, output;
        public final long time, durationMs;

        TestResult(String id, String result, String summary, String output, long time, long durationMs) {
            this.id = id;
            this.result = result;
            this.summary = summary;
            this.output = output;
            this.time = time;
            this.durationMs = durationMs;
        }
    }

    /**
     * {@code summary} is text produced by our own code (status codes, timings). {@code output}
     * is the result of fixed test input (the bundled sample or the fixed test sentence) and
     * appears only in the full report.
     */
    public static void recordTest(String id, String result, long durationMs, String summary, String output) {
        String safeOutput = output == null ? "" : output.length() > MAX_TEST_OUTPUT ? output.substring(0, MAX_TEST_OUTPUT) + "…" : output;
        prefs().edit()
            .putString("test_" + id + "_result", result)
            .putString("test_" + id + "_summary", summary == null ? "" : summary)
            .putString("test_" + id + "_output", safeOutput)
            .putLong("test_" + id + "_time", System.currentTimeMillis())
            .putLong("test_" + id + "_duration", durationMs)
            .apply();
        DiagnosticsLog.Event.Builder event = DiagnosticsLog.event("selftest", DiagnosticsLog.safeName(id)).duration(durationMs);
        log(RESULT_PASS.equals(result) ? event.ok().build() : RESULT_FAIL.equals(result) ? event.fail(summaryCode(summary)).build() : event.outcome("skipped").build());
    }

    private static String summaryCode(String summary) {
        if (summary == null) return DiagnosticsLog.OTHER_ERROR;
        String first = summary.split("[ ,;]")[0];
        return DiagnosticsLog.safeError(first);
    }

    public static TestResult getTest(String id) {
        SharedPreferences p = prefs();
        String result = p.getString("test_" + id + "_result", null);
        if (result == null) return null;
        return new TestResult(id, result, p.getString("test_" + id + "_summary", ""), p.getString("test_" + id + "_output", ""),
            p.getLong("test_" + id + "_time", 0), p.getLong("test_" + id + "_duration", 0));
    }

    public static void clear() {
        prefs().edit().clear().apply();
        queue.postRunnable(() -> log().clear());
    }

    // Reports.

    public static void buildReport(boolean full, Utilities.Callback<String> done) {
        queue.postRunnable(() -> {
            String report = buildReportBlocking(full);
            AndroidUtilities.runOnUIThread(() -> done.run(report));
        });
    }

    public static String buildReportBlocking(boolean full) {
        Context context = ApplicationLoader.applicationContext;
        List<DiagnosticsLog.Event> events = log().readAll();
        ArrayList<DiagnosticsReport.Section> sections = new ArrayList<>();
        sections.add(new DiagnosticsReport.Section("App and device", environment(context), true));
        sections.add(new DiagnosticsReport.Section("Settings", settings(), true));
        sections.add(new DiagnosticsReport.Section("Speech engine", speech(full), true));
        sections.add(new DiagnosticsReport.Section("Self-tests", tests(full), true));
        sections.add(new DiagnosticsReport.Section("UI speed", uiSpeed(events), true));
        sections.add(new DiagnosticsReport.Section("Notification diagnostics", push(full), true));
        if (full) {
            sections.add(new DiagnosticsReport.Section("Update files", updateFiles(), false));
        }
        return DiagnosticsReport.build(full, "Telegram Plus diagnostics", sections, events, TimeZone.getDefault(),
            "Full report: Settings > Telegram Plus > Diagnostics > Download full report.");
    }

    private static List<String> uiSpeed(List<DiagnosticsLog.Event> events) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("Measuring: " + (TgPlusSettings.isUiSpeedEnabled() ? "on" : "off (turn it on in Settings > Telegram Plus > Diagnostics)"));
        lines.addAll(UiSpeedSummary.lines(events));
        return lines;
    }

    /** UI speed comparison for the Feature diagnostics screen. Reads the log off the main thread. */
    public static void uiSpeedText(Utilities.Callback<String> done) {
        queue.postRunnable(() -> {
            String text = TextUtils.join("\n\n", UiSpeedSummary.lines(log().readAll()));
            AndroidUtilities.runOnUIThread(() -> done.run(text));
        });
    }

    private static String when(long time) {
        if (time <= 0) return "never";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        return format.format(new Date(time));
    }

    private static List<String> environment(Context context) {
        ArrayList<String> lines = new ArrayList<>();
        String versionCode = "?";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            versionCode = Long.toString(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (Exception ignore) {
        }
        lines.add("App: " + BuildVars.BUILD_VERSION_STRING + " (" + versionCode + "), package " + context.getPackageName());
        int build = UpdateChecker.installedBuild();
        lines.add("Build: " + (build > 0 ? "#" + build : "local (not built by the release workflow)")
            + (TextUtils.isEmpty(UpdateChecker.installedBranch()) ? "" : ", branch " + UpdateChecker.installedBranch())
            + (TextUtils.isEmpty(UpdateChecker.installedSha()) ? "" : ", commit " + UpdateChecker.installedSha()));
        lines.add("Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        lines.add("ABI: " + (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
        try {
            long page = Os.sysconf(OsConstants._SC_PAGESIZE);
            lines.add("Memory page size: " + (page / 1024) + " KB");
        } catch (Throwable ignore) {
            lines.add("Memory page size: unknown");
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            lines.add("RAM: " + AndroidUtilities.formatFileSize(mi.totalMem) + " total, " + AndroidUtilities.formatFileSize(mi.availMem) + " available" + (mi.lowMemory ? ", LOW" : ""));
        } catch (Throwable ignore) {
        }
        lines.add("Free storage: " + AndroidUtilities.formatFileSize(context.getFilesDir().getUsableSpace()));
        lines.add("Network: " + (ApplicationLoader.isNetworkOnline() ? "online" : "offline"));
        lines.add("Telegram Premium: " + (UserConfig.getInstance(UserConfig.selectedAccount).isPremium() ? "yes" : "no"));
        lines.add("Time zone: " + TimeZone.getDefault().getID());
        return lines;
    }

    private static List<String> settings() {
        ArrayList<String> lines = new ArrayList<>();
        int route = TgPlusSettings.getTranscriptionRoute();
        lines.add("Voice transcription: " + (route == TgPlusSettings.TRANSCRIPTION_ON_DEVICE ? "always on-device"
            : route == TgPlusSettings.TRANSCRIPTION_TELEGRAM ? "Telegram only" : "automatic") + " (transcription buttons are not built yet; only the model can be tested)");
        lines.add("Dictation button: " + (TgPlusSettings.isDictationEnabled() ? "on" : "off") + " (not built yet; only the model can be tested)");
        lines.add("Translation provider: " + (TgPlusSettings.getTranslationProvider() == TgPlusSettings.TRANSLATION_GEMINI ? "Gemini" : "Telegram"));
        lines.add("Gemini model: " + TgPlusSettings.getGeminiModel());
        lines.add("Gemini API key stored: " + (TgPlusSettings.hasGeminiKey() ? "yes" : "no"));
        lines.add("Gemini translation active: " + (TgPlusSettings.isGeminiTranslationActive() ? "yes" : "no"));
        String toLanguage = org.telegram.ui.Components.TranslateAlert2.getToLanguage();
        lines.add("Translate-to language: " + toLanguage);
        try {
            org.telegram.messenger.MessagesController mc = org.telegram.messenger.MessagesController.getInstance(UserConfig.selectedAccount);
            lines.add("Telegram translation allowed by server: " + (mc.isTranslationsManualEnabled() ? "yes" : "no"));
            lines.add("Telegram Show Translate Button: " + (mc.getTranslateController().isContextTranslateEnabled() ? "on" : "off"));
            ArrayList<String> restricted = new ArrayList<>(org.telegram.ui.RestrictedLanguagesSelectActivity.getRestrictedLanguages());
            java.util.Collections.sort(restricted);
            lines.add("Telegram Do Not Translate: " + (restricted.isEmpty() ? "none" : TextUtils.join(", ", restricted)));
        } catch (Throwable ignore) {
        }
        lines.add("Inline translations stored: " + TgPlusInlineTranslations.storedCount());
        lines.add("Update channel: " + UpdateChecker.effectiveChannel() + (TextUtils.isEmpty(TgPlusSettings.getUpdateChannel()) ? " (this build's branch)" : ""));
        lines.add("Automatic update check: " + (TgPlusSettings.isAutoUpdateCheckEnabled() ? "on" : "off") + ", last check " + when(TgPlusSettings.getLastUpdateCheck()));
        lines.add("Update source: github.com/" + UpdateChecker.repository());
        return lines;
    }

    private static List<String> speech(boolean full) {
        ArrayList<String> lines = new ArrayList<>();
        ParakeetEngine engine = ParakeetEngine.getInstance();
        ParakeetModelManager manager = ParakeetModelManager.getInstance();
        AsrEngine.Status status = engine.getStatus();
        lines.add("Engine: " + engine.displayName() + " (" + engine.id() + "), sherpa-onnx " + ParakeetModel.RUNTIME_VERSION);
        lines.add("State: " + status.state.name() + (TextUtils.isEmpty(status.code) || status.code.equals(status.state.name()) ? "" : " (" + status.code + ")")
            + (engine.isLoaded() ? ", model loaded in memory" : ""));
        switch (manager.state()) {
            case READY:
                lines.add("Model: installed, " + AndroidUtilities.formatFileSize(manager.installedBytes()));
                break;
            case DOWNLOADING:
            case UNPACKING:
                lines.add("Model: " + manager.state().name().toLowerCase(Locale.US) + " " + manager.percent() + "%");
                break;
            case ERROR:
                lines.add("Model: last attempt failed (" + manager.error() + ")" + (full && manager.errorDetail() != null ? " - " + manager.errorDetail() : ""));
                break;
            default:
                lines.add("Model: not installed" + (manager.partialBytes() > 0 ? ", partial download " + AndroidUtilities.formatFileSize(manager.partialBytes()) : ""));
        }
        lines.add("Download: " + AndroidUtilities.formatFileSize(ParakeetModel.ARCHIVE_BYTES) + ", needs "
            + AndroidUtilities.formatFileSize(ParakeetModel.requiredFreeBytes()) + " free while installing; Wi-Fi only: "
            + (TgPlusSettings.isModelWifiOnly() ? "on" : "off"));
        lines.add("Process memory now: " + ParakeetEngine.memoryMb() + " MB, highest so far: " + ParakeetEngine.peakMemoryMb() + " MB");
        if (full) {
            lines.add("Model folder: " + manager.dir().getAbsolutePath());
            if (engine.errorDetail() != null) lines.add("Engine error detail: " + engine.errorDetail());
            lines.add("Free storage for app data: " + AndroidUtilities.formatFileSize(ApplicationLoader.applicationContext.getFilesDir().getUsableSpace()));
        }
        return lines;
    }

    private static List<String> tests(boolean full) {
        ArrayList<String> lines = new ArrayList<>();
        for (String id : TESTS) {
            TestResult r = getTest(id);
            if (r == null) {
                lines.add(id + ": not run");
                continue;
            }
            lines.add(id + ": " + r.result + " (" + r.durationMs + " ms, " + when(r.time) + ")" + (TextUtils.isEmpty(r.summary) ? "" : " - " + r.summary));
            if (full && !TextUtils.isEmpty(r.output)) {
                lines.add("  output: " + r.output.replace('\n', ' '));
            }
        }
        return lines;
    }

    private static List<String> push(boolean full) {
        ArrayList<String> lines = new ArrayList<>();
        try {
            UserConfig account = UserConfig.getInstance(UserConfig.selectedAccount);
            PushDiagnostics.Snapshot s = PushDiagnostics.snapshot(UserConfig.selectedAccount, account.getClientUserId());
            lines.add("Firebase token: " + s.tokenState + (s.tokenError.isEmpty() ? "" : " (" + s.tokenError + ")"));
            lines.add("Telegram registration: " + s.registrationState + (s.registrationError.isEmpty() ? "" : " (" + s.registrationError + ")"));
            lines.add("Pushes received: " + s.receivedCount + ", last " + when(s.receivedAt));
            if (full) {
                lines.add("Last token request: " + when(s.tokenRequestAt) + ", result " + when(s.tokenResultAt));
                lines.add("Last registration result: " + when(s.registrationAt));
                lines.add("Last push sent at: " + when(s.sentAt) + ", callback finished " + when(s.finishedAt));
                lines.add("Priority: " + s.priority + ", original priority: " + s.originalPriority);
                lines.add("Stored registration: " + (account.registeredForPush ? "yes" : "no") + ", logged in: " + (account.isClientActivated() ? "yes" : "no"));
            }
        } catch (Throwable e) {
            lines.add("Unavailable");
        }
        return lines;
    }

    private static List<String> updateFiles() {
        ArrayList<String> lines = new ArrayList<>();
        File[] files = UpdateChecker.updateDir().listFiles();
        if (files == null || files.length == 0) {
            lines.add("No downloaded update files.");
        } else {
            for (File f : files) lines.add(f.getName() + " (" + f.length() + " bytes, " + when(f.lastModified()) + ")");
        }
        return lines;
    }

    // Saving the full report.

    public static String reportFileName() {
        return "tgplus-diagnostics-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
    }

    /** Writes the full report to a document the user picked (ACTION_CREATE_DOCUMENT). */
    public static void writeFullReport(Uri uri, Utilities.Callback<Boolean> done) {
        queue.postRunnable(() -> {
            boolean ok = false;
            try (OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri, "w")) {
                if (out != null) {
                    out.write(buildReportBlocking(true).getBytes(StandardCharsets.UTF_8));
                    ok = true;
                }
            } catch (Exception ignore) {
            }
            final boolean result = ok;
            log(DiagnosticsLog.event("diagnostics", "save_report").build());
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    /** Writes the full report to a private file for sharing through the FileProvider. */
    public static void writeShareFile(Utilities.Callback<File> done) {
        queue.postRunnable(() -> {
            File dir = new File(new File(ApplicationLoader.applicationContext.getFilesDir(), "cache"), "tgplus-diagnostics");
            File file = null;
            try {
                if (!dir.exists()) dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                file = new File(dir, reportFileName());
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(buildReportBlocking(true).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                file = null;
            }
            final File result = file;
            log(DiagnosticsLog.event("diagnostics", "share_report").build());
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    public static String formatTime(long time) {
        return time <= 0 ? "never" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time));
    }
}
