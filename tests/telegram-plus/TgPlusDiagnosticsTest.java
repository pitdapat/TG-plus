import org.telegram.messenger.tgplus.DiagnosticsLog;
import org.telegram.messenger.tgplus.DiagnosticsReport;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

public class TgPlusDiagnosticsTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static String read(File file) throws Exception {
        return file.exists() ? new String(Files.readAllBytes(file.toPath()), "UTF-8") : "";
    }

    public static void main(String[] args) throws Exception {
        int checks = 0;
        File dir = Files.createTempDirectory("tgplus-diag").toFile();
        File file = new File(dir, "events.log");
        DiagnosticsLog log = new DiagnosticsLog(file);

        // Free-form text never reaches storage: tags with spaces or separators become "other",
        // errors become OTHER_ERROR, and names/keys outside the allowed set are rejected.
        String secret = "AIzaSyDUMMY-key value";
        String message = "Hello, please translate this private message";
        log.append(DiagnosticsLog.event("translation", "request").fail(message)
            .tag("provider", "gemini").tag("target", message).tag("key", secret)
            .num("http", 400).duration(120).at(1000).build());
        String stored = read(file);
        check(!stored.contains("Hello") && !stored.contains("AIza") && !stored.contains("private"), "content stored: " + stored);
        check(stored.contains("target=other") && stored.contains("key=other") && stored.contains("OTHER_ERROR"), "sanitized values missing: " + stored);
        checks++;

        boolean rejected = false;
        try { DiagnosticsLog.event("Translation Request", "x"); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "unsafe feature name accepted");
        rejected = false;
        try { DiagnosticsLog.event("translation", "request").num("bad key", 1); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "unsafe key accepted");
        checks++;

        // Round trip of typed values.
        log.append(DiagnosticsLog.event("translation", "request").ok().tag("model", "gemini-3.5-flash-lite")
            .tag("target", "th").num("in_chars", 42).flag("cached", false).duration(812).at(2000).build());
        List<DiagnosticsLog.Event> events = log.readAll();
        check(events.size() == 2, "expected 2 events, got " + events.size());
        DiagnosticsLog.Event e = events.get(1);
        check("ok".equals(e.outcome) && e.durationMs == 812 && "gemini-3.5-flash-lite".equals(e.fields.get("model"))
            && "42".equals(e.fields.get("in_chars")) && "no".equals(e.fields.get("cached")) && e.error == null, "round trip failed");
        checks++;

        // Malformed lines (for example a partially written last line) are skipped.
        Files.write(file.toPath(), (read(file) + "garbage|line\n").getBytes("UTF-8"));
        check(log.readAll().size() == 2, "malformed line not skipped");
        checks++;

        // The log is capped by event count.
        log.clear();
        for (int i = 0; i < DiagnosticsLog.MAX_EVENTS + 10; i++) {
            log.append(DiagnosticsLog.event("update", "check").ok().num("i", i).at(10_000 + i).build());
        }
        events = log.readAll();
        check(events.size() <= DiagnosticsLog.MAX_EVENTS && events.size() >= 1000, "cap not applied: " + events.size());
        check(("" + (DiagnosticsLog.MAX_EVENTS + 9)).equals(events.get(events.size() - 1).fields.get("i")), "newest event lost");
        checks++;

        // A fresh instance over an existing file keeps counting from the stored lines.
        DiagnosticsLog reopened = new DiagnosticsLog(file);
        for (int i = 0; i < DiagnosticsLog.MAX_EVENTS; i++) {
            reopened.append(DiagnosticsLog.event("update", "check").ok().at(20_000 + i).build());
        }
        check(reopened.readAll().size() <= DiagnosticsLog.MAX_EVENTS, "cap not applied after reopen");
        checks++;

        // Short report stays under the limit and points to the full report; the full report keeps every event.
        log.clear();
        for (int i = 0; i < 300; i++) {
            DiagnosticsLog.Event.Builder b = DiagnosticsLog.event(i % 3 == 0 ? "translation" : "update", "request").duration(i).at(1_700_000_000_000L + i * 1000L)
                .tag("provider", "gemini").num("http", i % 5 == 0 ? 429 : 200).num("in_chars", i);
            log.append(i % 5 == 0 ? b.fail("RESOURCE_EXHAUSTED").build() : b.ok().build());
        }
        events = log.readAll();
        List<String> env = new ArrayList<>();
        for (int i = 0; i < 20; i++) env.add("Environment line " + i + ": value");
        List<DiagnosticsReport.Section> sections = Arrays.asList(
            new DiagnosticsReport.Section("Environment", env, true),
            new DiagnosticsReport.Section("Model files", Arrays.asList("only in full report"), false));
        String footer = "Full report: Settings > Telegram Plus > Diagnostics > Download full report.";
        String shortReport = DiagnosticsReport.build(false, "Telegram Plus diagnostics", sections, events, TimeZone.getTimeZone("UTC"), footer);
        check(shortReport.length() <= DiagnosticsReport.SHORT_LIMIT, "short report too long: " + shortReport.length());
        check(shortReport.contains(footer) && shortReport.contains("Environment line 19") && !shortReport.contains("only in full report"), "short report content wrong");
        check(shortReport.contains("translation: ") && shortReport.contains("last error RESOURCE_EXHAUSTED"), "feature summary missing");
        checks++;

        String fullReport = DiagnosticsReport.build(true, "Telegram Plus diagnostics", sections, events, TimeZone.getTimeZone("UTC"), footer);
        check(fullReport.contains("only in full report") && fullReport.contains("All events (300"), "full report missing sections");
        int lines = 0;
        for (String line : fullReport.split("\n")) if (line.contains(".request ")) lines++;
        check(lines == 300, "full report should list all 300 events, got " + lines);
        checks++;

        // Huge sections are truncated but the footer survives.
        List<String> huge = new ArrayList<>();
        for (int i = 0; i < 500; i++) huge.add("Long environment line number " + i + " with padding padding padding");
        String truncated = DiagnosticsReport.build(false, "T", Arrays.asList(new DiagnosticsReport.Section("Env", huge, true)), events, TimeZone.getTimeZone("UTC"), footer);
        check(truncated.length() <= DiagnosticsReport.SHORT_LIMIT && truncated.contains(footer) && truncated.contains("[truncated]"), "truncation failed");
        checks++;

        // Empty log.
        log.clear();
        String empty = DiagnosticsReport.build(false, "T", new ArrayList<>(), log.readAll(), TimeZone.getTimeZone("UTC"), footer);
        check(empty.contains("No events recorded."), "empty log text missing");
        checks++;

        System.out.println(checks + " Telegram Plus diagnostics checks passed");
    }
}
