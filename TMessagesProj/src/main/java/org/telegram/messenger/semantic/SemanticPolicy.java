package org.telegram.messenger.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure charging and bounded-index decisions, shared with regression tests. */
public final class SemanticPolicy {
    public static final int MAX_CHUNKS = 10000;
    private SemanticPolicy() {}
    public static boolean canRun(boolean automatic, boolean chargingOnly, boolean charging) {
        return !automatic || !chargingOnly || charging;
    }
    public static boolean eligible(int id, String text, boolean protectedContent, boolean expires, boolean service) {
        return id>0 && text!=null && !text.trim().isEmpty() && !protectedContent && !expires && !service;
    }
    public static final class Entry {
        public final long dialog;
        public final int mid, date, chunks;
        public Entry(long dialog, int mid, int date, int chunks) {
            this.dialog = dialog; this.mid = mid; this.date = date; this.chunks = chunks;
        }
    }
    /** Null means skip this message; an empty plan means no eviction is needed. */
    public static ArrayList<Entry> victims(int used, int replacing, int incoming, int date, List<Entry> entries) {
        if (incoming < 1 || incoming > MAX_CHUNKS) return null;
        int required = used - replacing + incoming - MAX_CHUNKS;
        ArrayList<Entry> result = new ArrayList<>();
        if (required <= 0) return result;
        ArrayList<Entry> older = new ArrayList<>();
        for (Entry entry : entries) if (entry.date < date) older.add(entry);
        older.sort(Comparator.comparingInt((Entry e) -> e.date).thenComparingLong(e -> e.dialog).thenComparingInt(e -> e.mid));
        int freed = 0;
        for (Entry entry : older) {
            result.add(entry); freed += entry.chunks;
            if (freed >= required) return result;
        }
        return null;
    }
}
