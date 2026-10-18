"""Run the production diagnostics recorder against an in-memory Android prefs adapter."""
from pathlib import Path
import subprocess
import tempfile

sources = {
    'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 String getString(String k,String d); long getLong(String k,long d); int getInt(String k,int d); Editor edit();
 interface Editor { Editor putString(String k,String v); Editor putLong(String k,long v); Editor putInt(String k,int v); Editor remove(String k); Editor clear(); void apply(); }
}''',
    'android/content/Context.java': '''package android.content;
public abstract class Context { public static final int MODE_PRIVATE=0; public abstract SharedPreferences getSharedPreferences(String n,int m); }''',
    'org/telegram/messenger/ApplicationLoader.java': '''package org.telegram.messenger;
public class ApplicationLoader { public static android.content.Context applicationContext; }''',
    'DiagnosticsTest.java': '''import android.content.*;
import java.util.*;
import org.telegram.messenger.*;
public class DiagnosticsTest {
 static class Prefs implements SharedPreferences {
  final Map<String,Object> values=new HashMap<>();
  public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
  public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
  public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
  public Editor edit(){return new Editor(){
   final Map<String,Object> pending=new HashMap<>(); boolean clear;
   public Editor putString(String k,String v){pending.put(k,v);return this;}
   public Editor putLong(String k,long v){pending.put(k,v);return this;}
   public Editor putInt(String k,int v){pending.put(k,v);return this;}
   public Editor remove(String k){pending.put(k,null);return this;}
   public Editor clear(){clear=true;return this;}
   public void apply(){if(clear)values.clear();for(Map.Entry<String,Object> e:pending.entrySet()){if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}}
  };}
 }
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  Prefs prefs=new Prefs();
  ApplicationLoader.applicationContext=new Context(){public SharedPreferences getSharedPreferences(String n,int mode){check(mode==MODE_PRIVATE,"private storage");return prefs;}};
  long user=4433221100L; String token="private-test-token-do-not-store";
  check(PushDiagnostics.snapshot(0,user).tokenState.equals("not_checked"),"empty status");
  PushDiagnostics.tokenRequestStarted();
  check(PushDiagnostics.snapshot(0,user).tokenState.equals("requesting"),"request status");
  PushDiagnostics.tokenFailed(new Exception("SERVICE_NOT_AVAILABLE token="+token));
  check(PushDiagnostics.snapshot(0,user).tokenError.equals("SERVICE_NOT_AVAILABLE"),"safe Firebase error");
  PushDiagnostics.tokenReady(token);
  PushDiagnostics.registrationStarted(0,user,token);
  PushDiagnostics.registrationResult(0,user,token,false,400,"APP_PUSH_APIKEY_MISSING");
  check(PushDiagnostics.snapshot(0,user).registrationError.equals("400: APP_PUSH_APIKEY_MISSING"),"server error surfaced");
  PushDiagnostics.registrationResult(0,user,token,true,0,null);
  check(PushDiagnostics.snapshot(0,user).registrationState.equals("accepted"),"acceptance");
  check(PushDiagnostics.snapshot(1,user).registrationState.equals("not_checked"),"account isolation");
  check(PushDiagnostics.snapshot(0,user+1).registrationState.equals("not_checked"),"account reuse isolation");
  PushDiagnostics.tokenReady("rotated-token");
  check(PushDiagnostics.snapshot(0,user).registrationState.equals("stale"),"rotation invalidates old acceptance");
  PushDiagnostics.registrationStarted(0,user,"rotated-token");
  PushDiagnostics.registrationResult(0,user,token,true,0,null);
  check(PushDiagnostics.snapshot(0,user).registrationState.equals("requesting"),"ignore stale token response");
  PushDiagnostics.registrationResult(0,user,"rotated-token",true,0,null);
  PushDiagnostics.registrationStarted(0,user+1,"rotated-token");
  PushDiagnostics.registrationResult(0,user,"rotated-token",true,0,null);
  check(PushDiagnostics.snapshot(0,user+1).registrationState.equals("requesting"),"ignore previous user response");
  long first=PushDiagnostics.received(1000,2,1);
  long second=PushDiagnostics.received(2000,1,1);
  PushDiagnostics.callbackFinished(first);
  check(PushDiagnostics.snapshot(0,user).finishedAt==0,"old callback must not finish new push");
  PushDiagnostics.callbackFinished(second);
  check(PushDiagnostics.snapshot(0,user).finishedAt>0,"current callback completion");
  check(PushDiagnostics.snapshot(0,user).receivedCount==2,"receipt count");
  for(Object value:prefs.values.values()){
   check(!value.toString().contains(token),"no raw token stored");
   check(!value.toString().contains(Long.toString(user)),"no raw user ID stored");
  }
  check(PushDiagnostics.safeServerError("server echoed "+token).equals("OTHER_ERROR"),"redact unexpected server text");
  PushDiagnostics.clear();
  long third=PushDiagnostics.received(3000,2,2);
  PushDiagnostics.callbackFinished(second);
  check(third!=second && PushDiagnostics.snapshot(0,user).finishedAt==0,"clearing cannot reuse receipt IDs");
  PushDiagnostics.clear();
  Thread[] threads=new Thread[40];
  for(int i=0;i<threads.length;i++){threads[i]=new Thread(()->PushDiagnostics.received(0,0,0));threads[i].start();}
  for(Thread t:threads)t.join();
  check(PushDiagnostics.snapshot(0,user).receivedCount==40,"concurrent receipts preserved");
  PushDiagnostics.clear();
  check(prefs.values.isEmpty(),"clear removes diagnostics only");
  System.out.println("PASS: diagnostics transitions, account/token isolation, errors, receipt races, concurrency, privacy and clearing");
 }
}''',
}

with tempfile.TemporaryDirectory(prefix='tgplus-diagnostics-') as temp:
    root = Path(temp)
    for name, content in sources.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    production = Path('TMessagesProj/src/main/java/org/telegram/messenger/PushDiagnostics.java')
    subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', temp,
                    *[str(root / name) for name in sources], str(production)], check=True)
    subprocess.run(['java', '-cp', temp, 'DiagnosticsTest'], check=True)
