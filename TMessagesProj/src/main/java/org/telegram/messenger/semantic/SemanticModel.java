package org.telegram.messenger.semantic;

import android.content.Context;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.EmbeddingEngine;
import com.google.ai.edge.litertlm.EmbeddingEngineConfig;
import com.google.ai.edge.litertlm.EmbeddingOptions;
import com.google.ai.edge.litertlm.InputData;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Shared model lifecycle; every engine/file operation runs on one queue. */
public final class SemanticModel {
    public static final DispatchQueue QUEUE = new DispatchQueue("semantic-search");
    public static final String HASH = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb";
    public static final long SIZE = 164626432;
    private static final String URL_STRING = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm";
    private static final AtomicLong generation = new AtomicLong();
    private static volatile String status = "Model not downloaded";
    private static volatile boolean downloading;
    private static EmbeddingEngine engine;
    public interface Listener { void updated(); }
    private static volatile Listener listener;
    private SemanticModel() {}
    public static void observe(Listener l) { listener = l; }
    public static void notifyChanged() { Listener l = listener; if (l != null) AndroidUtilities.runOnUIThread(l::updated); }
    public static File dir() {
        File d = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "semantic-model");
        if (!d.isDirectory()) d.mkdirs(); return d;
    }
    public static File weights() { return new File(dir(), "embeddinggemma-2-text.litertlm"); }
    public static boolean ready() { return weights().isFile() && weights().length() == SIZE; }
    public static boolean downloading() { return downloading; }
    public static long storageBytes() { return bytes(dir()); }
    private static long bytes(File f) { if(f.isFile())return f.length();long count=0;File[] children=f.listFiles();if(children!=null)for(File c:children)count+=bytes(c);return count; }
    public static String status() { String label=downloading?status:ready()?"Model ready • CPU":status;return label+String.format(Locale.ROOT," • %.1f MB on device",storageBytes()/1000000.0); }
    private static void status(String text) { status = text; notifyChanged(); }
    public static void download() {
        if (downloading || ready()) return;
        downloading = true; long token = generation.incrementAndGet(); status("Starting download…");
        QUEUE.postRunnable(() -> {
            File partial = new File(dir(), "download.partial"); HttpURLConnection connection = null;
            try {
                if (dir().getUsableSpace() < SIZE + 32L * 1024 * 1024) throw new IOException("Not enough free storage");
                connection = (HttpURLConnection) new URL(URL_STRING).openConnection();
                connection.setConnectTimeout(20000); connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() != 200) throw new IOException("Download server returned " + connection.getResponseCode());
                if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) throw new IOException("Unsafe download redirect");
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0; int lastPercent = -1;
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (generation.get() != token) throw new IOException("Download cancelled");
                        count += n; if (count > SIZE) throw new IOException("Unexpected download size");
                        out.write(buffer, 0, n); digest.update(buffer, 0, n);
                        int percent = (int) (count * 100 / SIZE);
                        if (percent != lastPercent) { status("Downloading model: " + percent + "%"); lastPercent = percent; }
                    }
                    out.getFD().sync();
                }
                StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
                if (count != SIZE || !HASH.contentEquals(hash)) throw new IOException("Model verification failed");
                synchronized (SemanticModel.class) {
                    if (generation.get() != token) throw new IOException("Download cancelled");
                    if (!partial.renameTo(weights())) throw new IOException("Cannot activate model");
                }
                status("Model ready • CPU • 165 MB");
            } catch (Exception e) { status(e.getMessage() == null ? "Download failed; retry" : e.getMessage()); }
            finally { partial.delete(); if (connection != null) connection.disconnect(); downloading = false; notifyChanged(); }
        });
    }
    public static void cancelDownload() { synchronized (SemanticModel.class) { generation.incrementAndGet(); } }
    public static void delete() {
        synchronized (SemanticModel.class) { generation.incrementAndGet(); }
        SemanticSearch.cancelAll(); status("Removing model…");
        QUEUE.postRunnable(() -> { close(); removeTree(dir()); status("Model not downloaded"); });
    }
    public static void close() { if (engine != null) { engine.close(); engine = null; } }
    private static void removeTree(File f) { File[] children = f.listFiles(); if (children != null) for (File c : children) removeTree(c); if (!f.delete() && f.exists()) status("Unable to remove all model files"); }
    public static float[] embed(String text) throws Exception {
        if (!ready()) throw new IOException("Download the model first");
        if (engine == null) {
            File cache = new File(dir(), "cpu-cache"); cache.mkdirs();
            EmbeddingEngine next = new EmbeddingEngine(new EmbeddingEngineConfig(weights().getAbsolutePath(), new Backend.CPU(), null, null, cache.getAbsolutePath(), null, 1024, null, null));
            try { next.initialize(); engine=next; } catch(Exception | LinkageError e) { try { next.close(); } catch(Exception ignored) {} throw e; }
        }
        return SemanticText.normalize(engine.computeEmbedding(Collections.singletonList(new InputData.Text(text)), new EmbeddingOptions(true, true, null, null)).getEmbedding());
    }
}
