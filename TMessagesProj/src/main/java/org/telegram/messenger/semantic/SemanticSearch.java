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
    private static boolean cleanupRecovered;
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
        initialize();
        long owner = UserConfig.getInstance(account).getClientUserId();
        SemanticSearch old = instances.get(account);
        if (old == null || old.owner != owner) {
            if (old != null) old.logout();
            old = new SemanticSearch(account, owner); instances.put(account, old);
        }
        return old;
    }
    /** Event handling loads account policy, without downloading or starting the model. */
    private static synchronized SemanticSearch eventAccount(int account) {
        initialize();
        UserConfig config=UserConfig.getInstance(account);
        return config.isClientActivated()&&config.getClientUserId()!=0?get(account):null;
    }
    private static SharedPreferences pendingCleanup() {
        return ApplicationLoader.applicationContext.getSharedPreferences("semantic-pending-cleanup",Context.MODE_PRIVATE);
    }
    private static synchronized void pending(long uid, boolean add) {
        HashSet<String> owners=new HashSet<>(pendingCleanup().getStringSet("owners",Collections.emptySet()));
        if(add)owners.add(""+uid);else owners.remove(""+uid);
        if(!pendingCleanup().edit().putStringSet("owners",owners).commit())throw new IllegalStateException("Cannot persist search cleanup");
    }
    public static synchronized void initialize() {
        if(cleanupRecovered)return;
        cleanupRecovered=true;
        for(String value:new HashSet<>(pendingCleanup().getStringSet("owners",Collections.emptySet()))) {
            try { long uid=Long.parseLong(value); if(uid>0)purgeOwner(uid); }
            catch(NumberFormatException e){FileLog.e(e);}
        }
    }
    private static void purgeOwner(long uid) {
        cleanup(null,()->{
            deleteIndex(SemanticIndex.file(uid));
            pending(uid,false);
        });
    }
    public static void cancelAll() { for (SemanticSearch s : instances.values()) s.cancel(); }
    private static void cleanup(SemanticSearch owner, Runnable action) {
        SemanticModel.QUEUE.postRunnable(() -> {
            SemanticTasks.run(action, e -> {
                FileLog.e(e);
                if (owner != null) { owner.dirty=true; owner.status("Search data cleanup failed; retry clearing the index"); }
                else for(SemanticSearch s:instances.values()) { s.dirty=true; s.status("Search data cleanup failed; retry clearing the index"); }
            });
        });
    }
    private static final class MessageChange {
        final long dialog; final int mid; final String hash;
        MessageChange(TLRPC.Message m) {
            dialog=MessageObject.getDialogId(m); mid=m.id;
            String next=null;
            if(SemanticMessages.eligible(m))try{next=SemanticText.fingerprint(m.message);}catch(Exception e){FileLog.e(e);}
            hash=next;
        }
    }
    public static void sourceChanged(int account) {
        SemanticSearch s = eventAccount(account); if (s == null) return;
        s.dirty = true; s.cancel();
        AndroidUtilities.runOnUIThread(() -> s.autoIndex(), 2000);
    }
    public static void sourceMessages(int account, ArrayList<TLRPC.Message> messages) {
        if(messages==null)return;
        SemanticSearch s=eventAccount(account);if(s==null)return;
        Set<Long> allowed=s.allowed();ArrayList<MessageChange> copy=new ArrayList<>();
        for(TLRPC.Message m:messages)if(m!=null&&allowed.contains(MessageObject.getDialogId(m)))copy.add(new MessageChange(m));
        if(copy.isEmpty())return;sourceChanged(account);
        cleanup(s,()->{if(!SemanticIndex.file(s.owner).exists())return;try(SemanticIndex index=new SemanticIndex(s.owner)){
            for(MessageChange m:copy){if(m.mid<=0)continue;
                String previous=index.hash(m.dialog,m.mid);
                if(previous!=null&&(m.hash==null||!s.permits(m.dialog)||!previous.equals(m.hash)))index.remove(m.dialog,m.mid);
            }
        }});
    }
    public static void chatProtected(int account, long chatId) {
        SemanticSearch s=eventAccount(account);if(s==null)return;
        s.cancel();s.dirty=true;
        cleanup(s,()->{if(!SemanticIndex.file(s.owner).exists())return;try(SemanticIndex index=new SemanticIndex(s.owner)){index.removeDialog(-chatId);}});
    }
    public static void messagesDeleted(int account, long dialog, ArrayList<Integer> mids) {
        SemanticSearch s=eventAccount(account); if(s==null)return;
        s.cancel();s.dirty=true;ArrayList<Integer> copy=mids==null?new ArrayList<>():new ArrayList<>(mids);
        cleanup(s,()->{if(!SemanticIndex.file(s.owner).exists())return;try(SemanticIndex index=new SemanticIndex(s.owner)){
            for(int mid:copy){if(dialog!=0)index.remove(dialog,mid);else index.db.delete("items","mid=?",new String[]{""+mid});}
        }});
    }
    public static void historyDeleted(int account,long dialog,int maxId){
        SemanticSearch s=eventAccount(account);if(s==null)return;s.cancel();s.dirty=true;
        cleanup(s,()->{if(!SemanticIndex.file(s.owner).exists())return;try(SemanticIndex index=new SemanticIndex(s.owner)){
            index.db.delete("items",maxId>0?"dialog=? AND mid<=?":"dialog=?",maxId>0?new String[]{""+dialog,""+maxId}:new String[]{""+dialog});
        }});
    }
    public static void clearAllIndexes(){
        cancelAll();cleanup(null,()->{
            java.io.File[] files=ApplicationLoader.applicationContext.getNoBackupFilesDir().listFiles();
            if(files!=null)for(java.io.File f:files)if(f.getName().startsWith("semantic-index-")&&f.getName().endsWith(".db"))deleteIndex(f);
            for(SemanticSearch s:instances.values()){s.dirty=true;s.status("Search index cleared");}
        });
    }
    public static synchronized void accountCleared(int account) { accountCleared(account,UserConfig.getInstance(account).getClientUserId()); }
    public static synchronized void accountCleared(int account,long uid) {
        initialize();
        SemanticSearch s=instances.remove(account);
        if(s!=null)s.logout();
        if(uid>0&&(s==null||s.owner!=uid))clearOwner(uid);
    }
    public static void resumeEnabledAccounts() {
        initialize();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (UserConfig.getInstance(a).isClientActivated()) get(a).autoIndex();
    }
    private void logout() {
        cancel();clearOwner(owner);
    }
    private static void clearOwner(long uid) {
        if(uid<=0)return;
        try { pending(uid,true); }
        catch(Exception e){FileLog.e(e);}
        ApplicationLoader.applicationContext.getSharedPreferences("semantic-search-"+uid,Context.MODE_PRIVATE).edit().clear().commit();
        purgeOwner(uid);
    }
    private static void deleteIndex(java.io.File file) {
        if(file.exists()&&!android.database.sqlite.SQLiteDatabase.deleteDatabase(file))throw new IllegalStateException("Cannot remove search database");
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
        if (!value) cleanup(this,() -> { try (SemanticIndex index = new SemanticIndex(owner)) { index.removeDialog(dialog); } });
    }
    public void pause() { prefs.edit().putBoolean("paused",true).apply(); cancel(); status("Indexing paused"); }
    public long revision() { return generation.get(); }
    private void changed() { SemanticModel.Listener o=observer;if(o!=null)AndroidUtilities.runOnUIThread(o::updated); }
    public void cancel() { generation.incrementAndGet(); changed(); }
    public void clear() {
        prefs.edit().putBoolean("paused",true).apply();
        cancel(); dirty = true;
        cleanup(this,() -> { try (SemanticIndex index = new SemanticIndex(owner)) { index.clear(); status("Search index cleared"); } });
    }
    private boolean charging() { Intent i = ApplicationLoader.applicationContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); return i != null && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0; }
    public void autoIndex() { if (dirty && !prefs.getBoolean("paused",false) && enabled() && SemanticModel.ready() && SemanticPolicy.canRun(true,chargingOnly(),charging())) index(true); }
    public void index() { index(false); }
    private void checkpoint(boolean automatic, long token, long dialog) throws InterruptedException {
        if (yieldIndex || token != generation.get() || !permits(dialog)) throw new InterruptedException("Indexing paused; tap Index now to resume");
        if (!SemanticPolicy.canRun(automatic,chargingOnly(),!automatic||charging())) throw new InterruptedException("Automatic indexing paused; plug in and reopen the app to resume");
    }
    private void index(boolean automatic) {
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
                        checkpoint(automatic,token,dialog);
                        SemanticMessages.Page page = SemanticMessages.page(account, owner, dialog, since, before);
                        if (page.scanned == 0) break;
                        before = page.before;
                        for (SemanticMessages.Message m : page.items) {
                            checkpoint(automatic,token,dialog);
                            if (m.hash.equals(index.hash(dialog, m.id))) { index.keep(dialog, m.id, seen); continue; }
                            ArrayList<String> parts = SemanticText.chunks(m.text);
                            if (!index.canPut(m,parts.size())) { capped = true; continue; }
                            ArrayList<float[]> vectors = new ArrayList<>();
                            for (String part : parts) {
                                checkpoint(automatic,token,dialog);
                                vectors.add(SemanticModel.embed("title: none | text: " + part));
                            }
                            SemanticMessages.Message current = SemanticMessages.resolve(account, owner, dialog, m.id);
                            checkpoint(automatic,token,dialog);
                            if (current != null && current.hash.equals(m.hash)) {
                                if(!index.put(m, parts, vectors, seen))capped=true;
                            }
                            processed++; status("Indexing downloaded messages: " + processed + " updated");
                        }
                    }
                    if (token == generation.get()) index.finish(dialog, seen);
                }
                if(token!=generation.get())throw new InterruptedException("Indexing paused; tap Index now to resume");
                dirty = false;
                status("Indexed " + index.count() + " text chunks from downloaded history" + (capped ? " • older text omitted at the 10,000-chunk limit" : " • up to date"));
            } catch (InterruptedException e) { dirty = true; status(e.getMessage()); }
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

