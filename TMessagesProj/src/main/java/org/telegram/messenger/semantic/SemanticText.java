package org.telegram.messenger.semantic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

/** Pure functions shared by the index and regression tests. */
public final class SemanticText {
    private SemanticText() {}
    public static ArrayList<String> chunks(String text) {
        ArrayList<String> result = new ArrayList<>();
        String normalized = text.replaceAll("\\s+", " ").trim();
        int start = 0;
        while (start < normalized.length()) {
            int count = Math.min(150, normalized.codePointCount(start, normalized.length()));
            int end = normalized.offsetByCodePoints(start, count);
            result.add(normalized.substring(start, end));
            if (end == normalized.length()) break;
            start = normalized.offsetByCodePoints(start, Math.max(1, count - 20));
        }
        return result;
    }
    public static String fingerprint(String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    public static float[] normalize(float[] original) {
        if (original.length < 256) throw new IllegalArgumentException("Unexpected embedding size");
        float[] vector = new float[256]; double sum = 0;
        for (int i = 0; i < 256; i++) {
            if (!Float.isFinite(original[i])) throw new IllegalArgumentException("Invalid embedding");
            vector[i] = original[i]; sum += (double) vector[i] * vector[i];
        }
        if (sum <= 0) throw new IllegalArgumentException("Empty embedding");
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }
    public static byte[] encode(float[] vector) {
        ByteBuffer b = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) b.putFloat(f);
        return b.array();
    }
    public static float[] decode(byte[] bytes) {
        if (bytes.length != 1024) throw new IllegalArgumentException("Invalid vector record");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[256]; for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
        return v;
    }
    public static double cosine(float[] a, float[] b) {
        double result = 0; for (int i = 0; i < 256; i++) result += (double) a[i] * b[i]; return result;
    }
    public static double keyword(String text, String query) {
        String source = text.toLowerCase(Locale.ROOT), q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return 0;
        if (source.contains(q)) return 2;
        String[] words = q.split("\\s+"); int hits = 0;
        for (String word : words) if (!word.isEmpty() && source.contains(word)) hits++;
        return hits / (double) words.length;
    }
}
