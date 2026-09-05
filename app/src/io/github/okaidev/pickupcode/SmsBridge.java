package io.github.okaidev.pickupcode;

import android.content.Intent;
import android.telephony.SmsMessage;

import de.robv.android.xposed.XposedHelpers;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 短信桥：从 Hook 参数中提取短信正文与来源。
 *
 * 支持参数形态：Intent / SmsMessage / byte[] PDU / Handler Message(obj)。
 * S8 兜底：SmsProvider.insert（ContentValues）——所有短信入库必经，网络短信也覆盖。
 *
 * 处理规则：
 *  1) Provider 路径整条入库 → 立即处理；
 *  2) 广播路径多段拼接（同来源 8 秒窗口，最多 4 段）→ 到期/满段处理；
 *  3) 进程内指纹去重（防多 Hook 点重复）；
 *  4) 处理后向模块自身进程广播（提取→去重→写待办→通知都在模块 App 完成）。
 */
public class SmsBridge {

    /** 进程内最近处理过的指纹（sender+body），容量 200 */
    private static final LinkedHashSet<String> RECENT = new LinkedHashSet<>();
    private static final int RECENT_MAX = 200;

    /** 多段拼接缓冲：key = sender */
    private static final java.util.LinkedHashMap<String, PendingPart> PENDING = new java.util.LinkedHashMap<>();
    private static final long PART_WINDOW_MS = 8000L;
    private static final int MAX_PARTS = 4;

    /** 进程内记住的 Context（来自 onReceive 参数），用于向模块 App 广播 */
    private static volatile android.content.Context lastContext;

    private static class PendingPart {
        long firstSeen;
        String body;
        int parts;
    }

    /**
     * 获取 Context：优先用 onReceive 参数记住的；否则反射 ActivityThread.currentApplication()
     * （Provider 进程没有 Context 参数，但反射在 framework 进程可用）
     */
    private static android.content.Context obtainContext() {
        if (lastContext != null) return lastContext;
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (app instanceof android.content.Context) {
                lastContext = (android.content.Context) app;
            }
        } catch (Throwable ignored) { }
        return lastContext;
    }

    public static void processArguments(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        // 记住 Context（广播转发用）
        for (Object a : param.args) {
            if (a instanceof android.content.Context) {
                lastContext = (android.content.Context) a;
            }
        }
        // 识别短信参数形态
        for (Object a : param.args) {
            if (a instanceof Intent) {
                handleIntent((Intent) a);
                return;
            }
            if (a instanceof SmsMessage) {
                handleSmsMessage((SmsMessage) a);
                return;
            }
            if (a instanceof byte[]) {
                handlePdu((byte[]) a);
                return;
            }
            if (a instanceof android.os.Message) {
                Object obj = ((android.os.Message) a).obj;
                if (obj instanceof SmsMessage) {
                    handleSmsMessage((SmsMessage) obj);
                    return;
                }
                if (obj instanceof Intent) {
                    handleIntent((Intent) obj);
                    return;
                }
            }
        }
        // 未识别：输出参数形状诊断（每进程最多 15 次，防刷屏）
        if (argDumpCounter < 15) {
            argDumpCounter++;
            StringBuilder sb = new StringBuilder("ARGDUMP:");
            for (int i = 0; i < param.args.length; i++) {
                Object a = param.args[i];
                String cls = a == null ? "null" : a.getClass().getName();
                String txt = a == null ? "null"
                        : (a instanceof String ? (String) a
                        : (String.valueOf(a).length() > 120 ? String.valueOf(a).substring(0, 120) : String.valueOf(a)));
                sb.append(" #").append(i).append("(").append(cls).append(")=").append(txt);
            }
            XposedEntry.log(sb.toString());
        }
    }

    private static int argDumpCounter = 0;

    /**
     * S8：SmsProvider.insert 兜底——所有短信入库必经，无论普通短信还是小米网络短信。
     * 参数：insert(Uri, ContentValues)
     */
    public static void processProviderInsert(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 2) return;
        Object uri = param.args[0];
        Object cv = param.args[1];
        if (!(cv instanceof android.content.ContentValues)) return;
        if (!isSmsUri(uri)) return;
        handleContentValues((android.content.ContentValues) cv);
    }

    /**
     * S8b：bulkInsert(Uri, ContentValues[])
     */
    public static void processProviderBulkInsert(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 2) return;
        Object uri = param.args[0];
        Object cvArr = param.args[1];
        if (!isSmsUri(uri)) return;
        if (cvArr instanceof Object[]) {
            for (Object o : (Object[]) cvArr) {
                if (o instanceof android.content.ContentValues) {
                    handleContentValues((android.content.ContentValues) o);
                }
            }
        }
    }

    /** 仅处理短信（含 inbox 网络短信），不处理 mms/附件 */
    private static boolean isSmsUri(Object uri) {
        String u = String.valueOf(uri == null ? "" : uri);
        u = u.toLowerCase();
        return u.contains("sms") || u.contains("inbox");
    }

    private static void handleContentValues(android.content.ContentValues cv) {
        String body = cv.getAsString("body");
        if (body == null || body.trim().isEmpty()) return;
        String sender = cv.getAsString("address");
        long date = cv.getAsLong("date") != null ? cv.getAsLong("date") : System.currentTimeMillis();
        // Provider 写入 = 整条短信一次入库 → 立即处理
        pushParts(sender, body, 1, true);
        XposedEntry.log("PROVIDER-INSERT: date=" + date + " sender=" + sender);
    }

    private static void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (action == null
                || (!"android.provider.Telephony.SMS_RECEIVED".equals(action)
                && !"android.provider.Telephony.SMS_DELIVER".equals(action))) {
            return; // 非短信广播
        }
        Object raw = intent.getSerializableExtra("pdus");
        if (!(raw instanceof Object[])) return;
        Object[] pdus = (Object[]) raw;
        if (pdus.length == 0) return;

        String format = intent.getStringExtra("format");
        if (format == null) format = "3gpp";

        String sender = null;
        StringBuilder bodyBuilder = new StringBuilder();
        for (Object pduObj : pdus) {
            if (!(pduObj instanceof byte[])) continue;
            try {
                SmsMessage m = SmsMessage.createFromPdu((byte[]) pduObj, format);
                if (m == null) continue;
                if (sender == null) sender = m.getDisplayOriginatingAddress();
                if (sender == null) sender = m.getOriginatingAddress();
                bodyBuilder.append(m.getMessageBody() == null ? "" : m.getMessageBody());
            } catch (Throwable t) {
                XposedEntry.log("pdu decode err: " + t);
            }
        }
        pushParts(sender, bodyBuilder.toString(), pdus.length, false);
    }

    private static void handlePdu(byte[] pdu) {
        try {
            SmsMessage m = SmsMessage.createFromPdu(pdu, "3gpp");
            if (m == null) return;
            String sender = m.getDisplayOriginatingAddress();
            if (sender == null) sender = m.getOriginatingAddress();
            pushParts(sender, m.getMessageBody() == null ? "" : m.getMessageBody(), 1, false);
        } catch (Throwable t) {
            XposedEntry.log("pdu decode err: " + t);
        }
    }

    private static void handleSmsMessage(SmsMessage m) {
        try {
            String sender = m.getDisplayOriginatingAddress();
            if (sender == null) sender = m.getOriginatingAddress();
            pushParts(sender, m.getMessageBody() == null ? "" : m.getMessageBody(), 1, false);
        } catch (Throwable t) {
            XposedEntry.log("sms err: " + t);
        }
    }

    private static void pushParts(String sender, String body, int inBroadcast, boolean flushNow) {
        if (body == null || body.isEmpty()) return;
        if (sender == null) sender = "unknown";

        long now = System.currentTimeMillis();
        cleanupPending(now);

        PendingPart part = PENDING.get(sender);
        if (part == null || now - part.firstSeen > PART_WINDOW_MS) {
            PendingPart np = new PendingPart();
            np.firstSeen = now;
            np.body = body;
            np.parts = 1;
            PENDING.put(sender, np);
        } else {
            part.body += body;
            part.parts += 1;
        }
        part = PENDING.get(sender);
        // Provider 写入（整条短信一次入库）：立即处理；广播路径：等窗口/多段
        if (flushNow || inBroadcast > 1 || part.parts >= MAX_PARTS || now - part.firstSeen > PART_WINDOW_MS) {
            flush(sender);
        }
    }

    private static void flush(String sender) {
        PendingPart part = PENDING.remove(sender);
        if (part == null) return;
        String body = part.body;
        if (body == null || body.trim().isEmpty()) return;

        String fingerprint = sender + "|" + body.hashCode();
        synchronized (RECENT) {
            if (RECENT.contains(fingerprint)) {
                XposedEntry.log("dup skip: sender=" + sender);
                return;
            }
            RECENT.add(fingerprint);
            if (RECENT.size() > RECENT_MAX) {
                Iterator<String> it = RECENT.iterator();
                it.next();
                it.remove();
            }
        }

        // 日志（PICKUPDEBUG 双通道）
        String snippet = body.length() > 300 ? body.substring(0, 300) + "..." : body;
        XposedEntry.log("SMS: sender=" + sender + " | parts=" + part.parts
                + " | body=" + snippet);

        // 转发给模块自身进程：提取 → 去重 → 写待办 → 通知
        // 主通道：ContentResolver.call（Provider 唤醒，不受 MIUI 冻结/进程冷启动限制）
        // 兜底：sendBroadcast（App 进程已运行时可用）
        try {
            android.content.Context ctx = obtainContext();
            if (ctx != null) {
                android.os.Bundle extras = new android.os.Bundle();
                extras.putString("sender", sender);
                extras.putString("body", body);
                extras.putLong("ts", System.currentTimeMillis());
                android.os.Bundle result = ctx.getContentResolver().call(
                        android.net.Uri.parse("content://io.github.okaidev.pickupcode.provider"),
                        "onSms", null, extras);
                XposedEntry.log("EVENT dispatched (provider), result=" + result);
            } else {
                XposedEntry.log("EVENT dispatch skipped: no app context");
            }
        } catch (Throwable t) {
            XposedEntry.log("EVENT provider dispatch err: " + t);
            try {
                android.content.Context ctx = obtainContext();
                if (ctx != null) {
                    android.content.Intent i = new android.content.Intent("io.github.okaidev.pickupcode.action.ON_SMS");
                    i.setPackage("io.github.okaidev.pickupcode");
                    i.putExtra("sender", sender);
                    i.putExtra("body", body);
                    i.putExtra("ts", System.currentTimeMillis());
                    ctx.sendBroadcast(i);
                    XposedEntry.log("EVENT dispatched (broadcast fallback)");
                }
            } catch (Throwable t2) {
                XposedEntry.log("EVENT broadcast fallback err: " + t2);
            }
        }
    }

    private static void cleanupPending(long now) {
        Iterator<Map.Entry<String, PendingPart>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingPart> e = it.next();
            if (now - e.getValue().firstSeen > PART_WINDOW_MS) {
                flush(e.getKey());
            }
        }
    }
}
