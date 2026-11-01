package org.telegram.messenger.tgplus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounded, append-only event log for Telegram Plus feature diagnostics.
 *
 * Pure Java (no Android types) so it can be tested on a plain JVM. Every value is
 * restricted to short identifiers and numbers, so free-form text such as message
 * content, transcripts, translations or API keys cannot be stored by accident.
 */
public final class DiagnosticsLog {

    public static final int MAX_EVENTS = 2000;
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    /** When a cap is hit, the log is rewritten keeping this many of the newest events. */
    static final int TRIM_TO_EVENTS = 1500;

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,24}");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9._:+-]{1,48}");
    private static final Pattern ERROR = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    public static final String OTHER = "other";
    public static final String OTHER_ERROR = "OTHER_ERROR";

    private final File file;
    private int lineCount = -1;

    public DiagnosticsLog(File file) {
        this.file = file;
    }

    public static String safeName(String value) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Diagnostics names must match [a-z0-9_]{1,24}");
        }
        return value;
    }

    /** Short identifier values only (language codes, model ids, HTTP status names). */
    public static String safeTag(String value) {
        return value != null && TAG.matcher(value).matches() ? value : OTHER;
    }

    public static String safeError(String value) {
        return value != null && ERROR.matcher(value).matches() ? value : OTHER_ERROR;
    }

    public static Event.Builder event(String feature, String action) {
        return new Event.Builder(feature, action);
    }

    public static final class Event {
        public final long time;
        public final String feature, action, outcome;
        public final long durationMs;
        public final Map<String, String> fields;
        public final String error;

        Event(long time, String feature, String action, String outcome, long durationMs, Map<String, String> fields, String error) {
            this.time = time;
            this.feature = feature;
            this.action = action;
            this.outcome = outcome;
            this.durationMs = durationMs;
            this.fields = Collections.unmodifiableMap(fields);
            this.error = error;
        }

        public boolean failed() {
            return "fail".equals(outcome);
        }

        String serialize() {
            StringBuilder out = new StringBuilder();
            out.append(time).append('|').append(feature).append('|').append(action).append('|')
                .append(outcome).append('|').append(durationMs).append('|');
            boolean first = true;
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (!first) out.append(';');
                first = false;
                out.append(e.getKey()).append('=').append(e.getValue());
            }
            out.append('|').append(error == null ? "" : error);
            return out.toString();
        }

        static Event parse(String line) {
            if (line == null) return null;
            String[] parts = line.split("\\|", -1);
            if (parts.length != 7) return null;
            try {
                long time = Long.parseLong(parts[0]);
                long duration = Long.parseLong(parts[4]);
                if (!NAME.matcher(parts[1]).matches() || !NAME.matcher(parts[2]).matches() || !NAME.matcher(parts[3]).matches()) {
                    return null;
                }
                LinkedHashMap<String, String> fields = new LinkedHashMap<>();
                if (!parts[5].isEmpty()) {
                    for (String pair : parts[5].split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq <= 0) return null;
                        String key = pair.substring(0, eq);
                        String value = pair.substring(eq + 1);
                        if (!KEY.matcher(key).matches()) return null;
                        fields.put(key, safeTag(value));
                    }
                }
                String error = parts[6].isEmpty() ? null : safeError(parts[6]);
                return new Event(time, parts[1], parts[2], parts[3], duration, fields, error);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public static final class Builder {
            private final String feature, action;
            private String outcome = "info";
            private long durationMs = -1;
            private long time = -1;
            private String error;
            private final LinkedHashMap<String, String> fields = new LinkedHashMap<>();

            Builder(String feature, String action) {
                this.feature = safeName(feature);
                this.action = safeName(action);
            }

            public Builder ok() {
                outcome = "ok";
                error = null;
                return this;
            }

            public Builder fail(String errorCode) {
                outcome = "fail";
                error = safeError(errorCode);
                return this;
            }

            public Builder outcome(String value) {
                outcome = safeName(value);
                return this;
            }

            public Builder duration(long ms) {
                durationMs = Math.max(-1, ms);
                return this;
            }

            public Builder at(long timeMs) {
                time = timeMs;
                return this;
            }

            public Builder num(String key, long value) {
                fields.put(checkKey(key), Long.toString(value));
                return this;
            }

            public Builder flag(String key, boolean value) {
                fields.put(checkKey(key), value ? "yes" : "no");
                return this;
            }

            public Builder tag(String key, String value) {
                fields.put(checkKey(key), safeTag(value));
                return this;
            }

            private static String checkKey(String key) {
                if (key == null || !KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("Diagnostics keys must match [a-z0-9_]{1,32}");
                }
                return key;
            }

            public Event build() {
                return new Event(time >= 0 ? time : System.currentTimeMillis(), feature, action, outcome, durationMs, new LinkedHashMap<>(fields), error);
            }
        }
    }

    public synchronized void append(Event event) {
        if (event == null || file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (lineCount < 0) lineCount = file.exists() ? readLines().size() : 0;
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                writer.write(event.serialize());
                writer.write('\n');
            }
            lineCount++;
            if (lineCount > MAX_EVENTS || file.length() > MAX_BYTES) {
                trim();
            }
        } catch (IOException ignore) {
            // Diagnostics must never break the feature being diagnosed.
        }
    }

    private void trim() throws IOException {
        List<String> lines = readLines();
        int from = Math.max(0, lines.size() - TRIM_TO_EVENTS);
        List<String> keep = lines.subList(from, lines.size());
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp, false), StandardCharsets.UTF_8)) {
            for (String line : keep) {
                writer.write(line);
                writer.write('\n');
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) throw new IOException("Could not replace diagnostics log");
        }
        lineCount = keep.size();
    }

    private List<String> readLines() throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        if (file == null || !file.exists()) return lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    /** Oldest first. Malformed lines are skipped. */
    public synchronized List<Event> readAll() {
        ArrayList<Event> events = new ArrayList<>();
        try {
            for (String line : readLines()) {
                Event event = Event.parse(line);
                if (event != null) events.add(event);
            }
        } catch (IOException ignore) {
        }
        return events;
    }

    public synchronized void clear() {
        if (file != null) file.delete();
        lineCount = 0;
    }
}
