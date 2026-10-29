package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知拦截双通道（v2.9.0）：
 *
 * 原理：在 system_server（android 域，已在作用域内）Hook NotificationManagerService 的
 * enqueueNotificationInternal——全系统所有 App 的通知都必经此点，无需"通知使用权"权限，
 * 无保活问题，不受第三方后台冻结影响。
 *
 * 策略：
 *  1) 包名白名单初筛：只处理快递/电商 App 的通知（菜鸟/京东/淘宝/拼多多/丰巢/顺丰等），
 *     杜绝全量扫描的性能与误报面；
 *  2) 文本拼合（title + text + bigText）后喂给 PickupExtractor 动态规则引擎；
 *  3) 提取成功 → 与短信通道共用「码+地点」指纹去重 → 写入待办（与短信双通道无缝合并）；
 *  4) 提取为空但含特征词 → 记入疑似漏抓错题本（与短信同一闭环）；
 *  5) 开关：设置页「通知取件提取」（默认关；开启后 Hook 生效，无需重启）。
 *     开关状态经 /data/local/tmp/pickup_sqlite/enable_noti_hook 标志文件同步
 *     （system_server 读不到模块 SharedPreferences，与冻结免疫直写同一套机制）。
 */
public class NotiHook {

    private static final String TAG = "PICKUPDEBUG";
    private static final String FLAG_FILE = "/data/local/tmp/pickup_sqlite/enable_noti_hook";

    /**
     * 快递/电商通知白名单（v1：主流大厂，按需热扩）
     * v3.1.0：改为 public 常量，供 ProcessSync 同步给系统进程，并支持用户在设置页追加包名。
     */
    public static final String[] BUILTIN_PKGS = {
            "com.cainiao.wireless",     // 菜鸟
            "com.cainiao.guoguo",       // 菜鸟裹裹
            "com.jingdong.app.mall",    // 京东
            "com.taobao.taobao",        // 淘宝
            "com.xunmeng.pinduoduo",    // 拼多多
            "com.sf.cdsibm",            // 顺丰
            "com.fcbox.hiveconsumer",   // 丰巢（v6.2 新增）
            // v4.8：设备互联。小米设备之间会互相中转通知——A 设备收到快递短信，
            // B 设备通过「设备互联」收到同样内容的通知，这里也要读取，
            // 这样 B 设备同样能把取件码写进待办。
            // 非小米设备没有这个包，不影响运行（白名单只按包名匹配，未安装即永不命中）。
            "com.xiaomi.mi_connect_service"  // 设备互联
    };

    /**
     * 包名 → 展示名（供设置页「多平台」弹窗列出支持的应用）
     * v6.1：新增。未收录的包名直接显示包名本身，不会漏。
     */
    public static final java.util.Map<String, String> PKG_LABELS;
    static {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("com.cainiao.wireless", "菜鸟");
        m.put("com.cainiao.guoguo", "菜鸟裹裹");
        m.put("com.jingdong.app.mall", "京东");
        m.put("com.taobao.taobao", "淘宝");
        m.put("com.xunmeng.pinduoduo", "拼多多");
        m.put("com.sf.cdsibm", "顺丰速运");
        m.put("com.fcbox.hiveconsumer", "丰巢");
        m.put("com.xiaomi.mi_connect_service", "设备互联");
        PKG_LABELS = java.util.Collections.unmodifiableMap(m);
    }

    /** 当前生效的通知白名单快照（内置 + 用户追加） */
    public static java.util.List<String> currentPkgs() {
        synchronized (PKG_WHITELIST) {
            return new java.util.ArrayList<>(PKG_WHITELIST);
        }
    }

    /**
     * 运行时白名单 = 内置 + 用户追加。
     * v6.2：改用 LinkedHashSet —— 原 List 在用户重复填同一包名时会累积重复项，
     * 白名单越滚越长（虽不影响匹配结果，但 currentPkgs() 展示会重复）。
     */
    private static final java.util.LinkedHashSet<String> PKG_WHITELIST =
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(BUILTIN_PKGS));

    /** 模块入口调用：在 system_server 注册通知管线钩子 */
    public static void install(ClassLoader sysCl) {
        try {
            // v3.0.0：system_server 冷启动时先加载用户规则（从 root 同步文件读）
            // v3.1.0：同时加载「官方规则集 + 黑名单/模板偏好」快照（ProcessSync），
            // 否则通知通道会用编译内置旧规则跑，出现「短信已修好、通知还在漏抓」的组合债
            try {
                ProcessSync.State st = ProcessSync.load();
                PickupExtractor.initForSystemProcess(st != null ? st.rulesJson : null);
                PickupExtractor.loadUserRules(null, UserRules.loadForSystemProcess());
                if (st != null && st.notiPkgs != null && !st.notiPkgs.trim().isEmpty()) {
                    synchronized (PKG_WHITELIST) {
                        PKG_WHITELIST.clear();
                        for (String p : st.notiPkgs.split("[,，\\s]+")) {
                            String t = p.trim();
                            if (!t.isEmpty()) PKG_WHITELIST.add(t);   // Set 天然去重
                        }
                    }
                }
                XposedBridge.log(TAG + ": user rules loaded: " + PickupExtractor.userRuleCount()
                        + " / official rules v" + PickupExtractor.getActiveRulesVersion());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": load rules skipped: " + t);
            }

            Class<?> nms = XposedHelpers.findClass(
                    "com.android.server.notification.NotificationManagerService", sysCl);
            XposedBridge.hookAllMethods(nms, "enqueueNotificationInternal", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        handleEnqueue(param);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": NotiHook err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": NotiHook installed on NotificationManagerService");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NotiHook install FAILED: " + t);
        }
    }

    /** 单条通知处理：白名单 → 文本拼合 → 引擎提取 → 指纹去重 → 转发写入 */
    private static void handleEnqueue(XC_MethodHook.MethodHookParam param) {
        try {
            Object[] args = param.args;
            // 参数形态：enqueueNotificationInternal(String pkg, String opPkg, int uid, ...)，逐 ROM 稳定
            if (args == null || args.length < 10) return;
            String pkg = (String) args[0];
            if (pkg == null || !isWhitelisted(pkg)) return;
            if (!isFlagOn()) return;

            // 取 Notification 对象（倒数第 2/3 个参数位置因版本而异，稳妥做法：全参数扫）
            Notification n = findNotification(args);
            if (n == null) return;

            String text = buildText(n);
            if (text == null || text.isEmpty()) return;
            // v3.0.0：通知通道没有真实发送号码，用「通知:包名」作为匹配依据，
            // 用户若对通知来源做了号码规则，可用包含匹配（如包名片段）覆盖
            // ⚠️ 本方法运行在 system_server 多线程环境，sender 必须显式传参，不可存全局
            String notiSender = "通知:" + pkg;

            // v3.2.0 加固：通知通道不再用 hasFeatureWords 做前置门禁。
            //
            // 原因（Issue 实测）：白名单里的电商 App 通知形态与短信差别很大——
            //   · 「您的包裹已到站，点击查看详情」：有特征词、没有码
            //   · 「取件码 4-3-5120」：有码、可能没有特征词（标题是 App 名）
            //   · 拼多多/淘宝多用 MessagingStyle，正文分散在多条 message 里
            // 前置门禁会把第二类直接拦掉，连错题本都进不去，表现为
            // 「用户明明收到通知，App 却毫无反应」。
            //
            // 现在改为：白名单命中即无条件尝试提取。
            // 安全性不受影响——extract() 内部仍有 Tier0 特征门禁 + 四层判定 +
            // 语义排除（URL/手机号/单号/量词/时间），空结果不会写入任何待办。
            List<String> codes = PickupExtractor.extract(notiSender, text);
            if (codes.isEmpty()) {
                // 提取为空：仅在「含特征词」时才记错题本，避免把纯广告通知也记进去
                if (PickupExtractor.hasFeatureWords(notiSender, text)) {
                    NotiRelay.relayMissed(param, pkg, text);
                }
                return;
            }
            // 提取成功：转发模块 App（与短信通道共用 TodoWriter.handle 的去重与写入）
            NotiRelay.relayHit(param, pkg, text, codes);
        } catch (Throwable ignored) { }
    }

    private static boolean isWhitelisted(String pkg) {
        return PKG_WHITELIST.contains(pkg);
    }

    /** 标志文件开关（默认关；设置页经 su 写入 1/0 同步） */
    private static boolean isFlagOn() {
        try {
            java.io.File f = new java.io.File(FLAG_FILE);
            if (!f.exists()) return false;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            return line != null && line.trim().equals("1");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在参数数组里找 Notification 对象 */
    private static Notification findNotification(Object[] args) {
        for (Object a : args) {
            if (a instanceof Notification) return (Notification) a;
        }
        return null;
    }

    /**
     * 拼合通知全部可见文本
     *
     * v3.2.0 加固：此前只取 TITLE / TEXT / BIG_TEXT 三个字段，导致「原文能识别、
     * 实际收不到」——电商 App（拼多多/淘宝/京东）大量使用 MessagingStyle 与
     * 多行列表，正文根本不在前三个字段里：
     *   - EXTRA_TEXT_LINES：InboxStyle 多行（「取件码 xxx」常在第二行）
     *   - EXTRA_SUB_TEXT / EXTRA_SUMMARY_TEXT：副标题与摘要
     *   - EXTRA_INFO_TEXT：通知底部补充信息
     *   - MessagingStyle.messages[]：聊天式通知，正文逐条在 Person 的 message 里
     *   - EXTRA_TEMPLATE：部分 ROM 把模板名塞这里，一并纳入便于排查
     * 全部拼进来后由提取引擎自行判断，不会因为多字段而误报（引擎有语义排除）。
     */
    private static String buildText(Notification n) {
        StringBuilder sb = new StringBuilder();
        try {
            Bundle extras = n.extras;
            if (extras == null) return null;

            appendCs(sb, extras.getCharSequence(Notification.EXTRA_TITLE));
            appendCs(sb, extras.getCharSequence(Notification.EXTRA_TEXT));
            appendCs(sb, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            appendCs(sb, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            appendCs(sb, extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
            appendCs(sb, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));

            // InboxStyle 多行
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence l : lines) appendCs(sb, l);
            }

            // MessagingStyle：电商 App 最常用的样式，正文在每条 message 里
            // 注意：extractMessagingStyleFromNotification 是 @hide API，编译期不可直接调用，
            // 必须走反射；失败则静默跳过（其它字段通常已覆盖同一份文本）
            try {
                java.lang.reflect.Method m = Notification.MessagingStyle.class
                        .getDeclaredMethod("extractMessagingStyleFromNotification", Notification.class);
                m.setAccessible(true);
                Object ms = m.invoke(null, n);
                if (ms instanceof Notification.MessagingStyle) {
                    for (Notification.MessagingStyle.Message msg
                            : ((Notification.MessagingStyle) ms).getMessages()) {
                        if (msg != null) appendCs(sb, msg.getText());
                    }
                }
            } catch (Throwable ignored) { }

        } catch (Throwable ignored) { }
        return sb.toString().trim();
    }

    private static void appendCs(StringBuilder sb, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString().trim();
        if (s.isEmpty()) return;
        // 去重：MessagingStyle 的 message 常与 TEXT/BIG_TEXT 重复
        if (sb.indexOf(s) >= 0) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(s);
    }
}
