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
    /**
     * v3.1.0：PENDING 的锁。
     * 此前它与 RECENT 并排声明、RECENT 有 synchronized 而 PENDING 没有——
     * 同一个类里两个容器，一个加锁一个不加，是明显的遗漏。
     * 实际后果：com.android.mms 里「服务后台线程」(SmsReceiverService.onReceive)
     * 与「主线程」(各 *.onReceive) 可以真正并发进来：
     *   · part.body += body 是非原子读-改-写 → 丢片段
     *   · 并发 put/get 可能损坏 LinkedHashMap 结构
     *   · cleanupPending 迭代器与 flush 里的 remove 并发 → ConcurrentModificationException，
     *     而该异常被 XposedEntry 的 catch(Throwable) 吞掉 → 触发本次的短信连缓冲都没进就被丢弃，
     *     无任何日志，用户只看到「某条短信莫名没抓到」。
     */
    private static final Object PENDING_LOCK = new Object();
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
        // v3.1.0：整个缓冲区操作放进 PENDING_LOCK（理由见字段注释）。
        // 只锁「读+改」这一小段，真正的网络/跨进程派发（flush 内部）放在锁外，
        // 避免长时间持锁——这也是为什么 flush 只做 remove 而不做派发。
        boolean needFlush;
        synchronized (PENDING_LOCK) {
            cleanupPendingLocked(now);

            PendingPart part = PENDING.get(sender);
            if (part == null || now - part.firstSeen > PART_WINDOW_MS) {
                PendingPart np = new PendingPart();
                np.firstSeen = now;
                np.body = body;
                np.parts = 1;
                PENDING.put(sender, np);
            } else {
                // v3.1.0：加换行分隔，避免两条独立短信被裸拼接成一串
                // （如「取件码12-3-4567」+「请凭码取件」→ 粘成 12-3-4567请凭码取件）
                part.body = part.body + "\n" + body;
                part.parts += 1;
            }
            part = PENDING.get(sender);
            // v3.1.0 修复「单段短信永不 flush」（三份独立审计一致认定，唯一一条默认配置下就会丢码的路径）：
            //  原条件里 inBroadcast>1 意味着「单段短信」永远不满足任何一条 → 滞留在 PENDING。
            //  而 cleanupPending 只在【下一条短信到达】时才被调用，当天不再收短信就永久丢失，
            //  且没有任何日志（flush 根本没进），用户完全无法归因。
            //  修法：广播路径下的单段短信（inBroadcast==1）立即处理——分页短信本来就在同一次
            //  广播里被完整拼好（见 handleIntent），不需要再等第二个 Hook 点来凑段数。
            needFlush = flushNow || inBroadcast >= 1 || part.parts >= MAX_PARTS
                    || now - part.firstSeen > PART_WINDOW_MS;
        }
        // 派发放锁外：flush 只从 map 里取走数据，真正的跨进程调用可能耗时
        if (needFlush) flush(sender);
    }

    private static void flush(String sender) {
        PendingPart part;
        synchronized (PENDING_LOCK) {
            part = PENDING.remove(sender);
        }
        if (part == null) return;
        dispatch(part, sender);
    }

    /** 从缓冲区取出一条并派发（去重 + 跨进程转发），调用方负责已把它从 map 里摘走 */
    private static void dispatch(PendingPart part, String sender) {
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
            android.content.Context ctx2 = null;
            try {
                ctx2 = obtainContext();
                if (ctx2 != null) {
                    android.content.Intent i = new android.content.Intent("io.github.okaidev.pickupcode.action.ON_SMS");
                    i.setPackage("io.github.okaidev.pickupcode");
                    i.putExtra("sender", sender);
                    i.putExtra("body", body);
                    i.putExtra("ts", System.currentTimeMillis());
                    ctx2.sendBroadcast(i);
                    XposedEntry.log("EVENT dispatched (broadcast fallback)");
                }
            } catch (Throwable t2) {
                XposedEntry.log("EVENT broadcast fallback err: " + t2);
            }
            // v2.7.0：模块 App 转发失败（被 ColorOS 冻结 / 进程不可唤醒）→ 本进程内 su 直写兜底
            //（默认关闭；需在设置页开启并给系统进程授权 root，见 SystemDirectWriter）
            if (ctx2 != null) {
                SystemDirectWriter.fallback(ctx2, sender, body);
            }
        }
    }

    /**
     * 清理超窗缓冲（调用方必须已持有 PENDING_LOCK）。
     *
     * v3.1.0 修复：此前这里用迭代器边遍历边调 flush（flush 内部 remove），
     * 在下一次 it.next() 时必然抛 ConcurrentModificationException；
     * 而该异常被 XposedEntry 的 catch(Throwable) 吞掉，
     * 表现是「触发本次的短信连缓冲都没进就被丢弃」，且日志里什么都看不到。
     * 注意 hasNext() 不检查 comodification，异常点在 next()，所以更不容易被发现。
     *
     * 现在：先在锁内摘出「到期的 sender 列表」，再在锁外逐个 flush。
     */
    private static void cleanupPendingLocked(long now) {
        java.util.List<String> expired = null;
        for (Map.Entry<String, PendingPart> e : PENDING.entrySet()) {
            if (now - e.getValue().firstSeen > PART_WINDOW_MS) {
                if (expired == null) expired = new java.util.ArrayList<>();
                expired.add(e.getKey());
            }
        }
        if (expired == null) return;
        for (String s : expired) {
            PendingPart p;
            synchronized (PENDING_LOCK) {
                p = PENDING.remove(s);
            }
            if (p == null || p.body == null || p.body.trim().isEmpty()) continue;
            dispatch(p, s);
        }
    }

    /** 过期清理的兜底入口（若被锁外调用则自行加锁） */
    private static void cleanupPending(long now) {
        cleanupPendingLocked(now);
    }
}
