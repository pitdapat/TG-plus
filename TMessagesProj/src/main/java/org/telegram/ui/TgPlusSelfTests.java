package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.tgplus.GeminiTranslator;
import org.telegram.messenger.tgplus.TgPlusDiagnostics;
import org.telegram.messenger.tgplus.TgPlusSettings;
import org.telegram.messenger.tgplus.UpdateChecker;
import org.telegram.messenger.tgplus.asr.AsrEngine;
import org.telegram.messenger.tgplus.asr.ParakeetEngine;
import org.telegram.ui.Components.TranslateAlert2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Self-tests for the Feature diagnostics screen. Results are stored by {@link TgPlusDiagnostics}. */
public final class TgPlusSelfTests {

    /** Fixed input, so its translation can be shown in the full report. */
    public static final String TRANSLATION_SAMPLE = "Good morning, how are you?";
    public static final int MIC_PERMISSION_REQUEST = 3;
    private static final int MIC_SECONDS = 3;
    private static final int SAMPLE_RATE = 16000;

    private TgPlusSelfTests() {}

    public static void run(Activity activity, String id, Runnable done) {
        Runnable finish = () -> AndroidUtilities.runOnUIThread(() -> {
            if (done != null) done.run();
        });
        switch (id) {
            case TgPlusDiagnostics.TEST_TRANSLATION:
                translation(finish);
                break;
            case TgPlusDiagnostics.TEST_UPDATES:
                updates(finish);
                break;
            case TgPlusDiagnostics.TEST_MICROPHONE:
                microphone(activity, finish);
                break;
            case TgPlusDiagnostics.TEST_SPEECH_MODEL:
            case TgPlusDiagnostics.TEST_DICTATION:
                speechStub(id, finish);
                break;
            default:
                finish.run();
        }
    }

    /** Runs every test in order, then calls {@code done}. */
    public static void runAll(Activity activity, Runnable afterEach, Runnable done) {
        List<String> queue = new ArrayList<>(TgPlusDiagnostics.TESTS);
        runNext(activity, queue, afterEach, done);
    }

    private static void runNext(Activity activity, List<String> queue, Runnable afterEach, Runnable done) {
        if (queue.isEmpty()) {
            if (done != null) done.run();
            return;
        }
        String id = queue.remove(0);
        run(activity, id, () -> {
            if (afterEach != null) afterEach.run();
            runNext(activity, queue, afterEach, done);
        });
    }

    private static void translation(Runnable done) {
        if (!TgPlusSettings.hasGeminiKey()) {
            TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_TRANSLATION, TgPlusDiagnostics.RESULT_SKIPPED, 0,
                "NO_KEY: no Gemini API key stored", null);
            done.run();
            return;
        }
        String to = TranslateAlert2.getToLanguage();
        if (to == null || to.startsWith("en")) to = "es";
        final String target = to;
        GeminiTranslator.translate(TRANSLATION_SAMPLE, "en", target, "selftest", result -> {
            if (result.ok()) {
                TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_TRANSLATION, TgPlusDiagnostics.RESULT_PASS, result.durationMs,
                    "HTTP " + result.httpStatus + ", model " + TgPlusSettings.getGeminiModel() + ", en -> " + target,
                    TRANSLATION_SAMPLE + " -> " + result.text);
            } else {
                TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_TRANSLATION, TgPlusDiagnostics.RESULT_FAIL, result.durationMs,
                    result.error + ", HTTP " + result.httpStatus + ", model " + TgPlusSettings.getGeminiModel(), null);
            }
            done.run();
        });
    }

    private static void updates(Runnable done) {
        UpdateChecker.check("selftest", result -> {
            String summary = (result.ok() ? "" : result.error + ", ") + "HTTP " + result.httpStatus
                + ", channel " + result.channel + ", " + result.status.name().toLowerCase(Locale.US)
                + (result.candidate == null ? "" : ", release build #" + result.candidate.buildNumber)
                + ", installed " + TgPlusUpdateFlow.describeInstalled();
            TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_UPDATES, result.ok() ? TgPlusDiagnostics.RESULT_PASS : TgPlusDiagnostics.RESULT_FAIL,
                result.durationMs, summary, null);
            done.run();
        });
    }

    private static void speechStub(String id, Runnable done) {
        AsrEngine.Status status = ParakeetEngine.getInstance().getStatus();
        TgPlusDiagnostics.recordTest(id, TgPlusDiagnostics.RESULT_SKIPPED, 0,
            status.code + ": " + ParakeetEngine.getInstance().displayName() + " engine is not built into this version", null);
        done.run();
    }

    private static void microphone(Activity activity, Runnable done) {
        if (Build.VERSION.SDK_INT >= 23 && activity != null
            && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_MICROPHONE, TgPlusDiagnostics.RESULT_SKIPPED, 0,
                "NO_PERMISSION: microphone permission not granted; allow it and run the test again", null);
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
            done.run();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            long start = SystemClock.elapsedRealtime();
            AudioRecord recorder = null;
            try {
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) {
                    TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_MICROPHONE, TgPlusDiagnostics.RESULT_FAIL, 0, "MIC_UNSUPPORTED: 16 kHz mono not supported", null);
                    done.run();
                    return;
                }
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLE_RATE * 2));
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_MICROPHONE, TgPlusDiagnostics.RESULT_FAIL, 0, "MIC_INIT: recorder could not start (is another app using the mic?)", null);
                    done.run();
                    return;
                }
                short[] buffer = new short[1024];
                long total = 0, target = (long) SAMPLE_RATE * MIC_SECONDS;
                int peak = 0;
                double sumSquares = 0;
                recorder.startRecording();
                while (total < target) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read < 0) break;
                    for (int i = 0; i < read; i++) {
                        int v = Math.abs((int) buffer[i]);
                        if (v > peak) peak = v;
                        sumSquares += (double) buffer[i] * buffer[i];
                    }
                    total += read;
                }
                long duration = SystemClock.elapsedRealtime() - start;
                if (total == 0) {
                    TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_MICROPHONE, TgPlusDiagnostics.RESULT_FAIL, duration, "MIC_NO_DATA: no audio samples were read", null);
                } else {
                    int peakPercent = (int) Math.round(peak * 100.0 / 32767);
                    int rmsPercent = (int) Math.round(Math.sqrt(sumSquares / total) * 100.0 / 32767);
                    String note = peakPercent < 3 ? " (very quiet: speak during the test; if you did, the mic may be muted or in use by another app)" : "";
                    TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_MICROPHONE, TgPlusDiagnostics.RESULT_PASS, duration,
                        "OK: " + (total * 1000 / SAMPLE_RATE) + " ms recorded, peak " + peakPercent + "%, average " + rmsPercent + "%" + note, null);
                }
            } catch (Exception e) {
                TgPlusDiagnostics.recordTest(TgPlusDiagnostics.TEST_MICROPHONE, TgPlusDiagnostics.RESULT_FAIL, SystemClock.elapsedRealtime() - start,
                    "MIC_ERROR: " + e.getClass().getSimpleName(), null);
            } finally {
                if (recorder != null) {
                    try {
                        recorder.stop();
                    } catch (Exception ignore) {
                    }
                    recorder.release();
                }
            }
            done.run();
        });
    }
}
