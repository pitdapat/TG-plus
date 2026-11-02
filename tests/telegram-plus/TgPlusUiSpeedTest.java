import org.telegram.messenger.tgplus.DiagnosticsLog;
import org.telegram.messenger.tgplus.UiSpeedSummary;

import java.util.ArrayList;
import java.util.List;

public class TgPlusUiSpeedTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static boolean has(List<String> lines, String text) {
        for (String line : lines) if (line.contains(text)) return true;
        return false;
    }

    static String all(List<String> lines) {
        return String.join("\n", lines);
    }

    static DiagnosticsLog.Event open(long total, int translated, boolean first) {
        return DiagnosticsLog.event("ui", "chat_open").ok().duration(total).num("load_ms", total / 2).num("msgs", 30)
            .num("translated", translated).flag("first", first).build();
    }

    static DiagnosticsLog.Event translate(long total, long net, long wait, long apply, long frame) {
        return DiagnosticsLog.event("ui", "translate_apply").ok().duration(total).num("net_ms", net).num("wait_ms", wait)
            .num("apply_ms", apply).num("frame_ms", frame).num("chars", 40).build();
    }

    static DiagnosticsLog.Event scroll(boolean translated, int frames, int slow, long worst) {
        return DiagnosticsLog.event("ui", "scroll").ok().duration(frames * 10L).flag("tr", translated).num("sessions", 1)
            .num("frames", frames).num("slow", slow).num("worst_ms", worst).build();
    }

    public static void main(String[] args) {
        int checks = 0;

        // No data: one hint line, no crash on null or unrelated events.
        List<String> none = UiSpeedSummary.lines(new ArrayList<>());
        check(none.size() == 1 && none.get(0).startsWith("No UI speed data yet"), "empty hint: " + none);
        check(UiSpeedSummary.lines(null).size() == 1, "null events");
        ArrayList<DiagnosticsLog.Event> unrelated = new ArrayList<>();
        unrelated.add(DiagnosticsLog.event("translation", "request").ok().build());
        check(UiSpeedSummary.lines(unrelated).get(0).startsWith("No UI speed data yet"), "unrelated events ignored");
        checks++;

        // Opening a chat: medians for each group, the difference, and first opens kept apart.
        ArrayList<DiagnosticsLog.Event> events = new ArrayList<>();
        for (long t : new long[]{300, 340, 320}) events.add(open(t, 0, false));
        for (long t : new long[]{400, 360, 380}) events.add(open(t, 5, false));
        events.add(open(900, 0, true));
        events.add(open(800, 4, true));
        List<String> lines = UiSpeedSummary.lines(events);
        String text = all(lines);
        check(has(lines, "without translations n=3 median 320 ms, max 340 ms"), "without stat: " + text);
        check(has(lines, "with translations n=3 median 380 ms, max 400 ms"), "with stat: " + text);
        check(has(lines, "[+60 ms, +19% with translations]"), "difference: " + text);
        check(has(lines, "First chat after app start") && has(lines, "n=2 median 850 ms"), "first opens: " + text);
        check(!text.contains("few samples"), "enough samples");
        checks++;

        // Few samples are flagged, and a faster result is shown with a minus sign.
        ArrayList<DiagnosticsLog.Event> few = new ArrayList<>();
        few.add(open(400, 0, false));
        few.add(open(300, 2, false));
        List<String> fewLines = UiSpeedSummary.lines(few);
        check(has(fewLines, "[-100 ms, -25% with translations, few samples]"), "few samples: " + all(fewLines));
        checks++;

        // One group missing.
        ArrayList<DiagnosticsLog.Event> onlyWithout = new ArrayList<>();
        onlyWithout.add(open(300, 0, false));
        List<String> ow = UiSpeedSummary.lines(onlyWithout);
        check(has(ow, "with translations none yet") && !all(ow).contains("["), "one group missing: " + all(ow));
        checks++;

        // Translate tap to visible, with the part that is not Gemini.
        ArrayList<DiagnosticsLog.Event> tr = new ArrayList<>();
        tr.add(translate(1000, 900, 0, 10, 20));
        tr.add(translate(1200, 1100, 0, 12, 18));
        tr.add(translate(1400, 1300, 20, 14, 16));
        List<String> trLines = UiSpeedSummary.lines(tr);
        check(has(trLines, "Translate tap to visible: n=3 median 1200 ms (Gemini 1100, save/queue 0, layout 12, frame 18); excluding Gemini 100 ms"),
            "translate line: " + all(trLines));
        checks++;

        // Scrolling: sums per group, percentage and worst frame.
        ArrayList<DiagnosticsLog.Event> sc = new ArrayList<>();
        sc.add(scroll(false, 100, 2, 30));
        sc.add(scroll(false, 100, 4, 55));
        sc.add(scroll(true, 50, 10, 90));
        List<String> scLines = UiSpeedSummary.lines(sc);
        check(has(scLines, "without translations 3.0% slow of 200 frames, worst 55 ms"), "scroll without: " + all(scLines));
        check(has(scLines, "with translations 20.0% slow of 50 frames, worst 90 ms"), "scroll with: " + all(scLines));
        checks++;

        // Failed events and malformed numbers are ignored, never crash.
        ArrayList<DiagnosticsLog.Event> bad = new ArrayList<>();
        bad.add(DiagnosticsLog.event("ui", "chat_open").fail("BOOM").duration(5000).build());
        bad.add(DiagnosticsLog.event("ui", "translate_apply").ok().duration(100).tag("net_ms", "abc").build());
        bad.add(DiagnosticsLog.event("ui", "scroll").ok().tag("frames", "x").build());
        List<String> badLines = UiSpeedSummary.lines(bad);
        check(has(badLines, "Translate tap to visible: n=1 median 100 ms") && has(badLines, "none yet"), "bad input: " + all(badLines));
        checks++;

        // Every line is short enough for the short report.
        for (String line : UiSpeedSummary.lines(events)) check(line.length() < 200, "line too long: " + line.length());
        checks++;

        System.out.println(checks + " Telegram Plus UI speed checks passed");
    }
}
