package org.telegram.messenger.tgplus;

import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessageObject;

import java.util.HashMap;
import java.util.List;

/**
 * UI speed measurements for Telegram Plus diagnostics. Records only timings and counts
 * (see {@link UiSpeedSummary} for the event layout), never message content.
 *
 * Every public entry point swallows its own errors: a measurement problem must never break the chat.
 * Work done on the main thread is a clock read, a counter, or one frame callback.
 */
public final class TgPlusUiSpeed {

    /** Scroll sessions shorter than this many moving frames are ignored. */
    private static final int MIN_SCROLL_FRAMES = 8;
    private static final int FLUSH_SESSIONS = 8;
    private static final int FLUSH_FRAMES = 600;
    private static final int MAX_PENDING_TRANSLATIONS = 50;

    private static boolean firstChatOpened;
    private static final HashMap<String, TranslateTiming> translateTimings = new HashMap<>();

    private TgPlusUiSpeed() {}

    public static boolean isEnabled() {
        return TgPlusSettings.isUiSpeedEnabled();
    }

    // Translate: tap to visible.

    private static final class TranslateTiming {
        long tapAt, resultAt;
        int chars;
    }

    public static synchronized void translateTapped(String key) {
        try {
            if (key == null || !isEnabled()) return;
            if (translateTimings.size() >= MAX_PENDING_TRANSLATIONS) translateTimings.clear();
            TranslateTiming t = new TranslateTiming();
            t.tapAt = SystemClock.elapsedRealtime();
            translateTimings.put(key, t);
        } catch (Throwable ignore) {
        }
    }

    /** Gemini answered; the translation is about to be applied to the chat. */
    public static synchronized void translateResult(String key, int chars) {
        TranslateTiming t = key == null ? null : translateTimings.get(key);
        if (t != null) {
            t.resultAt = SystemClock.elapsedRealtime();
            t.chars = chars;
        }
    }

    public static synchronized void translateEnded(String key) {
        if (key != null) translateTimings.remove(key);
    }

    /**
     * The chat has handled the "message translated" notification, started at {@code handlerStartedAt}.
     * Waits for two frames: the first draws the change, the second starts when it is on screen.
     */
    public static void translationApplied(String key, long handlerStartedAt) {
        try {
            final TranslateTiming t;
            synchronized (TgPlusUiSpeed.class) {
                t = key == null ? null : translateTimings.get(key);
                // resultAt is 0 for the "Translating…" placeholder; keep waiting for the real result.
                if (t == null || t.resultAt == 0) return;
                translateTimings.remove(key);
            }
            final long applied = SystemClock.elapsedRealtime();
            final long net = t.resultAt - t.tapAt;
            final long wait = Math.max(0, handlerStartedAt - t.resultAt);
            final long apply = Math.max(0, applied - handlerStartedAt);
            final Choreographer choreographer = Choreographer.getInstance();
            choreographer.postFrameCallback(first -> {
                final long frameStart = SystemClock.elapsedRealtime();
                choreographer.postFrameCallback(second -> {
                    long visible = SystemClock.elapsedRealtime();
                    TgPlusDiagnostics.log(DiagnosticsLog.event("ui", "translate_apply").ok()
                        .duration(visible - t.tapAt)
                        .num("net_ms", net)
                        .num("wait_ms", wait)
                        .num("apply_ms", apply)
                        .num("frame_ms", visible - frameStart)
                        .num("chars", t.chars)
                        .build());
                });
            });
        } catch (Throwable ignore) {
        }
    }

    // Chat: open time and scrolling.

    public interface TranslatedProbe {
        /** True if any message currently on screen shows a translation. */
        boolean anyTranslatedVisible();
    }

    /** One per chat screen. Create it when the chat screen is created. */
    public static final class Chat {
        private final long created = SystemClock.elapsedRealtime();
        private boolean done;

        /**
         * Call whenever a message cell is bound; only the first call does anything. The time to the
         * following frame is the time until the chat showed its first messages.
         */
        public void messageBound(List<MessageObject> messages) {
            if (done) return;
            done = true;
            try {
                if (!isEnabled()) return;
                final long bound = SystemClock.elapsedRealtime();
                final boolean first;
                synchronized (TgPlusUiSpeed.class) {
                    first = !firstChatOpened;
                    firstChatOpened = true;
                }
                Choreographer.getInstance().postFrameCallback(frame -> {
                    try {
                        long shown = SystemClock.elapsedRealtime();
                        int translated = 0;
                        for (int i = 0, n = messages.size(); i < n; i++) {
                            MessageObject m = messages.get(i);
                            if (m != null && m.tgplusInlineApplied != null) translated++;
                        }
                        TgPlusDiagnostics.log(DiagnosticsLog.event("ui", "chat_open").ok()
                            .duration(shown - created)
                            .num("load_ms", bound - created)
                            .num("msgs", messages.size())
                            .num("translated", translated)
                            .flag("first", first)
                            .build());
                    } catch (Throwable ignore) {
                    }
                });
            } catch (Throwable ignore) {
            }
        }

        public void attachScroll(RecyclerView list, TranslatedProbe probe) {
            try {
                if (list == null || probe == null || !isEnabled()) return;
                new Scroll(list, probe).attach();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Counts frames while the chat list moves and how many took longer than
     * {@link UiSpeedSummary#SLOW_FRAME_MS}. This sees stalls of the main (UI) thread, which is where
     * message layout work happens; it does not see GPU time.
     */
    private static final class Scroll extends RecyclerView.OnScrollListener implements Choreographer.FrameCallback, View.OnAttachStateChangeListener {
        private final RecyclerView list;
        private final TranslatedProbe probe;

        private boolean active, moved, sawTranslated;
        private long lastFrameNanos, startedAt, worst;
        private int frames, slow;

        // Totals since the last flush, [0] = no translation on screen, [1] = a translation on screen.
        private final long[] totalFrames = new long[2], totalSlow = new long[2], totalWorst = new long[2],
            totalSessions = new long[2], totalDuration = new long[2];

        Scroll(RecyclerView list, TranslatedProbe probe) {
            this.list = list;
            this.probe = probe;
        }

        void attach() {
            list.addOnScrollListener(this);
            list.addOnAttachStateChangeListener(this);
        }

        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
            try {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    end();
                } else {
                    begin();
                }
            } catch (Throwable ignore) {
            }
        }

        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (dy != 0) moved = true;
        }

        private void begin() {
            if (active) return;
            active = true;
            moved = false;
            lastFrameNanos = 0;
            frames = slow = 0;
            worst = 0;
            startedAt = SystemClock.elapsedRealtime();
            sawTranslated = probe.anyTranslatedVisible();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!active) return;
            try {
                // Count a frame only if the list actually moved since the previous one.
                if (lastFrameNanos != 0 && moved) {
                    long ms = (frameTimeNanos - lastFrameNanos) / 1_000_000L;
                    frames++;
                    if (ms > UiSpeedSummary.SLOW_FRAME_MS) slow++;
                    if (ms > worst) worst = ms;
                }
                moved = false;
                lastFrameNanos = frameTimeNanos;
            } catch (Throwable ignore) {
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void end() {
            if (!active) return;
            active = false;
            Choreographer.getInstance().removeFrameCallback(this);
            if (frames >= MIN_SCROLL_FRAMES) {
                int i = sawTranslated || probe.anyTranslatedVisible() ? 1 : 0;
                totalFrames[i] += frames;
                totalSlow[i] += slow;
                totalWorst[i] = Math.max(totalWorst[i], worst);
                totalSessions[i]++;
                totalDuration[i] += SystemClock.elapsedRealtime() - startedAt;
                if (totalSessions[i] >= FLUSH_SESSIONS || totalFrames[i] >= FLUSH_FRAMES) flush();
            }
        }

        /** One event per kind of scrolling, so flicking through a chat does not fill the log. */
        private void flush() {
            for (int i = 0; i < 2; i++) {
                if (totalSessions[i] == 0) continue;
                TgPlusDiagnostics.log(DiagnosticsLog.event("ui", "scroll").ok()
                    .duration(totalDuration[i])
                    .flag("tr", i == 1)
                    .num("sessions", totalSessions[i])
                    .num("frames", totalFrames[i])
                    .num("slow", totalSlow[i])
                    .num("worst_ms", totalWorst[i])
                    .build());
                totalFrames[i] = totalSlow[i] = totalWorst[i] = totalSessions[i] = totalDuration[i] = 0;
            }
        }

        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            try {
                end();
                flush();
            } catch (Throwable ignore) {
            }
        }
    }
}
