#!/usr/bin/env python3
"""Compile real notification transport sources against in-memory Android/Xposed fakes.
These are contract tests, not a substitute for a real NMS -> Provider -> SQLite test.
No real notification, database, root command or network request is issued.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/io/github/okaidev/pickupcode'
STUBS = {
'android/os/Parcelable.java': 'package android.os; public interface Parcelable {}',
'android/os/Bundle.java': '''package android.os;
import java.util.HashMap;
public class Bundle extends HashMap<String,Object> implements Parcelable {
 public void putString(String k,String v){put(k,v);} public String getString(String k){return (String)get(k);}
 public void putLong(String k,long v){put(k,v);} public long getLong(String k,long d){return containsKey(k)?((Number)get(k)).longValue():d;}
 public void putInt(String k,int v){put(k,v);} public int getInt(String k,int d){return containsKey(k)?((Number)get(k)).intValue():d;}
 public void putBoolean(String k,boolean v){put(k,v);} public CharSequence getCharSequence(String k){return (CharSequence)get(k);}
 public CharSequence[] getCharSequenceArray(String k){return (CharSequence[])get(k);}
 public Parcelable[] getParcelableArray(String k){return (Parcelable[])get(k);}
}''',
'android/os/Binder.java': '''package android.os; public class Binder {
 public static int clear,restore; public static long clearCallingIdentity(){clear++;return 42;}
 public static void restoreCallingIdentity(long t){if(t!=42)throw new AssertionError();restore++;}
}''',
'android/app/Notification.java': '''package android.app; import android.os.Bundle;
public class Notification {
 public Bundle extras = new Bundle(); public CharSequence tickerText;
 public static final String EXTRA_TITLE="title",EXTRA_TEXT="text",EXTRA_BIG_TEXT="big",EXTRA_SUB_TEXT="sub",
 EXTRA_SUMMARY_TEXT="summary",EXTRA_INFO_TEXT="info",EXTRA_TEXT_LINES="lines";
}''',
'android/net/Uri.java': 'package android.net; public class Uri { public static Uri parse(String s){return new Uri();}}',
'android/content/Intent.java': '''package android.content; import android.os.Bundle;
public class Intent { public Bundle extras=new Bundle(); public Intent(String a){}
 public void setPackage(String p){} public void putExtra(String k,String v){extras.putString(k,v);}
 public void putExtra(String k,long v){extras.putLong(k,v);} public void putExtra(String k,boolean v){extras.putBoolean(k,v);}
}''',
'android/content/ContentResolver.java': '''package android.content; import android.net.Uri; import android.os.Bundle;
public abstract class ContentResolver {public abstract Bundle call(Uri u,String m,String a,Bundle e); }''',
'android/content/Context.java': '''package android.content;
public abstract class Context {public abstract ContentResolver getContentResolver(); public abstract void sendBroadcast(Intent i);}''',
'android/content/ContentValues.java': 'package android.content; public class ContentValues {}',
'android/database/Cursor.java': 'package android.database; public interface Cursor {}',
'android/content/ContentProvider.java': '''package android.content; import android.os.Bundle; import android.net.Uri; import android.database.Cursor;
public abstract class ContentProvider {public Context context;public Context getContext(){return context;}
 public abstract boolean onCreate(); public abstract Bundle call(String m,String a,Bundle b);
 public abstract Cursor query(Uri u,String[]p,String s,String[]a,String o); public abstract String getType(Uri u);
 public abstract Uri insert(Uri u,ContentValues v);public abstract int delete(Uri u,String s,String[]a);
 public abstract int update(Uri u,ContentValues v,String s,String[]a);
}''',
'android/util/Log.java': '''package android.util;public class Log {
 public static int i(String t,String s){return 0;} public static int w(String t,String s){return 0;} public static int e(String t,String s){return 0;}}
''',
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed; public class XC_MethodHook {
 public static class MethodHookParam {public Object thisObject;}}
''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;import java.util.*;public class XposedBridge {
 public static final List<String> logs=new ArrayList<>();public static void log(String s){logs.add(s);}}
''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;public class XposedHelpers {
 public static Object getObjectField(Object o,String n)throws Exception{return o.getClass().getField(n).get(o);}}
''',
'io/github/okaidev/pickupcode/PickupExtractor.java': '''package io.github.okaidev.pickupcode;import android.content.Context;
public class PickupExtractor {public static void init(Context c){} public static boolean hasFeatureWords(String s,String b){return b.contains("取件");}}
''',
'io/github/okaidev/pickupcode/TodoWriter.java': '''package io.github.okaidev.pickupcode;import java.util.*;import android.content.Context;
public class TodoWriter {public static class HandleResult {
 public List<String> written=new ArrayList<>();public int codeCount,dedupCount,failedCount;public String status,diagnostic="test";}
 public static HandleResult next;public static HandleResult handleDetailed(Context c,String s,String b){return next;}
 public static int runSql(Context c,String s){return 0;}}
''',
'io/github/okaidev/pickupcode/Notifier.java': '''package io.github.okaidev.pickupcode;import java.util.*;import android.content.Context;
public class Notifier {public static int success,failed;public static void notifyCodes(Context c,List<String>l,Object x){success++;}
 public static void notifyWriteFailed(Context c,String s){failed++;}}
''',
'io/github/okaidev/pickupcode/NotesBackend.java': '''package io.github.okaidev.pickupcode;import android.content.Context;
public class NotesBackend {public static String markDoneSql(Context c,String s){return s;}}
''',
}
HARNESS = '''package io.github.okaidev.pickupcode;
import android.app.Notification;import android.content.*;import android.os.*;import java.util.*;
import de.robv.android.xposed.XposedBridge;
public class NotificationTransportTest {
 static int passed;
 static void ok(boolean yes,String name){if(!yes)throw new AssertionError(name);passed++;}
 static class Ctx extends Context {
   Bundle reply,last;boolean throwsCall;int calls,broadcasts;Intent broadcast;
   public ContentResolver getContentResolver(){return new ContentResolver(){public Bundle call(android.net.Uri u,String m,String a,Bundle e){
    calls++;last=e;if(throwsCall)throw new IllegalStateException("unavailable");return reply;}};}
   public void sendBroadcast(Intent i){broadcasts++;broadcast=i;}
 }
 public static void main(String[]args){
   Notification n=new Notification();n.extras.putString(Notification.EXTRA_TITLE,"取件提醒");
   n.extras.putString(Notification.EXTRA_TEXT,"包裹已到站，请查看详情");
   ok(!NotificationText.build(n).contains("99-3-2001"),"do not invent missing code");
   n.extras.put(Notification.EXTRA_TEXT_LINES,new CharSequence[]{"取件码99-3-2001"});
   ok(NotificationText.build(n).contains("99-3-2001"),"inbox line text");
   Bundle msg=new Bundle();msg.putString("text","取件码99-3-2002");
   n.extras.put("android.messages",new Parcelable[]{msg});
   ok(NotificationText.build(n).contains("99-3-2002"),"messaging text");
   n.tickerText="凭99-3-2003取件";ok(NotificationText.build(n).contains("99-3-2003"),"ticker fallback");
   n.extras.putString(Notification.EXTRA_BIG_TEXT,"x".repeat(10000));
   ok(NotificationText.build(n).length()<=8192,"bounded text");
   ok(NotiEventWindow.accept("p","same",1),"first event");
   ok(!NotiEventWindow.accept("p","same",2),"nested overload skipped");
   ok(NotiEventWindow.accept("p","changed",2),"changed event kept");
   ok(NotiEventWindow.accept("p","same",31001),"window expires");
   Ctx c=new Ctx();c.reply=new Bundle();c.reply.putString("status","no_code");
   NotiRelay.deliver(c,"test.pkg","取件通知",true,1);
   ok(c.calls==1&&c.broadcasts==0,"miss uses Provider");
   ok(Boolean.TRUE.equals(c.last.get("miss")),"boolean miss flag");
   ok(XposedBridge.logs.get(XposedBridge.logs.size()-1).contains("status=no_code"),"explicit no-code acknowledgement");
   c.reply=null;NotiRelay.deliver(c,"test.pkg","取件通知",true,2);
   ok(c.broadcasts==1&&Boolean.TRUE.equals(c.broadcast.extras.get("miss")),"null receipt uses compatible fallback");
   ok(XposedBridge.logs.get(XposedBridge.logs.size()-1).contains("unconfirmed"),"broadcast not claimed delivered");
   c.throwsCall=true;NotiRelay.deliver(c,"test.pkg","取件码99-3-2004",false,3);
   ok(c.broadcasts==2,"exception fallback");
   ok(Binder.clear==Binder.restore,"restore Binder identity");
   TodoProvider provider=new TodoProvider();provider.context=c;
   Bundle e=new Bundle();e.putString("body","");
   ok("empty".equals(provider.call("onSms",null,e).getString("status")),"empty outcome");
   e.putString("body","不是快递");
   ok("not_pickup".equals(provider.call("onSms",null,e).getString("status")),"nonparcel outcome");
   e.putString("body","取件通知");
   String[] states={"no_code","blacklisted","written","deduplicated","partial","write_failed"};
   for(String st:states){
    TodoWriter.HandleResult out=new TodoWriter.HandleResult();out.status=st;
    if(st.equals("written")||st.equals("partial"))out.written.add("99-3-2005");
    if(st.equals("deduplicated"))out.dedupCount=1;
    if(st.equals("partial")||st.equals("write_failed"))out.failedCount=1;
    out.codeCount=out.written.size()+out.dedupCount+out.failedCount;TodoWriter.next=out;
    Bundle got=provider.call("onSms",null,e);
    ok(st.equals(got.getString("status")),"structured status "+st);
    ok(got.getInt("written_count",-1)==out.written.size(),"write count "+st);
   }
   ok(Notifier.failed==2,"only actual write failures notify failure");
   System.out.println("Notification transport/text contracts: PASS="+passed+" FAIL=0");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='pickup-noti-test-') as td:
    out = Path(td)
    for rel, text in STUBS.items():
        p = out / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding='utf-8')
    p = out / 'io/github/okaidev/pickupcode/NotificationTransportTest.java'
    p.write_text(HARNESS, encoding='utf-8')
    files = list(out.rglob('*.java'))
    files += [SRC / name for name in ('NotiRelay.java','NotiEventWindow.java','NotificationText.java','TodoProvider.java')]
    subprocess.run(['javac','-encoding','UTF-8','--release','11','-d',str(out),*[str(f) for f in files]], check=True)
    subprocess.run(['java','-cp',str(out),'io.github.okaidev.pickupcode.NotificationTransportTest'], check=True)
