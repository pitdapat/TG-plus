package org.telegram.messenger.tgplus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Turns recorded "ui" diagnostics events into the lines of the UI speed report:
 * how long a chat takes to open, how long Translate takes to show up, and how smoothly the
 * chat list scrolls, each with and without translations. Pure Java for tests.
 *
 * Events (feature "ui"):
 * <ul>
 * <li>chat_open: duration = time from creating the chat to its first frame with messages;
 *     load_ms, msgs, translated (messages showing a translation), first (yes for the first chat
 *     opened since the app started).</li>
 * <li>translate_apply: duration = tap to the frame that shows the translation; net_ms (waiting for
 *     Gemini), wait_ms (saving the translation and any queueing before it was applied), apply_ms (rebuilding the message layout),
 *     frame_ms (the frame that drew it), chars.</li>
 * <li>scroll: tr (yes if a translation was on screen), sessions, frames, slow, worst_ms.</li>
 * </ul>
 */
public final class UiSpeedSummary {

    /** A frame that took longer than this is counted as slow (a dropped frame on a 60 Hz screen). */
    public static final int SLOW_FRAME_MS = 24;
    public static final int MIN_SAMPLES = 3;

    private UiSpeedSummary() {}

    public static List<String> lines(List<DiagnosticsLog.Event> events) {
        ArrayList<String> lines = new ArrayList<>();
        ArrayList<DiagnosticsLog.Event> ui = new ArrayList<>();
        int notShown = 0;
        if (events != null) {
            for (DiagnosticsLog.Event e : events) {
                if (e == null || !"ui".equals(e.feature)) continue;
                if ("ok".equals(e.outcome)) {
                    ui.add(e);
                } else if (e.failed() && "translate_apply".equals(e.action)) {
                    // Saved, but no visible message was updated: shows only after reopening the chat.
                    notShown++;
                }
            }
        }
        if (ui.isEmpty() && notShown == 0) {
            lines.add("No UI speed data yet. Open a few chats, translate a message, scroll, then reopen the chat.");
            return lines;
        }
        lines.add(openLine(ui));
        String first = firstOpenLine(ui);
        if (first != null) lines.add(first);
        lines.add(translateLine(ui));
        String delivery = deliveryLine(ui, notShown);
        if (delivery != null) lines.add(delivery);
        lines.add(scrollLine(ui));
        return lines;
    }

    private static String openLine(List<DiagnosticsLog.Event> ui) {
        ArrayList<Long> without = new ArrayList<>(), with = new ArrayList<>();
        for (DiagnosticsLog.Event e : ui) {
            if (!"chat_open".equals(e.action) || e.durationMs < 0 || "yes".equals(e.fields.get("first"))) continue;
            (num(e, "translated") > 0 ? with : without).add(e.durationMs);
        }
        StringBuilder out = new StringBuilder("Open chat (not the first since launch): without translations ")
            .append(openStat(without)).append(" | with translations ").append(openStat(with));
        if (!without.isEmpty() && !with.isEmpty()) {
            long a = median(without), b = median(with), diff = b - a;
            out.append(" [").append(signed(diff)).append(" ms");
            if (a > 0) out.append(", ").append(signed(Math.round(diff * 100.0 / a))).append('%');
            out.append(" with translations");
            if (Math.min(without.size(), with.size()) < MIN_SAMPLES) out.append(", few samples");
            out.append(']');
        }
        return out.toString();
    }

    private static String openStat(List<Long> values) {
        if (values.isEmpty()) return "none yet";
        return "n=" + values.size() + " median " + median(values) + " ms, max " + Collections.max(values) + " ms";
    }

    private static String firstOpenLine(List<DiagnosticsLog.Event> ui) {
        ArrayList<Long> values = new ArrayList<>();
        for (DiagnosticsLog.Event e : ui) {
            if ("chat_open".equals(e.action) && e.durationMs >= 0 && "yes".equals(e.fields.get("first"))) values.add(e.durationMs);
        }
        if (values.isEmpty()) return null;
        return "First chat after app start (app still warming up): n=" + values.size() + " median " + median(values) + " ms";
    }

    private static String translateLine(List<DiagnosticsLog.Event> ui) {
        ArrayList<Long> total = new ArrayList<>(), net = new ArrayList<>(), wait = new ArrayList<>(),
            apply = new ArrayList<>(), frame = new ArrayList<>(), ours = new ArrayList<>();
        for (DiagnosticsLog.Event e : ui) {
            if (!"translate_apply".equals(e.action) || e.durationMs < 0) continue;
            total.add(e.durationMs);
            long n = Math.max(0, num(e, "net_ms"));
            net.add(n);
            wait.add(Math.max(0, num(e, "wait_ms")));
            apply.add(Math.max(0, num(e, "apply_ms")));
            frame.add(Math.max(0, num(e, "frame_ms")));
            ours.add(Math.max(0, e.durationMs - n));
        }
        if (total.isEmpty()) return "Translate tap to visible: no data yet. Translate a message in a chat.";
        return "Translate tap to visible: n=" + total.size() + " median " + median(total) + " ms (Gemini " + median(net)
            + ", save/queue " + median(wait) + ", layout " + median(apply) + ", frame " + median(frame) + "); excluding Gemini "
            + median(ours) + " ms";
    }

    /** Whether translations appeared live. Only shown when something is worth knowing. */
    private static String deliveryLine(List<DiagnosticsLog.Event> ui, int notShown) {
        int shown = 0, animating = 0;
        for (DiagnosticsLog.Event e : ui) {
            if (!"translate_apply".equals(e.action)) continue;
            shown++;
            if ("yes".equals(e.fields.get("anim"))) animating++;
        }
        if (notShown == 0 && animating == 0) return null;
        StringBuilder out = new StringBuilder("Translate delivery: ");
        out.append(shown).append(" shown live");
        if (notShown > 0) out.append(", ").append(notShown).append(" saved but NOT shown until the chat was reopened");
        if (animating > 0) out.append(", ").append(animating).append(" answered while the chat list was animating");
        return out.toString();
    }

    private static String scrollLine(List<DiagnosticsLog.Event> ui) {
        long[] frames = new long[2], slow = new long[2], worst = new long[2];
        for (DiagnosticsLog.Event e : ui) {
            if (!"scroll".equals(e.action)) continue;
            int i = "yes".equals(e.fields.get("tr")) ? 1 : 0;
            frames[i] += Math.max(0, num(e, "frames"));
            slow[i] += Math.max(0, num(e, "slow"));
            worst[i] = Math.max(worst[i], num(e, "worst_ms"));
        }
        return "Scrolling (slow = frame over " + SLOW_FRAME_MS + " ms): without translations " + scrollStat(frames[0], slow[0], worst[0])
            + " | with translations " + scrollStat(frames[1], slow[1], worst[1]);
    }

    private static String scrollStat(long frames, long slow, long worst) {
        if (frames <= 0) return "none yet";
        return String.format(Locale.US, "%.1f%% slow of %d frames, worst %d ms", slow * 100.0 / frames, frames, worst);
    }

    static long median(List<Long> values) {
        if (values.isEmpty()) return 0;
        ArrayList<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static long num(DiagnosticsLog.Event e, String key) {
        String value = e.fields.get(key);
        if (value == null) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String signed(long value) {
        return (value >= 0 ? "+" : "") + value;
    }
}
