"""Run the real coordinator with deterministic JVM-only platform dependencies.

This exercises event/lifecycle coordination, not Android SQLite or native inference.
"""
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
"android/content/SharedPreferences.java": '''package android.content;
import java.util.Set;
public interface SharedPreferences {
 boolean getBoolean(String k,boolean d); int getInt(String k,int d); Set<String> getStringSet(String k,Set<String> d); Editor edit();
 interface Editor { Editor putBoolean(String k,boolean v); Editor putInt(String k,int v); Editor putStringSet(String k,Set<String> v); Editor clear(); void apply(); boolean commit(); }
}''',
"android/content/Context.java": '''package android.content;
import java.io.*;import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0;public final Map<String,Prefs> prefs=new HashMap<>();
 public final File root=new File(System.getProperty("test.root"));
 public File getNoBackupFilesDir(){root.mkdirs();return root;}
 public SharedPreferences getSharedPreferences(String name,int mode){return prefs.computeIfAbsent(name,k->new Prefs());}
 public Intent registerReceiver(Object receiver,IntentFilter filter){return new Intent();}
 public static class Prefs implements SharedPreferences {
  final Map<String,Object> values=new HashMap<>();
  public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
  public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
  @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String> d){return new HashSet<>((Set<String>)values.getOrDefault(k,d));}
  public Editor edit(){return new Editor(){final Map<String,Object> changes=new HashMap<>();boolean clear;
   public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}public Editor putInt(String k,int v){changes.put(k,v);return this;}
   public Editor putStringSet(String k,Set<String> v){changes.put(k,new HashSet<>(v));return this;}public Editor clear(){clear=true;return this;}
   public void apply(){commit();}public boolean commit(){if(clear)values.clear();values.putAll(changes);return true;}
  };}
 }
}''',
"android/content/Intent.java": '''package android.content;public class Intent {public static final String ACTION_BATTERY_CHANGED="battery";public int getIntExtra(String key,int fallback){return 1;}}''',
"android/content/IntentFilter.java": '''package android.content;public class IntentFilter {public IntentFilter(String action){}}''',
"android/os/BatteryManager.java": '''package android.os;public class BatteryManager {public static final String EXTRA_PLUGGED="plugged";}''',
"android/database/sqlite/SQLiteDatabase.java": '''package android.database.sqlite;
import java.io.File;import org.telegram.messenger.semantic.SemanticIndex;
public class SQLiteDatabase {public static boolean deleteDatabase(File file){return SemanticIndex.erase(file);}public int delete(String table,String where,String[] args){throw new UnsupportedOperationException(where);}}
''',
"org/telegram/messenger/ApplicationLoader.java": '''package org.telegram.messenger;public class ApplicationLoader {public static final android.content.Context applicationContext=new android.content.Context();}''',
"org/telegram/messenger/AndroidUtilities.java": '''package org.telegram.messenger;public class AndroidUtilities {public static void runOnUIThread(Runnable r){r.run();}public static void runOnUIThread(Runnable r,long delay){}}''',
"org/telegram/messenger/UserConfig.java": '''package org.telegram.messenger;public class UserConfig {public static final int MAX_ACCOUNT_COUNT=3;static final UserConfig[] all={new UserConfig(),new UserConfig(),new UserConfig()};public long uid;public static UserConfig getInstance(int account){return all[account];}public long getClientUserId(){return uid;}public boolean isClientActivated(){return uid!=0;}}''',
"org/telegram/messenger/MessagesController.java": '''package org.telegram.messenger;import org.telegram.tgnet.TLRPC;public class MessagesController {public static MessagesController getInstance(int a){return new MessagesController();}public TLRPC.Chat getChat(long id){return new TLRPC.Chat();}}''',
"org/telegram/messenger/MessageObject.java": '''package org.telegram.messenger;public class MessageObject {public static long getDialogId(org.telegram.tgnet.TLRPC.Message m){return m.dialog;}}''',
"org/telegram/messenger/DialogObject.java": '''package org.telegram.messenger;public class DialogObject {public static boolean isEncryptedDialog(long id){return id==Long.MIN_VALUE;}}''',
"org/telegram/messenger/FileLog.java": '''package org.telegram.messenger;public class FileLog {public static void e(Throwable error){}}''',
"org/telegram/tgnet/TLRPC.java": '''package org.telegram.tgnet;public class TLRPC {public static class Chat {public boolean noforwards;}public static class Message {public long dialog;public int id;public String message;public boolean noforwards;public int expire_date,ttl,ttl_period;}}''',
"org/telegram/messenger/semantic/SemanticModel.java": '''package org.telegram.messenger.semantic;import java.util.*;public class SemanticModel {public static final Queue QUEUE=new Queue();public interface Listener{void updated();}public static class Queue {public final ArrayDeque<Runnable> jobs=new ArrayDeque<>();public void postRunnable(Runnable r){jobs.add(r);}public void drain(){while(!jobs.isEmpty())jobs.remove().run();}}public static boolean ready(){return false;}public static void close(){}public static void notifyChanged(){}public static float[] embed(String text){throw new AssertionError("Event cleanup must not load the model");}}''',
"org/telegram/messenger/semantic/SemanticMessages.java": '''package org.telegram.messenger.semantic;import java.util.*;import org.telegram.tgnet.TLRPC;public class SemanticMessages {public static class Message {public long dialog;public int id,date;public String hash,text;}public static class Page {public final ArrayList<Message> items=new ArrayList<>();public int before,scanned;}public static Page page(int account,long owner,long dialog,int since,int before){throw new AssertionError("Cleanup must not index messages");}public static Message resolve(int account,long owner,long dialog,int id){throw new AssertionError("Cleanup must not start search");}public static boolean eligible(TLRPC.Message m){return m!=null&&SemanticPolicy.eligible(m.id,m.message,m.noforwards,m.expire_date>0||m.ttl>0||m.ttl_period>0,false);}}''',
"org/telegram/messenger/semantic/SemanticIndex.java": '''package org.telegram.messenger.semantic;
import java.io.*;import java.util.*;import org.telegram.messenger.ApplicationLoader;import android.database.sqlite.SQLiteDatabase;
public class SemanticIndex implements AutoCloseable {
 public static final Map<Long,Map<String,String>> rows=new HashMap<>();public static boolean failDelete;
 final long uid;public final SQLiteDatabase db;
 public static File file(long uid){return new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),"semantic-index-"+uid+".db");}
 public SemanticIndex(long owner){uid=owner;try{file(uid).createNewFile();}catch(IOException e){throw new RuntimeException(e);}rows.computeIfAbsent(uid,k->new HashMap<>());db=new SQLiteDatabase(){@Override public int delete(String table,String where,String[] args){Map<String,String> data=rows.get(uid);if(where.equals("mid=?"))data.keySet().removeIf(k->k.endsWith(":"+args[0]));else if(where.equals("dialog=?"))removeDialog(Long.parseLong(args[0]));else if(where.equals("dialog=? AND mid<=?"))data.keySet().removeIf(k->k.startsWith(args[0]+":")&&Integer.parseInt(k.split(":")[1])<=Integer.parseInt(args[1]));else throw new AssertionError(where);return 1;}};}
 public static void seed(long uid,long dialog,int mid,String text)throws Exception{try(SemanticIndex i=new SemanticIndex(uid)){rows.get(uid).put(dialog+":"+mid,SemanticText.fingerprint(text));}}
 public static boolean has(long uid,long dialog,int mid){return rows.getOrDefault(uid,Collections.emptyMap()).containsKey(dialog+":"+mid);}
 public static boolean erase(File file){if(failDelete)return false;long uid=Long.parseLong(file.getName().replace("semantic-index-","").replace(".db",""));rows.remove(uid);return file.delete();}
 public String hash(long dialog,int mid){return rows.get(uid).get(dialog+":"+mid);}public void remove(long dialog,int mid){rows.get(uid).remove(dialog+":"+mid);}public void removeDialog(long dialog){rows.get(uid).keySet().removeIf(k->k.startsWith(dialog+":"));}
 public static class Item {long dialog;int mid,date;String hash,text;float[] vector;}
 public ArrayList<Item> all(){return new ArrayList<>();}public int count(){return rows.get(uid).size();}public void keep(long dialog,int mid,long seen){}public boolean canPut(SemanticMessages.Message m,int n){return true;}public boolean put(SemanticMessages.Message m,ArrayList<String> chunks,ArrayList<float[]> vectors,long seen){throw new AssertionError("Unexpected indexing");}public void finish(long dialog,long seen){}public void clear(){rows.get(uid).clear();}public void close(){}
}''',
"SemanticLifecycleTest.java": '''import org.telegram.messenger.*;import org.telegram.messenger.semantic.*;import org.telegram.tgnet.TLRPC;import android.content.*;import java.util.*;import java.lang.reflect.*;import java.nio.file.*;
public class SemanticLifecycleTest {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static void restart()throws Exception {Field f=SemanticSearch.class.getDeclaredField("instances");f.setAccessible(true);((Map<?,?>)f.get(null)).clear();try{f=SemanticSearch.class.getDeclaredField("cleanupRecovered");f.setAccessible(true);f.setBoolean(null,false);}catch(NoSuchFieldException ignored){}SemanticModel.QUEUE.jobs.clear();}
 static void permit(long uid,long dialog){ApplicationLoader.applicationContext.getSharedPreferences("semantic-search-"+uid,Context.MODE_PRIVATE).edit().putBoolean("enabled",true).putStringSet("allowed",new HashSet<>(Arrays.asList(""+dialog))).commit();}
 static Set<String> pending(){return ApplicationLoader.applicationContext.getSharedPreferences("semantic-pending-cleanup",Context.MODE_PRIVATE).getStringSet("owners",Collections.emptySet());}
 public static void main(String[] args)throws Exception {
  UserConfig.getInstance(0).uid=101;UserConfig.getInstance(1).uid=202;permit(101,10);permit(202,10);
  SemanticIndex.seed(101,10,1,"old");SemanticIndex.seed(202,10,1,"other account");restart();
  SemanticSearch.messagesDeleted(0,10,new ArrayList<>(Arrays.asList(1)));SemanticModel.QUEUE.drain();
  check(!SemanticIndex.has(101,10,1),"Cold deletion purges persisted index");check(SemanticIndex.has(202,10,1),"Deletion remains account isolated");
  SemanticIndex.seed(101,-50,2,"protected chat");restart();SemanticSearch.chatProtected(0,50);SemanticModel.QUEUE.drain();check(!SemanticIndex.has(101,-50,2),"Cold protected-chat update purges even stale unselected rows");
  for(boolean protectedContent:new boolean[]{true,false}){
   SemanticIndex.seed(101,10,3,"unchanged");restart();TLRPC.Message m=new TLRPC.Message();m.id=3;m.dialog=10;m.message="unchanged";m.noforwards=protectedContent;m.ttl_period=protectedContent?0:20;
   SemanticSearch.sourceMessages(0,new ArrayList<>(Arrays.asList(m)));m.noforwards=false;m.ttl_period=0;SemanticModel.QUEUE.drain();check(!SemanticIndex.has(101,10,3),"Cold metadata cleanup uses immutable eligibility snapshot");
  }
  SemanticIndex.seed(101,10,4,"history");restart();SemanticSearch.historyDeleted(0,10,4);SemanticModel.QUEUE.drain();check(!SemanticIndex.has(101,10,4),"Cold history deletion works");
  SemanticIndex.seed(101,10,5,"logout");restart();SemanticSearch.accountCleared(0,101);UserConfig.getInstance(0).uid=0;
  check(pending().contains("101"),"Logout records owner before asynchronous deletion");check(!ApplicationLoader.applicationContext.getSharedPreferences("semantic-search-101",Context.MODE_PRIVATE).getBoolean("enabled",false),"Logout immediately clears persisted policy");
  restart();SemanticSearch.initialize();SemanticModel.QUEUE.drain();check(!SemanticIndex.file(101).exists()&&!pending().contains("101"),"Next process retries interrupted logout cleanup");check(SemanticIndex.has(202,10,1),"Logout preserves another account");
  UserConfig.getInstance(0).uid=101;permit(101,10);SemanticIndex.seed(101,10,6,"pending event");restart();SemanticSearch.accountCleared(0,101);SemanticSearch.messagesDeleted(0,10,new ArrayList<>(Arrays.asList(6)));UserConfig.getInstance(0).uid=0;SemanticModel.QUEUE.drain();check(!SemanticIndex.file(101).exists(),"Queued stale events do not recreate removed index");
  UserConfig.getInstance(0).uid=101;SemanticIndex.seed(101,10,7,"retry failure");restart();SemanticIndex.failDelete=true;SemanticSearch.accountCleared(0,101);UserConfig.getInstance(0).uid=0;SemanticModel.QUEUE.drain();check(pending().contains("101"),"Failed disk cleanup retains retry marker");
  restart();SemanticIndex.failDelete=false;SemanticSearch.initialize();SemanticModel.QUEUE.drain();check(!SemanticIndex.file(101).exists()&&pending().isEmpty(),"Later retry completes cleanup");
  String source=Files.readString(Path.of(args[0]));int begin=source.indexOf("public void clearConfig()");int hook=source.indexOf("SemanticSearch.accountCleared",begin);int reset=source.indexOf("clientUserId = 0",begin);check(hook>begin&&hook<reset,"Shared logout hook captures owner before identity reset, including push revocation");
  System.out.println("PASS cold deletion/history/protection/expiry, immutable snapshots, account isolation, logout identity capture, interrupted/failed purge recovery and no stale index recreation");
 }
}'''
}

with tempfile.TemporaryDirectory(prefix="semantic-lifecycle-") as work:
    work = pathlib.Path(work)
    sources = []
    for name, text in FILES.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        sources.append(str(path))
    for name in ("SemanticSearch", "SemanticText", "SemanticPolicy", "SemanticTasks"):
        sources.append(str(ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/semantic" / (name + ".java")))
    subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(work / "classes"), *sources], check=True)
    subprocess.run(["java", "-Dtest.root=" + str(work / "data"), "-cp", str(work / "classes"), "SemanticLifecycleTest", str(ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java")], check=True)
