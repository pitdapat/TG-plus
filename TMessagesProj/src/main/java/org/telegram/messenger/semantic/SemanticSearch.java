package org.telegram.messenger.semantic;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates policy, persistent local indexing and source-validated results. */
public final class SemanticSearch {
    private static final ConcurrentHashMap<Integer, SemanticSearch> instances = new ConcurrentHashMap<>();
    public final int account; public final long owner;
    private final SharedPreferences prefs;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean busy;
    private volatile boolean yieldIndex;
    private volatile String status = "Choose chats and download the model to begin";
    private volatile SemanticModel.Listener observer;
    private volatile boolean dirty = true;
    public interface Results { void completed(ArrayList<Result> results, String error); }
    public static final class Result {
        public final long dialog; public final int id, date; public final String text;
        Result(SemanticMessages.Message m) { dialog = m.dialog; id = m.id; date = m.date; text = m.text; }
    }
    private SemanticSearch(int slot, long uid) {
        account = slot; owner = uid;
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("semantic-search-" + uid, Context.MODE_PRIVATE);
    }
    public static synchronized SemanticSearch get(int account) {
        long owner = UserConfig.getInstance(account).getClientUserId();
        SemanticSearch old = instances.get(account);
        if (old == null || old.owner != owner) {
            if (old != null) old.logout();
            old = new SemanticSearch(account, owner); instances.put(account, old);
        }
        return old;
    }
    public static void cancelAll() { for (SemanticSearch s : instances.values()) s.cancel(); }
    public static void sourceChanged(int account) {
        SemanticSearch s = instances.get(account); if (s == null) return;
        s.dirty = true; s.cancel();
        AndroidUtilities.runOnUIThread(() -> s.autoIndex(), 2000);
    }
    public static void sourceMessages(int account, ArrayList<TLRPC.Message> messages) {
        SemanticSearch s=instances.get(account);if(s==null||messages==null)return;
        Set<Long> allowed=s.allowed();ArrayList<TLRPC.Message> copy=new ArrayList<>();
        for(TLRPC.Message m:messages)if(m!=null&&allowed.contains(MessageObject.getDialogId(m)))copy.add(m);
        if(copy.isEmpty())return;sourceChanged(account);
        SemanticModel.QUEUE.postRunnable(()->{try(SemanticIndex index=new SemanticIndex(s.owner)){
            for(TLRPC.Message m:copy){if(m==null||m.id<=0)continue;long dialog=MessageObject.getDialogId(m);
                String previous=index.hash(dialog,m.id);
                if(previous!=null&&(m.message==null||!previous.equals(SemanticText.fingerprint(m.message))))index.remove(dialog,m.id);
            }
        }catch(Exception e){FileLog.e(e);}});
    }
    public static void messagesDeleted(int account, long dialog, ArrayList<Integer> mids) {
        SemanticSearch s=instances.get(account); if(s==null)return;
        s.cancel();s.dirty=true;ArrayList<Integer> copy=mids==null?new ArrayList<>():new ArrayList<>(mids);
        SemanticModel.QUEUE.postRunnable(()->{try(SemanticIndex index=new SemanticIndex(s.owner)){
            for(int mid:copy){if(dialog!=0)index.remove(dialog,mid);else index.db.delete("items","mid=?",new String[]{""+mid});}
        }});
    }
    public static void historyDeleted(int account,long dialog,int maxId){
        SemanticSearch s=instances.get(account);if(s==null)return;s.cancel();s.dirty=true;
        SemanticModel.QUEUE.postRunnable(()->{try(SemanticIndex index=new SemanticIndex(s.owner)){
            index.db.delete("items",maxId>0?"dialog=? AND mid<=?":"dialog=?",maxId>0?new String[]{""+dialog,""+maxId}:new String[]{""+dialog});
        }});
    }
    public static void clearAllIndexes(){
        cancelAll();SemanticModel.QUEUE.postRunnable(()->{
            java.io.File[] files=ApplicationLoader.applicationContext.getNoBackupFilesDir().listFiles();
            if(files!=null)for(java.io.File f:files)if(f.getName().startsWith("semantic-index-")&&f.getName().endsWith(".db"))android.database.sqlite.SQLiteDatabase.deleteDatabase(f);
            for(SemanticSearch s:instances.values()){s.dirty=true;s.status("Search index cleared");}
        });
    }
    public static void accountCleared(int account) { SemanticSearch s = instances.remove(account); if (s != null) s.logout(); }
    public static void resumeEnabledAccounts() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (UserConfig.getInstance(a).isClientActivated()) get(a).autoIndex();
    }
    private void logout() {
        cancel(); prefs.edit().clear().apply();
        SemanticModel.QUEUE.postRunnable(() -> android.database.sqlite.SQLiteDatabase.deleteDatabase(SemanticIndex.file(owner)));
    }
    public void observe(SemanticModel.Listener l) { observer = l; }
    private void status(String s) { status = s; SemanticModel.Listener o = observer; if (o != null) AndroidUtilities.runOnUIThread(o::updated); }
    public String status() { return status; }
    public boolean busy() { return busy; }
    public boolean enabled() { return prefs.getBoolean("enabled", false); }
    public void enabled(boolean value) { prefs.edit().putBoolean("enabled", value).apply(); cancel(); if (value) autoIndex(); }
    public int days() { return prefs.getInt("days", 30); }
    public void days(int days) { prefs.edit().putInt("days", days).apply(); clear(); }
    public boolean chargingOnly() { return prefs.getBoolean("charging", true); }
    public void chargingOnly(boolean value) { prefs.edit().putBoolean("charging", value).apply(); }
    public Set<Long> allowed() {
        Set<Long> result = new HashSet<>();
        for (String s : prefs.getStringSet("allowed", Collections.emptySet())) try { result.add(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
        return result;
    }
    public boolean permits(long dialog) { return enabled() && owner != 0 && UserConfig.getInstance(account).isClientActivated() && UserConfig.getInstance(account).getClientUserId() == owner && allowed().contains(dialog) && eligibleDialog(dialog); }
    public boolean eligibleDialog(long dialog) {
        if (DialogObject.isEncryptedDialog(dialog) || dialog == 0) return false;
        if (dialog < 0) { TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialog); return chat != null && !chat.noforwards; }
        return true;
    }
    public void allow(long dialog, boolean value) {
        Set<Long> current = allowed(); if (value) current.add(dialog); else current.remove(dialog);
        HashSet<String> strings = new HashSet<>(); for (long d : current) strings.add("" + d);
        prefs.edit().putStringSet("allowed", strings).apply(); cancel(); dirty = true;
        if (!value) SemanticModel.QUEUE.postRunnable(() -> { try (SemanticIndex index = new SemanticIndex(owner)) { index.removeDialog(dialog); } });
    }
    public void pause() { prefs.edit().putBoolean("paused",true).apply(); cancel(); status("Indexing paused"); }
    public long revision() { return generation.get(); }
    private void changed() { SemanticModel.Listener o=observer;if(o!=null)AndroidUtilities.runOnUIThread(o::updated); }
    public void cancel() { generation.incrementAndGet(); changed(); }
    public void clear() {
        prefs.edit().putBoolean("paused",true).apply();
        cancel(); dirty = true;
        SemanticModel.QUEUE.postRunnable(() -> { try (SemanticIndex index = new SemanticIndex(owner)) { index.clear(); status("Search index cleared"); } });
    }
    private boolean charging() { Intent i = ApplicationLoader.applicationContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); return i != null && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0; }
    public void autoIndex() { if (dirty && !prefs.getBoolean("paused",false) && enabled() && SemanticModel.ready() && (!chargingOnly() || charging())) index(); }
    public void index() {
        if (busy) return;
        if (!enabled()) { status("Enable semantic search first"); return; }
        if (!SemanticModel.ready()) { status("Download the model first"); return; }
        Set<Long> chats = allowed(); if (chats.isEmpty()) { status("Select at least one allowed chat"); return; }
        prefs.edit().putBoolean("paused",false).apply();
        yieldIndex=false;busy = true; long token = generation.get(); int scopeDays = days();
        SemanticModel.QUEUE.postRunnable(() -> {
            int processed = 0; boolean capped = false;
            try (SemanticIndex index = new SemanticIndex(owner)) {
                for (SemanticIndex.Item i : index.all()) if (!permits(i.dialog)) index.removeDialog(i.dialog);
                int since = scopeDays == 0 ? 0 : (int) (System.currentTimeMillis() / 1000 - scopeDays * 86400L);
                index.db.delete("items","date<?",new String[]{""+since});
                long seen = System.currentTimeMillis();
                for (long dialog : chats) {
                    int before = Integer.MAX_VALUE;
                    while (true) {
                        if (yieldIndex || token != generation.get() || !permits(dialog)) throw new InterruptedException();
                        SemanticMessages.Page page = SemanticMessages.page(account, owner, dialog, since, before);
                        if (page.scanned == 0) break;
                        before = page.before;
                        for (SemanticMessages.Message m : page.items) {
                            if (yieldIndex || token != generation.get() || !permits(dialog)) throw new InterruptedException();
                            if (m.hash.equals(index.hash(dialog, m.id))) { index.keep(dialog, m.id, seen); continue; }
                            ArrayList<String> parts = SemanticText.chunks(m.text);
                            if (index.count() + parts.size() > 10000) { capped = true; break; }
                            ArrayList<float[]> vectors = new ArrayList<>();
                            for (String part : parts) {
                                if (yieldIndex || token != generation.get()) throw new InterruptedException();
                                vectors.add(SemanticModel.embed("title: none | text: " + part));
                            }
                            SemanticMessages.Message current = SemanticMessages.resolve(account, owner, dialog, m.id);
                            if (current != null && current.hash.equals(m.hash) && token == generation.get() && permits(dialog)) index.put(m, parts, vectors, seen);
                            processed++; status("Indexing downloaded messages: " + processed + " updated");
                        }
                        if (capped) break;
                    }
                    if (!capped && token == generation.get()) index.finish(dialog, seen);
                    if (capped) break;
                }
                dirty = capped;
                status("Indexed " + index.count() + " text chunks from downloaded history" + (capped ? " • 10,000-chunk limit reached" : " • up to date"));
            } catch (InterruptedException e) { dirty = true; status("Indexing paused; tap Index now to resume"); }
            catch (Exception | LinkageError e) { dirty = true; status("Indexing failed: " + e.getClass().getSimpleName() + ". Retry or share the issue."); FileLog.e(e); }
            finally { SemanticModel.close(); busy = false; SemanticModel.notifyChanged(); }
        });
    }
    public void search(String query, Results callback) {
        if(busy)yieldIndex=true;
        long token = generation.get();
        SemanticModel.QUEUE.postRunnable(() -> {
            ArrayList<Result> results = new ArrayList<>(); String error = null;
            try (SemanticIndex index = new SemanticIndex(owner)) {
                if (!enabled() || !SemanticModel.ready()) throw new IllegalStateException("Enable search and download the model first");
                if (query.trim().isEmpty() || query.length() > 400) throw new IllegalArgumentException("Enter a shorter search query");
                if(token!=generation.get())throw new InterruptedException();
                float[] q = SemanticModel.embed("task: search result | query: " + query.trim());
                ArrayList<SemanticIndex.Item> candidates = index.all();
                int since = days() == 0 ? 0 : (int) (System.currentTimeMillis()/1000 - days()*86400L);
                candidates.removeIf(i -> !permits(i.dialog) || i.date < since || (SemanticText.cosine(q,i.vector)<0.25 && SemanticText.keyword(i.text,query)==0));
                ArrayList<SemanticIndex.Item> keywords = new ArrayList<>(candidates);
                candidates.sort((a,b) -> Double.compare(SemanticText.cosine(q,b.vector), SemanticText.cosine(q,a.vector)));
                keywords.sort((a,b) -> Double.compare(SemanticText.keyword(b.text,query),SemanticText.keyword(a.text,query)));
                HashMap<SemanticIndex.Item,Double> scores = new HashMap<>();
                for (int n = 0; n < candidates.size(); n++) scores.put(candidates.get(n),1.0/(60+n));
                for (int n = 0; n < keywords.size(); n++) if (SemanticText.keyword(keywords.get(n).text,query)>0) scores.merge(keywords.get(n),1.0/(60+n),Double::sum);
                candidates.sort((a,b) -> Double.compare(scores.get(b),scores.get(a)));
                Set<String> dedup = new HashSet<>();
                for (SemanticIndex.Item i : candidates) {
                    if (token != generation.get()) throw new InterruptedException();
                    String key = i.dialog + ":" + i.mid;
                    if (!dedup.add(key) || !permits(i.dialog)) continue;
                    SemanticMessages.Message m = SemanticMessages.resolve(account, owner, i.dialog, i.mid);
                    if (m == null || !m.hash.equals(i.hash)) { index.remove(i.dialog,i.mid); continue; }
                    if (permits(i.dialog)) results.add(new Result(m));
                    if (results.size() == 20) break;
                }
                if (token != generation.get()) throw new InterruptedException();
            } catch (InterruptedException e) { results.clear(); error = "Search changed; try again"; }
            catch (Exception | LinkageError e) { results.clear(); error = e.getMessage() == null ? "Search failed" : e.getMessage(); }
            finally { SemanticModel.close(); }
            String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (token != generation.get() || !enabled()) callback.completed(new ArrayList<>(),"Search changed; try again");
                else { results.removeIf(r -> !permits(r.dialog)); callback.completed(results, finalError); }
            });
        });
    }
}
