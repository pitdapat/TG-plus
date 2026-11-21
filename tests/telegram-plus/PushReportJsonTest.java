import org.telegram.messenger.tgplus.PushReportJson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PushReportJsonTest {
    static int checks;

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        // Escaping and value types.
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", "a \"quoted\" \\ path\nline\ttab\u0001");
        map.put("n", 42L);
        map.put("ok", true);
        map.put("none", null);
        map.put("empty", new LinkedHashMap<>());
        map.put("list", Arrays.asList(1, "x"));
        map.put("nolist", new ArrayList<>());
        String json = PushReportJson.toJson(map);
        check(json.contains("\"text\": \"a \\\"quoted\\\" \\\\ path\\nline\\ttab\\u0001\""), "escaping: " + json);
        check(json.contains("\"n\": 42,"), "number");
        check(json.contains("\"ok\": true,"), "boolean");
        check(json.contains("\"none\": null,"), "null");
        check(json.contains("\"empty\": {},"), "empty map");
        check(json.contains("\"nolist\": []"), "empty list");
        check(json.trim().startsWith("{") && json.trim().endsWith("}"), "object");
        check(json.indexOf("\"text\"") < json.indexOf("\"n\"") && json.indexOf("\"n\"") < json.indexOf("\"list\""), "insertion order kept");
        // Balanced brackets outside strings.
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') inString = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            check(depth >= 0, "bracket balance at " + i);
        }
        check(depth == 0 && !inString, "balanced JSON");

        // Event parsing: newest first, malformed lines tolerated.
        List<Map<String, Object>> events = PushReportJson.parseEvents(new String[]{
                "1000|PUSH_RECEIVED|priority=1 original=1",
                "",
                "2000|NOTIFY|skipped: duplicate",
                "garbage",
                "3000|CALLBACK_DONE|"
        });
        check(events.size() == 4, "events parsed: " + events.size());
        check("CALLBACK_DONE".equals(events.get(0).get("code")) && "".equals(events.get(0).get("detail")), "newest first");
        check(Long.valueOf(3000).equals(events.get(0).get("ms")), "epoch kept");
        check("garbage".equals(events.get(1).get("time")) && Long.valueOf(-1).equals(events.get(1).get("ms")), "malformed line kept");
        check("skipped: duplicate".equals(events.get(2).get("detail")), "detail with colon kept");
        check("PUSH_RECEIVED".equals(events.get(3).get("code")), "oldest last");
        check(PushReportJson.parseEvents(null).isEmpty(), "null events");
        check(PushReportJson.isoTime(0) == null, "no time for 0");
        check(PushReportJson.isoTime(1795245200000L).startsWith("20"), "iso time");

        System.out.println(checks + " push report JSON checks passed");
    }
}
