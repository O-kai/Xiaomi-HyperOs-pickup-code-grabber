package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 用户自定义通知来源（包名）的校验与清洗。
 *
 * 【为什么需要这个类】
 * 用户手动填包名是「自由输入」，不加约束会有四类真实问题：
 *
 *  1. 重复/脏输入：同一包名填两遍、用全角逗号或空格分隔、带前后空白。
 *     原实现把原始文本直接存下来，白名单会累积重复项。
 *  2. 拼错但不报错：正则只校验「像不像包名」（xxx.yyy 格式），
 *     写成 com.taobao.taobaoo 也能通过，保存成功却永远匹配不到任何通知，
 *     用户看到的现象是「开关开了、也加了包名，就是不工作」→ 以为软件坏了。
 *  3. 高危包名：加入短信/微信/支付宝后，这些 App 的通知正文
 *     （可能含验证码、转账、私聊）会进入提取流程，违反最小权限原则。
 *  4. 误清空：无二次确认地清掉用户配置。
 *
 * 本类只做「判断与清洗」，不碰存储；调用方拿到结果自己决定提示什么。
 * 注意：全部方法都是纯逻辑（除 isInstalled 需要 Context），可脱机测试。
 */
public class PkgInput {

    /** 包名格式：至少两段，字母数字下划线，段间点分隔 */
    private static final String PKG_REGEX = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+";

    /** 单个包名最大长度（Android 包名实际限制 255，这里收得更紧防误填整段文字） */
    private static final int MAX_LEN = 128;

    /** 一次最多接受多少个自定义包名，防止用户粘贴一大段文本 */
    private static final int MAX_COUNT = 32;

    /**
     * 高危包名：加入这些 App 后，它们的通知正文（可能含验证码/转账/私聊）
     * 会进入提取流程。不强制禁止（可能有正当场景），但保存时必须明确警告。
     *
     * 放在本类而非 NotiHook，是为了让本类不依赖 Xposed（可在普通 JVM 上测试）。
     */
    public static final java.util.Map<String, String> SENSITIVE;
    static {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("com.android.mms", "系统短信");
        m.put("com.tencent.mm", "微信");
        m.put("com.eg.android.AlipayGphone", "支付宝");
        m.put("android", "系统");
        m.put("com.miui.notes", "小米笔记");
        m.put("com.android.providers.telephony", "短信存储");
        SENSITIVE = java.util.Collections.unmodifiableMap(m);
    }

    /** 校验结果 */
    public static class Result {
        /** 清洗后的包名列表（去重、去空、已排序去噪） */
        public final List<String> valid = new ArrayList<>();
        /** 格式非法的原始片段 */
        public final List<String> malformed = new ArrayList<>();
        /** 格式合法但当前设备未安装的包名 */
        public final List<String> notInstalled = new ArrayList<>();
        /** 格式合法但属于高危 App 的包名 */
        public final List<String> sensitive = new ArrayList<>();

        public boolean hasProblem() {
            return !malformed.isEmpty() || !notInstalled.isEmpty() || !sensitive.isEmpty();
        }

        /** 可直接落库的字符串（逗号分隔） */
        public String toStorageString() {
            StringBuilder sb = new StringBuilder();
            for (String p : valid) {
                if (sb.length() > 0) sb.append(',');
                sb.append(p);
            }
            return sb.toString();
        }
    }

    /**
     * 解析用户输入。
     *
     * @param raw      原始文本（可含换行、中英文逗号、空格、分号）
     * @param ctx      用于判断是否已安装；传 null 则跳过安装检查
     * @param checkInstalled 是否检查「已安装」。测试时可传 false 以便脱机运行
     */
    public static Result parse(String raw, Context ctx, boolean checkInstalled) {
        Result r = new Result();
        if (raw == null || raw.trim().isEmpty()) return r;

        // 统一分隔符：换行 / 中英文逗号 / 分号 / 空白
        String[] parts = raw.split("[\\s,，;；、]+");
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (seen.size() >= MAX_COUNT) break;

            if (p.length() > MAX_LEN || !p.matches(PKG_REGEX)) {
                if (!r.malformed.contains(p)) r.malformed.add(p);
                continue;
            }
            if (!seen.add(p)) continue;   // 重复项静默丢弃（不算错误）

            if (SENSITIVE.containsKey(p) && !r.sensitive.contains(p)) {
                r.sensitive.add(p);
            }
            if (checkInstalled && ctx != null && !isInstalled(ctx, p)
                    && !r.notInstalled.contains(p)) {
                r.notInstalled.add(p);
            }
            r.valid.add(p);
        }
        return r;
    }

    /** 设备上是否装了该包名的 App */
    public static boolean isInstalled(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            // 某些 ROM 对查询有限制，取不到时不阻塞用户（返回 true 视为「不确定但放行」）
            return true;
        }
    }

    /**
     * 生成给用户看的问题说明（分条，不带命令式语气）。
     * 返回 null 表示没有任何问题。
     */
    public static String describe(Result r) {
        if (r == null || !r.hasProblem()) return null;
        StringBuilder sb = new StringBuilder();
        if (!r.malformed.isEmpty()) {
            sb.append("这些不是有效的包名，已跳过：\n  ")
              .append(join(r.malformed)).append("\n\n");
        }
        if (!r.notInstalled.isEmpty()) {
            sb.append("这些包名对应的 App 没装在这台手机上，\n")
              .append("可能拼错了（写错了就永远不会生效）：\n  ")
              .append(join(r.notInstalled)).append("\n\n");
        }
        if (!r.sensitive.isEmpty()) {
            sb.append("这些是高危 App，它们的通知正文会被读取，\n")
              .append("可能包含验证码 / 转账 / 私聊内容：\n  ")
              .append(join(r.sensitive)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(l.get(i));
        }
        return sb.toString();
    }
}
