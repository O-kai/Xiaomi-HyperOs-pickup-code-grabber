package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;


/** Configuration snapshots travel through the framework service, without root or public files. */
public class ProcessSync {

    private static final String TAG = "PICKUPDEBUG";
    private static final String PREFS = "dedup";

    /** 黑名单默认值（App 偏好读不到时的兜底；唯一来源在 TodoWriter） */
    public static final String DEFAULT_BLACKLIST = TodoWriter.DEFAULT_BLACKLIST;

    /** 系统进程侧只读快照 */
    public static class State {
        public String rulesJson = "";      // 当前生效的官方规则集 JSON（空 = 用编译内置）
        public String blacklist = "";      // 逗号分隔（空 = 用默认）
        public int todoMode = 1;           // 0=极简 1=完整 2=自定义
        public String customTpl = "";      // 自定义模板
        public String notiPkgs = "";       // 逗号分隔的通知白名单（含内置 + 用户追加）
        /**
         * v3.1.0：写入目标后端。
         * 此前漏了这个字段，冻结兜底通道只能用 NotesBackend.propsBackend() 按 ROM 属性猜后端——
         * 用户手动把写入目标改成与 ROM 推断不同的那个（如 ColorOS 上选「ColorOS 便签」）时，
         * 主通道写 nearme_note.db、兜底通道却写 tasks.db，数据分裂在两个 App 里。
         * 空 = 让兜底通道按原逻辑推断。
         */
        public String backend = "";
        /**
         * v4.0.3：多选之后的待办目标，逗号分隔（可能 0~2 个）。
         *
         * <p>为什么不能只留 {@link #backend}：ColorOS 上用户可以同时勾「日历待办」
         * 和「便签笔记」，兜底通道只写一个就会漏掉另一个。
         * 空串 = 没有任何待办目标（详见 {@link #noTodo}）。
         */
        public String backends = "";
        /**
         * v4.0.3 第二轮：用户把系统待办全关了，只留「软件内部记录」。
         *
         * <p><b>必须有这个独立标记</b>：{@code backends} 空串既可能是
         * 「用户一个待办都没勾」，也可能是「老版本快照里没有这个字段」。
         * 只看空串会让兜底通道把前者当成后者，回落到 ROM 推断目标，
         * 于是用户明明关掉了待办，冻结兜底却在偷偷往里写。
         */
        public boolean noTodo;
        public boolean notiHook;
        public boolean sysDirectWrite;
        public boolean appParcelEnabled;
    }

    private static final java.util.concurrent.ExecutorService SYNC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pickup-config");
                t.setDaemon(true);
                return t;
            });

    public static void pushThrottled(Context ctx) { push(ctx); }
    public static void markPending(Context ctx) { }
    public static void flushIfReady(Context ctx) { push(ctx); }

    /** Serial writes ensure an older snapshot cannot overwrite a newer setting. */
    public static void push(Context ctx) {
        if (ctx == null) return;
        SYNC.execute(() -> {
            try {
                String json = buildJson(ctx);
                if (json != null && !HookConfig.publish(json))
                    Log.i(TAG, "Configuration awaiting framework service");
            } catch (RuntimeException e) {
                Log.w(TAG, "Framework configuration sync failed: " + e.getClass().getSimpleName());
            }
        });
    }

    private static String buildJson(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            // 官方规则：取当前内存里生效的那份（可能是热更来的，也可能是 assets/内置）
            try {
                java.lang.reflect.Field f = PickupExtractor.class
                        .getDeclaredField("activeRules");
                f.setAccessible(true);
                Object r = f.get(null);
                if (r != null) {
                    java.lang.reflect.Method m = r.getClass().getMethod("toJson");
                    Object tj = m.invoke(r);
                    o.put("rulesJson", tj != null ? tj.toString() : "");
                }
            } catch (Throwable t) {
                Log.w(TAG, "ProcessSync: read activeRules failed: " + t);
            }
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            o.put("blacklist", sp.getString("blacklist", ""));
            o.put("todoMode", sp.getInt("todo_mode", 1));
            o.put("customTpl", sp.getString("todo_custom", ""));
            o.put("notiPkgs", notiPkgsOf(ctx));
            // v3.1.0：写入目标也进快照，保证主通道与冻结兜底通道写同一个库
            o.put("backend", sp.getString("backend_override", ""));
            // v4.0.3：多选目标（0~N 个）。空串表示「只写小组件」。
            StringBuilder bs = new StringBuilder();
            boolean noTodo = false;
            if (ctx != null) {
                for (String t : NotesBackend.todoTargets(ctx)) {
                    if (bs.length() > 0) bs.append(',');
                    bs.append(t);
                }
                noTodo = bs.length() == 0;
            }
            o.put("backends", bs.toString());
            // 空串有两种含义，必须单独标出来（见 State.noTodo 的说明）
            o.put("noTodo", noTodo);
            o.put("appParcelEnabled", sp.getBoolean(TodoProvider.APP_PARCEL_PREF, false));
            o.put("notiHook", sp.getBoolean("noti_hook", false));
            o.put("sysDirectWrite", sp.getBoolean("sys_direct_write", false));
            o.put("userRules", UserRules.systemSnapshot(ctx));
            return o.toString();
        } catch (Throwable t) {
            Log.w(TAG, "ProcessSync: buildJson failed: " + t);
            return null;
        }
    }

    /** 通知白名单 = 内置固定项 + 用户追加项（v3.1.0 P4：让用户规则/自定义能覆盖更多 App） */
    public static String notiPkgsOf(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (String p : NotiHook.BUILTIN_PKGS) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        if (ctx != null) {
            String extra = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("noti_extra_pkgs", "");
            if (extra != null && !extra.trim().isEmpty()) {
                for (String p : extra.split("[,，\\s]+")) {
                    String t = p.trim();
                    if (t.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    /** 供设置页保存用户追加的通知包名 */
    public static void setExtraNotiPkgs(Context ctx, String raw) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("noti_extra_pkgs", raw == null ? "" : raw.trim()).apply();
        push(ctx);
    }

    public static String getExtraNotiPkgs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("noti_extra_pkgs", "");
    }

    // ==================== 系统进程：读取快照 ====================

    /** Read-only preferences are supplied by the framework in each target process. */
    public static State load() { return decode(HookConfig.snapshot()); }

    static State decode(String json) {
        State st = new State();
        try {
            if (json.isEmpty()) return st;
            JSONObject o = new JSONObject(json);
            st.rulesJson = o.optString("rulesJson", "");
            st.blacklist = o.optString("blacklist", "");
            st.todoMode = o.optInt("todoMode", 1);
            st.customTpl = o.optString("customTpl", "");
            st.notiPkgs = o.optString("notiPkgs", "");
            st.backend = o.optString("backend", "");
            st.backends = o.optString("backends", "");
            st.noTodo = o.optBoolean("noTodo", false);
            st.appParcelEnabled = o.optBoolean("appParcelEnabled", false);
            st.notiHook = o.optBoolean("notiHook", false);
            st.sysDirectWrite = o.optBoolean("sysDirectWrite", false);
        } catch (Exception e) { Log.w(TAG, "Invalid framework configuration snapshot"); }
        return st;
    }
}
