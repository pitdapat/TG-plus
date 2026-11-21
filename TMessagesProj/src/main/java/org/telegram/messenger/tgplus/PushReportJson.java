package org.telegram.messenger.tgplus;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the shareable notification diagnostics report. Plain Java (no Android types) so it is
 * unit-tested; values are expected to be already sanitized by PushDiagnostics.
 */
public final class PushReportJson {
    public static final String FORMAT = "tgplus-push-report/1";

    private PushReportJson() {}

    /** Converts "epochMillis|CODE|detail" lines (oldest first) to event objects, newest first. */
    public static List<Map<String, Object>> parseEvents(String[] lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (lines == null) return out;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (line == null || line.isEmpty()) continue;
            String[] parts = line.split("\\|", 3);
            Map<String, Object> event = new LinkedHashMap<>();
            long ms = -1;
            try {
                ms = Long.parseLong(parts[0]);
            } catch (NumberFormatException ignore) {
            }
            event.put("time", ms >= 0 ? isoTime(ms) : parts[0]);
            event.put("ms", ms);
            event.put("code", parts.length > 1 ? parts[1] : "");
            event.put("detail", parts.length > 2 ? parts[2] : "");
            out.add(event);
        }
        return out;
    }

    public static String isoTime(long ms) {
        if (ms <= 0) return null;
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date(ms));
    }

    /** Serializes maps, lists, strings, numbers, booleans and nulls as indented JSON. */
    public static String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object value, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                pad(out, indent + 1);
                quote(out, String.valueOf(entry.getKey()));
                out.append(": ");
                write(out, entry.getValue(), indent + 1);
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            pad(out, indent);
            out.append('}');
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(out, indent + 1);
                write(out, list.get(i), indent + 1);
                out.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            pad(out, indent);
            out.append(']');
        } else {
            quote(out, String.valueOf(value));
        }
    }

    private static void pad(StringBuilder out, int indent) {
        for (int i = 0; i < indent; i++) out.append("  ");
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
