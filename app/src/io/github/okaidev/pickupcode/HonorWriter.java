package io.github.okaidev.pickupcode;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 荣耀 MagicOS 的待办写入器（v4.1.0）。
 *
 * <p><b>真机结论（HONOR（MagicOS 11 / Android 17）真机）</b>：
 * <ul>
 *   <li>待办库是 {@code com.android.providers.calendar/databases/task.db}，表 {@code Tasks}；</li>
 *   <li>荣耀<b>有</b>待办 Provider：authority 是 {@code com.android.calendar.task}
 *       （类 {@code .task.db.TaskProvider}），只要求 {@code READ/WRITE_CALENDAR}，
 *       而且是 exported —— 所以第三方可以直接写；</li>
 *   <li>它的 {@code insert} 内部会 {@code ContentResolver.notifyChange(...)}，
 *       <b>这正是桌面小组件刷新的触发点</b>。绕过 Provider 直接改 SQL 时没人发这个通知，
 *       于是「日历里看得到、小组件要等 30 分钟或手动建一条才更新」（真机反馈）。</li>
 * </ul>
 *
 * <p><b>所以顺序是</b>：Provider 优先（有通知、有搜索索引、行由系统自己补全）；
 * Provider 不可用（没给日历权限 / 被裁掉）才退回 {@code su sqlite3} 直写，
 * 直写后再补一次 {@link ContentResolver#notifyChange} 把小组件叫醒。
 */
public final class HonorWriter {

    private static final String TAG = "PICKUPDEBUG";

    /** 荣耀待办 Provider（TaskProvider），路径 /tasks */
    private static final String TASKS_AUTHORITY = "com.android.calendar.task";
    private static final Uri TASKS_URI = Uri.parse("content://" + TASKS_AUTHORITY + "/tasks");

    /** 执行一段 SQL 并返回 stdout 的能力 */
    public interface Runner {
        String run(String sql);
    }

    /** 按库路径取一个 Runner（模块进程 / 系统进程各有一套实现） */
    public interface RunnerFactory {
        Runner forDb(String dbPath);
    }

    /** 库路径 → 选中的表结构。同一进程里同一张库的表结构不会变 */
    private static final ConcurrentHashMap<String, HonorSql.Schema> SCHEMA_CACHE =
            new ConcurrentHashMap<>();

    /** 最近一次写入/标记的结论（诊断报告用，不含短信正文） */
    private static volatile String lastDiag = "";

    private HonorWriter() { }

    public static String getLastDiag() { return lastDiag; }

    // ==================== 对外入口 ====================

    /** 新增一条待办（模块进程）：Provider 优先，失败回落 root 直写 */
    public static boolean write(Context ctx, String backend, String content, String title) {
        if (content == null || content.trim().isEmpty()) return false;
        String providerWhy;
        if (Honor.hasCalendarPermission(ctx)) {
            try {
                if (insertViaProvider(ctx, content)) {
                    lastDiag = "OK（待办 Provider，已通知系统刷新）";
                    Log.i(TAG, "HONOR write: " + lastDiag);
                    return true;
                }
                providerWhy = "待办 Provider 未接受这条记录";
            } catch (Throwable t) {
                providerWhy = "待办 Provider 失败: " + t;
            }
        } else {
            providerWhy = "未授予日历权限";
        }
        boolean ok = writeWithFactory(
                db -> sql -> TodoWriter.runSqlForPath(ctx, backend, db, sql), content);
        if (ok) {
            // 直写不会触发 ContentObserver：补一次变更通知，桌面小组件才会跟着刷新
            notifyTasksChanged(ctx);
        } else {
            lastDiag = providerWhy + "；" + lastDiag;
        }
        return ok;
    }

    /** 系统进程（冻结兜底）用：调用方自带 su 通道，只走直写 */
    public static boolean writeWithFactory(RunnerFactory f, String content) {
        if (f == null || content == null || content.trim().isEmpty()) return false;
        StringBuilder tried = new StringBuilder();
        for (String db : Honor.todoDbCandidates()) {
            HonorSql.Schema s = schema(f, db);
            String sql = HonorSql.insertSql(s, content, Honor.PKG_CALENDAR);
            if (sql == null) {
                tried.append(db).append("：").append(s.describe()).append("；");
                continue;
            }
            String out = f.forDb(db).run(".timeout 5000\n" + sql);
            if (out == null) {
                // v4.1.0：这里原来只写「su 通道不可用」，把真正的失败原因
                // （NOT NULL constraint failed / no such column / database is locked）
                // 全吃掉了——真机上就是因此白查了一轮。改成带上 su/sqlite 的原始输出。
                tried.append(db).append("：").append(TodoWriter.getLastWriteDiag()).append("；");
                continue;
            }
            if (out.contains(HonorSql.OK)) {
                lastDiag = "OK（su 直写 " + db + " / " + s.table + "）";
                Log.i(TAG, "HONOR write: " + lastDiag);
                return true;
            }
            tried.append(db).append("：")
                    .append(out.contains(HonorSql.NOOP) ? "INSERT 影响 0 行" : tail(out)).append("；");
        }
        lastDiag = "直写未生效 → " + tried;
        Log.w(TAG, "HONOR write: " + lastDiag);
        return false;
    }

    /** 标记已取件（模块进程）：Provider 优先，失败回落直写 */
    public static boolean markDone(Context ctx, String backend, String code) {
        if (code == null || code.trim().isEmpty()) return false;
        if (Honor.hasCalendarPermission(ctx)) {
            try {
                ContentValues v = new ContentValues();
                v.put("complete", 1);
                v.put("date_completed", System.currentTimeMillis());
                v.put("modify_time", System.currentTimeMillis());
                v.put("dirty", 1);
                int n = ctx.getContentResolver().update(TASKS_URI, v,
                        "title LIKE ? AND complete=0 AND delete_flag=0",
                        new String[]{"%" + code + "%"});
                if (n > 0) {
                    lastDiag = "OK（待办 Provider 标记完成 " + n + " 条）";
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "HONOR markDone provider: " + t);
            }
        }
        boolean ok = markDoneWithFactory(
                db -> sql -> TodoWriter.runSqlForPath(ctx, backend, db, sql), code);
        if (ok) notifyTasksChanged(ctx);
        return ok;
    }

    public static boolean markDoneWithFactory(RunnerFactory f, String code) {
        if (f == null || code == null || code.trim().isEmpty()) return false;
        for (String db : Honor.todoDbCandidates()) {
            HonorSql.Schema s = schema(f, db);
            String sql = HonorSql.doneSql(s, code);
            if (sql == null) continue;
            String out = f.forDb(db).run(".timeout 5000\n" + sql);
            if (out == null) continue;
            // 0 行也算成功：库里可能本来就没有这条待办（用户已手动删掉）
            lastDiag = "OK（标记完成 " + db + " / " + s.table + "）";
            return true;
        }
        lastDiag = "标记完成失败：候选库里没有可用的待办表";
        return false;
    }

    /** 这条取件码是否已经有一条未完成待办（跨进程去重） */
    public static boolean exists(Context ctx, String backend, String code) {
        if (code == null || code.trim().isEmpty()) return false;
        if (Honor.hasCalendarPermission(ctx)) {
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(TASKS_URI, new String[]{"_id"},
                        "title LIKE ? AND complete=0 AND delete_flag=0",
                        new String[]{"%" + code + "%"}, null);
                if (c != null && c.getCount() > 0) return true;
            } catch (Throwable t) {
                Log.w(TAG, "HONOR exists provider: " + t);
            } finally {
                if (c != null) c.close();
            }
        }
        return existsWithFactory(
                db -> sql -> TodoWriter.runSqlForPath(ctx, backend, db, sql), code);
    }

    public static boolean existsWithFactory(RunnerFactory f, String code) {
        if (f == null) return false;
        for (String db : Honor.todoDbCandidates()) {
            HonorSql.Schema s = schema(f, db);
            String sql = HonorSql.existsSql(s, code);
            if (sql == null) continue;
            String out = f.forDb(db).run(sql);
            if (out != null && out.trim().matches("\\d+") && !out.trim().equals("0")) return true;
        }
        return false;
    }

    /**
     * 是否存在「未删除」的待办（不论是否已完成）。
     *
     * <p>与 {@link #exists} 的区别：那个问的是「还有没有没取件的」，
     * 用于跨进程去重；这个问的是「这条待办还在不在库里」，用于判断
     * 「同一条短信重发时要不要重新写一条」——用户已经取件（complete=1）就不该再冒出来，
     * 但用户删掉了（delete_flag=1）就应该重新写。
     */
    public static boolean existsAlive(Context ctx, String backend, String code) {
        if (code == null || code.trim().isEmpty()) return false;
        if (Honor.hasCalendarPermission(ctx)) {
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(TASKS_URI, new String[]{"_id"},
                        "title LIKE ? AND delete_flag=0",
                        new String[]{"%" + code + "%"}, null);
                if (c != null && c.getCount() > 0) return true;
            } catch (Throwable t) {
                Log.w(TAG, "HONOR existsAlive provider: " + t);
            } finally {
                if (c != null) c.close();
            }
        }
        // 没日历权限时退回「未完成」判断：宁可少写一条，也不要重复写
        return exists(ctx, backend, code);
    }

    /**
     * 体检用：把真实库/表/列写进报告。
     *
     * <p>荣耀侧没有稳定文档，出问题时就靠这一行定位——所以它把**两个候选库**
     * 都探一遍，而不是只报「当前后端指向的那个」。
     */
    public static String schemaSummary(Context ctx, String backend) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, HonorSql.Schema> e : probeAll(ctx, backend).entrySet()) {
            if (sb.length() > 0) sb.append("；");
            sb.append(shortName(e.getKey())).append(" → ").append(e.getValue().describe());
        }
        return sb.toString();
    }

    /**
     * 逐个候选库探测表结构（按候选顺序返回，库路径 → Schema）。
     *
     * <p>体检与写入共用这一份结果：体检要「把真实列名打出来」，
     * 写入要知道「往哪张表写」。两边各探一次会让 su 调用翻倍，没必要。
     */
    public static java.util.Map<String, HonorSql.Schema> probeAll(Context ctx, String backend) {
        java.util.LinkedHashMap<String, HonorSql.Schema> out = new java.util.LinkedHashMap<>();
        for (String db : Honor.todoDbCandidates()) {
            out.put(db, schema(d -> sql -> TodoWriter.runSqlForPath(ctx, backend, d, sql), db));
        }
        return out;
    }

    private static String shortName(String db) {
        int i = db == null ? -1 : db.lastIndexOf('/');
        return i >= 0 ? db.substring(i + 1) : String.valueOf(db);
    }

    // ==================== 待办 Provider 路径 ====================

    /**
     * 走 {@code content://com.android.calendar.task/tasks} 插入。
     *
     * <p>这条路的两个好处，直写都拿不到：
     * <ol>
     *   <li>Provider 内部 {@code notifyChange} → 日历 App 与<b>桌面小组件</b>立刻刷新；</li>
     *   <li>Provider 会把这条待办同步进系统搜索索引（{@code insertTaskToMMSearch}）。</li>
     * </ol>
     * 行字段仍然自己填（和直写同一套），填完再按 uuid 查回来确认真的落库——
     * Provider 悄悄丢列时不会报错，只会「插入成功但查不到」。
     */
    private static boolean insertViaProvider(Context ctx, String content) {
        ContentResolver cr = ctx.getContentResolver();
        String uuid = HonorSql.dollarUuid();
        long now = System.currentTimeMillis();
        long dayStart = startOfToday();

        ContentValues v = new ContentValues();
        v.put("uuid", uuid);
        v.put("title", content);
        v.put("body", content);
        v.put("search_content", content);
        v.put("html_content", HonorSql.htmlOf(content));
        v.put("message", HonorSql.messageOf(content));
        v.put("complete", 0);
        v.put("date_completed", 0);
        v.put("delete_flag", 0);
        v.put("delete_time", 0);
        v.put("dirty", 1);
        v.put("isAllDay", 1);
        v.put("reminder_type", 1);
        v.put("importance", 0);
        v.put("time_type", 0);
        v.put("has_attachment", 0);
        v.put("create_time", now);
        v.put("modify_time", now);
        v.put("due_date", dayStart);
        v.put("custom_app_package", "isMainUser:true,original:com.hihonor.calendar");

        // 挂到已有待办的同一个文件夹/账号下（否则可能落进"未分类"或被过滤掉）
        String[] fa = sampleFolderAccount(cr);
        if (fa[0] != null) v.put("folder_uuid", fa[0]);
        if (fa[1] != null) v.put("account_uuid", fa[1]);

        Uri u = cr.insert(TASKS_URI, v);
        if (u == null) return false;
        // 校验：Provider 可能把 uuid 换成自己的，也可能悄悄丢列。先按返回的 Uri 查，
        // 查不到再按标题查——两条都查不到才认为失败，避免「Provider 写成功又回落直写」造成重复待办。
        Cursor c = null;
        try {
            c = cr.query(u, new String[]{"_id"}, null, null, null);
            if (c != null && c.getCount() > 0) return true;
        } catch (Throwable t) {
            Log.w(TAG, "HONOR verify by uri: " + t);
        } finally {
            if (c != null) c.close();
        }
        c = null;
        try {
            c = cr.query(TASKS_URI, new String[]{"_id"}, "title=?", new String[]{content}, null);
            return c != null && c.getCount() > 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 从库里第一条待办抄 folder_uuid / account_uuid（没有就返回 null） */
    private static String[] sampleFolderAccount(ContentResolver cr) {
        String[] out = {null, null};
        Cursor c = null;
        try {
            c = cr.query(TASKS_URI, new String[]{"folder_uuid", "account_uuid"},
                    "folder_uuid IS NOT NULL", null, "_id ASC");
            if (c != null && c.moveToFirst()) {
                out[0] = c.isNull(0) ? null : c.getString(0);
                out[1] = c.isNull(1) ? null : c.getString(1);
            }
        } catch (Throwable t) {
            Log.w(TAG, "HONOR sampleFolderAccount: " + t);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** 直写之后补一次变更通知：日历 App 与桌面小组件靠它刷新 */
    private static void notifyTasksChanged(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            cr.notifyChange(TASKS_URI, null);
            // 少数版本的小组件观察的是根 URI，一起通知，代价只有一次 Binder 调用
            cr.notifyChange(Uri.parse("content://" + TASKS_AUTHORITY), null);
        } catch (Throwable t) {
            Log.w(TAG, "HONOR notifyChange: " + t);
        }
    }

    private static long startOfToday() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    // ==================== root 直写路径 ====================

    /** 取（并缓存）某个库的表结构；取不到时返回空 Schema（不缓存，下次再探） */
    private static HonorSql.Schema schema(RunnerFactory f, String db) {
        HonorSql.Schema cached = SCHEMA_CACHE.get(db);
        if (cached != null) return cached;
        String out = f.forDb(db).run(HonorSql.PROBE_SQL);
        List<HonorSql.Schema> all = HonorSql.parseAll(out);
        HonorSql.Schema picked = all.isEmpty()
                ? new HonorSql.Schema(HonorSql.TABLE_CANDIDATES[0],
                new java.util.LinkedHashSet<String>(), new java.util.LinkedHashSet<String>())
                : all.get(0);
        if (picked.usable()) SCHEMA_CACHE.put(db, picked);
        Log.i(TAG, "HONOR schema(" + db + "): " + picked.describe());
        return picked;
    }

    private static String tail(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= 160 ? s : s.substring(s.length() - 160);
    }
}
