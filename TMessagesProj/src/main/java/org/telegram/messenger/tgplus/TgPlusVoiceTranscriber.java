package org.telegram.messenger.tgplus;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.tgplus.asr.AsrEngine;
import org.telegram.messenger.tgplus.asr.AudioChunker;
import org.telegram.messenger.tgplus.asr.ParakeetEngine;
import org.telegram.messenger.tgplus.asr.TranscriptionRouting;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Transcribes voice messages on the phone with the Parakeet model. The result is handed back as plain
 * text, so the chat shows it exactly as it shows a transcript from Telegram.
 */
public final class TgPlusVoiceTranscriber {

    public interface Callback {
        /** Called on the main thread. Exactly one of text and error is non-null. */
        void onDone(String text, String error);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final long DOWNLOAD_TIMEOUT_MS = 2 * 60 * 1000;

    private TgPlusVoiceTranscriber() {}

    /** True if this voice message is transcribed on the phone under the current setting. */
    public static boolean useOnDevice(MessageObject message) {
        if (message == null || message.messageOwner == null || !message.isVoice()) return false;
        boolean premium = UserConfig.getInstance(message.currentAccount).isPremium();
        return TranscriptionRouting.useOnDevice(TgPlusSettings.getTranscriptionRoute(), premium);
    }

    public static void transcribe(MessageObject message, Callback callback) {
        final long start = SystemClock.elapsedRealtime();
        AsrEngine.Status status = ParakeetEngine.getInstance().getStatus();
        if (!status.isReady()) {
            finish(message, callback, start, 0, 0, null, status.state == AsrEngine.State.ERROR ? status.code : "MODEL_MISSING");
            return;
        }
        withFile(message, (file, downloadError) -> {
            if (file == null) {
                finish(message, callback, start, 0, 0, null, downloadError);
                return;
            }
            Utilities.globalQueue.postRunnable(() -> decodeAndRecognize(message, file, callback, start));
        });
    }

    private interface FileCallback {
        void onFile(File file, String error);
    }

    private static File localFile(MessageObject message) {
        String attach = message.messageOwner.attachPath;
        if (attach != null && !attach.isEmpty()) {
            File file = new File(attach);
            if (file.exists()) return file;
        }
        File file = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        return file != null && file.exists() ? file : null;
    }

    /** Calls back on the main thread with the voice file, downloading it first if it is not on the phone. */
    private static void withFile(MessageObject message, FileCallback callback) {
        File local = localFile(message);
        if (local != null) {
            callback.onFile(local, null);
            return;
        }
        TLRPC.Document document = message.getDocument();
        if (document == null) {
            callback.onFile(null, "AUDIO_DOWNLOAD");
            return;
        }
        final int account = message.currentAccount;
        final String fileName = FileLoader.getAttachFileName(document);
        final NotificationCenter center = NotificationCenter.getInstance(account);
        final boolean[] finished = new boolean[1];
        final NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        final Runnable[] timeout = new Runnable[1];
        observer[0] = (id, acc, args) -> {
            if (finished[0] || args == null || args.length == 0 || !fileName.equals(args[0])) return;
            if (id != NotificationCenter.fileLoaded && id != NotificationCenter.fileLoadFailed) return;
            finished[0] = true;
            center.removeObserver(observer[0], NotificationCenter.fileLoaded);
            center.removeObserver(observer[0], NotificationCenter.fileLoadFailed);
            AndroidUtilities.cancelRunOnUIThread(timeout[0]);
            File file = id == NotificationCenter.fileLoaded ? localFile(message) : null;
            callback.onFile(file, file == null ? "AUDIO_DOWNLOAD" : null);
        };
        timeout[0] = () -> {
            if (finished[0]) return;
            finished[0] = true;
            center.removeObserver(observer[0], NotificationCenter.fileLoaded);
            center.removeObserver(observer[0], NotificationCenter.fileLoadFailed);
            callback.onFile(null, "AUDIO_DOWNLOAD");
        };
        center.addObserver(observer[0], NotificationCenter.fileLoaded);
        center.addObserver(observer[0], NotificationCenter.fileLoadFailed);
        AndroidUtilities.runOnUIThread(timeout[0], DOWNLOAD_TIMEOUT_MS);
        FileLoader.getInstance(account).loadFile(document, message, FileLoader.PRIORITY_NORMAL, 0);
    }

    private static void decodeAndRecognize(MessageObject message, File file, Callback callback, long start) {
        short[] pcm = null;
        try {
            if (MediaController.isOpusFile(file.getAbsolutePath()) != 1) {
                finish(message, callback, start, 0, 0, null, "AUDIO_UNSUPPORTED");
                return;
            }
            pcm = MediaController.decodeOpusTo16k(file.getAbsolutePath());
        } catch (Throwable t) {
            pcm = null;
        }
        if (pcm == null || pcm.length == 0) {
            finish(message, callback, start, 0, 0, null, "AUDIO_DECODE");
            return;
        }
        if (pcm.length > (long) TranscriptionRouting.MAX_AUDIO_SECONDS * SAMPLE_RATE) {
            finish(message, callback, start, pcm.length * 1000L / SAMPLE_RATE, 0, null, "AUDIO_TOO_LONG");
            return;
        }
        final float[] samples = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) samples[i] = pcm[i] / 32768f;
        final long audioMs = pcm.length * 1000L / SAMPLE_RATE;
        final List<int[]> chunks = AudioChunker.split(samples, SAMPLE_RATE);
        final List<String> texts = new ArrayList<>();
        AndroidUtilities.runOnUIThread(() -> recognizeChunk(message, samples, chunks, 0, texts, callback, start, audioMs));
    }

    /** Runs the pieces one after the other; the engine delivers each result on the main thread. */
    private static void recognizeChunk(MessageObject message, float[] samples, List<int[]> chunks, int index, List<String> texts,
                                       Callback callback, long start, long audioMs) {
        if (index >= chunks.size()) {
            finish(message, callback, start, audioMs, chunks.size(), AudioChunker.join(texts), null);
            return;
        }
        int[] range = chunks.get(index);
        float[] piece = new float[range[1] - range[0]];
        System.arraycopy(samples, range[0], piece, 0, piece.length);
        ParakeetEngine.getInstance().transcribeSamples(piece, SAMPLE_RATE, null, result -> {
            if (!result.ok()) {
                finish(message, callback, start, audioMs, chunks.size(), null, result.error == null ? "ENGINE" : result.error);
                return;
            }
            texts.add(result.text);
            recognizeChunk(message, samples, chunks, index + 1, texts, callback, start, audioMs);
        });
    }

    private static void finish(MessageObject message, Callback callback, long start, long audioMs, int chunks, String text, String error) {
        long total = SystemClock.elapsedRealtime() - start;
        DiagnosticsLog.Event.Builder event = DiagnosticsLog.event("speech", "voice_note");
        if (error == null) event.ok(); else event.fail(error);
        TgPlusDiagnostics.log(event.duration(total).num("audio_ms", audioMs).num("chunks", chunks)
            .num("chars", text == null ? 0 : text.length()).build());
        AndroidUtilities.runOnUIThread(() -> callback.onDone(text, error));
    }
}
