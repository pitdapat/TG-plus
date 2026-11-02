package org.telegram.messenger.tgplus;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the two Telegram Plus diagnostics reports from sections and the event log.
 *
 * The short report fits in a chat message (at most {@link #SHORT_LIMIT} characters);
 * the full report has no limit and contains every stored event. Pure Java for tests.
 */
public final class DiagnosticsReport {

    public static final int SHORT_LIMIT = 4000;
    public static final int SHORT_EVENTS = 20;

    private DiagnosticsReport() {}

    public static final class Section {
        public final String title;
        public final List<String> lines;
        public final boolean inShortReport;

        public Section(String title, List<String> lines, boolean inShortReport) {
            this.title = title;
            this.lines = lines == null ? new ArrayList<>() : lines;
            this.inShortReport = inShortReport;
        }
    }

    public static String build(boolean full, String title, List<Section> sections, List<DiagnosticsLog.Event> events, TimeZone zone, String shortFooter) {
        if (events == null) events = new ArrayList<>();
        if (sections == null) sections = new ArrayList<>();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        format.setTimeZone(zone == null ? TimeZone.getDefault() : zone);

        StringBuilder head = new StringBuilder();
        head.append(title).append(full ? " (full report)" : " (short report)").append('\n');
        head.append("Generated: ").append(format.format(new Date())).append('\n');
        for (Section section : sections) {
            if (!full && !section.inShortReport) continue;
            appendSection(head, section.title, section.lines);
        }
        appendSection(head, "Feature summary", summary(events, format));

        if (full) {
            ArrayList<String> lines = new ArrayList<>();
            for (DiagnosticsLog.Event event : events) lines.add(formatEvent(event, format));
            if (lines.isEmpty()) lines.add("No events recorded.");
            appendSection(head, "All events (" + events.size() + ", oldest first)", lines);
            return head.toString();
        }

        String footer = shortFooter == null ? "" : "\n" + shortFooter + "\n";
        int from = Math.max(0, events.size() - SHORT_EVENTS);
        List<DiagnosticsLog.Event> recent = events.subList(from, events.size());
        // Drop the oldest of the recent events until the report fits.
        for (int skip = 0; skip <= recent.size(); skip++) {
            ArrayList<String> lines = new ArrayList<>();
            for (int i = skip; i < recent.size(); i++) lines.add(formatEvent(recent.get(i), format));
            if (lines.isEmpty()) lines.add(events.isEmpty() ? "No events recorded." : "Omitted to fit; see the full report.");
            StringBuilder out = new StringBuilder(head);
            appendSection(out, "Recent events (" + (recent.size() - skip) + " of " + events.size() + ", oldest first)", lines);
            out.append(footer);
            if (out.length() <= SHORT_LIMIT) return out.toString();
        }
        // Sections alone are too long: cut them, keep the footer.
        String cut = head.toString();
        int room = Math.max(0, SHORT_LIMIT - footer.length() - 32);
        if (cut.length() > room) cut = cut.substring(0, room);
        return cut + "\n[truncated]\n" + footer;
    }

    private static void appendSection(StringBuilder out, String title, List<String> lines) {
        out.append('\n').append("== ").append(title).append(" ==").append('\n');
        for (String line : lines) out.append(line).append('\n');
    }

    static List<String> summary(List<DiagnosticsLog.Event> events, SimpleDateFormat format) {
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        LinkedHashMap<String, DiagnosticsLog.Event> lastError = new LinkedHashMap<>();
        for (DiagnosticsLog.Event event : events) {
            int[] c = counts.get(event.feature);
            if (c == null) counts.put(event.feature, c = new int[2]);
            if ("ok".equals(event.outcome)) c[0]++;
            if (event.failed()) {
                c[1]++;
                lastError.put(event.feature, event);
            }
        }
        ArrayList<String> lines = new ArrayList<>();
        if (counts.isEmpty()) {
            lines.add("No events recorded.");
            return lines;
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            StringBuilder line = new StringBuilder();
            line.append(e.getKey()).append(": ").append(e.getValue()[0]).append(" ok, ").append(e.getValue()[1]).append(" failed");
            DiagnosticsLog.Event err = lastError.get(e.getKey());
            if (err != null) {
                line.append("; last error ").append(err.error).append(" at ").append(format.format(new Date(err.time)));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    public static String formatEvent(DiagnosticsLog.Event event, SimpleDateFormat format) {
        StringBuilder line = new StringBuilder();
        line.append(format.format(new Date(event.time))).append(' ')
            .append(event.feature).append('.').append(event.action).append(' ').append(event.outcome);
        if (event.durationMs >= 0) line.append(' ').append(event.durationMs).append("ms");
        for (Map.Entry<String, String> e : event.fields.entrySet()) {
            line.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        if (event.error != null) line.append(" error=").append(event.error);
        return line.toString();
    }
}
