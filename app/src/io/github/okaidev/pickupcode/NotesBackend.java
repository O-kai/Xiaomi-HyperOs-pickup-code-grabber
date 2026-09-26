package io.github.okaidev.pickupcode;

import android.content.Context;
import android.util.Log;

/**
 * 取件码写入后端（v2.7.0 多 ROM 支持，来自社区 ColorOS 适配的上游整合）：
 * 不同 ROM 的「待办」落在完全不同的 App 与私有库里，本类集中管理目标选择与各库的 SQL 模板：
 *
 *  - xiaomi       小米 HyperOS/MIUI：小米笔记 com.miui.notes → todo.db 的 todo 表，
 *                 置顶 = custom_sort_id = MAX+0x100000，标记完成 = is_finish=1。
 *  - coloros_todo ColorOS 16：日历「待办」存系统 CalendarProvider 的 tasks.db Tasks 表
 *                 （/data/data/com.android.providers.calendar/databases/tasks.db），
 *                 content=待办文本；完成 = 写 finish_time（completed 列不使用）。
 *                 注意 ColorOS 16 便签的待办已整体迁移至日历，便签 todo 表为遗留空表不可用；
 *                 官方任务接口 content://com.oplus.task 需平台签名权限，第三方无法调用 → 走 root 直写。
 *  - coloros_note ColorOS 便签置顶笔记（nearme_note.db rich_notes，top_time 置顶），
 *                 需要「便签笔记」而非「日历待办」时手动选用。
 *
 * 后端选择优先级：
 *  1) 用户手动指定（设置页「写入目标」→ SharedPreferences backend_override，模块 App 进程可读）；
 *  2) 自动识别（小米 ROM / 已装小米笔记 → xiaomi；否则 coloros_todo）。
 *  Hook 所在系统进程拿不到模块 SharedPreferences，兜底直写按 ROM 属性自动判定（propsBackend）。
 *
 * 真机侦察 2026-09（一加 9 Pro / ColorOS 16，docs/16-coloros-adaptation.md）：
 *  - tasks.db 字段约定取自日历 App 手动创建的样本：无提醒待办 sort_time≈create_time，
 *    mutators=com.coloros.calendar，create_package=com.coloros.calendar_&lt;ver&gt;_&lt;hash&gt;，timezone=UTC，
 *    完成只写 finish_time（completed 恒为 0）。
 *
 * 本类所有 SQL/路径构建均按「后端字符串」分派（*Of 系列），支持无 Context 调用——
 * 供 SystemDirectWriter（Hook 所在系统进程）在模块 App 被冻结时直写兜底。
 */
public class NotesBackend {

    private static final String TAG = "PICKUPDEBUG";

    public static final String PKG_XIAOMI = "com.miui.notes";
    public static final String PKG_COLOROS_NOTE = "com.coloros.note";
    public static final String PKG_COLOROS_CALENDAR = "com.coloros.calendar";

    public static final String BACKEND_XIAOMI = "xiaomi";
    public static final String BACKEND_COLOROS_TODO = "coloros_todo";
    public static final String BACKEND_COLOROS_NOTE = "coloros_note";

    /** 手动覆盖的 SharedPreferences 名/键（与主配置一致） */
    private static final String PREFS = "dedup";
    private static final String KEY_OVERRIDE = "backend_override";

    /** 检测结果缓存（进程级） */
    private static volatile String cached;

    /** 当前应使用的后端（含用户手动覆盖；模块 App 进程内调用） */
    public static String detect(Context ctx) {
        String b = cached;
        if (b != null) return b;
        synchronized (NotesBackend.class) {
            if (cached != null) return cached;
            cached = resolve(ctx);
            Log.i(TAG, "NotesBackend detect: " + cached);
            return cached;
        }
    }

    /** 设置页切换「写入目标」后调用，使下次 detect() 重新判定 */
    public static void invalidateCache() {
        cached = null;
    }

    private static String resolve(Context ctx) {
        // 1) 用户手动指定优先
        try {
            String o = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_OVERRIDE, "");
            if (BACKEND_XIAOMI.equals(o) || BACKEND_COLOROS_TODO.equals(o) || BACKEND_COLOROS_NOTE.equals(o)) {
                return o;
            }
        } catch (Throwable ignored) { }
        // 2) 自动识别
        return resolveByPackage(ctx);
    }

    /**
     * 无 Context 的后端判定（Hook 所在系统进程用）：纯 ROM 属性推断。
     * 小米 ROM → xiaomi；其余（ColorOS/OPPO 系）→ coloros_todo。
     */
    public static String propsBackend() {
        return isMiuiRom() ? BACKEND_XIAOMI : BACKEND_COLOROS_TODO;
    }

    private static String resolveByPackage(Context ctx) {
        boolean hasMiuiNotes = false;
        try {
            hasMiuiNotes = ctx.getPackageManager().getPackageInfo(PKG_XIAOMI, 0) != null;
        } catch (Throwable t) { }
        return (hasMiuiNotes || isMiuiRom()) ? BACKEND_XIAOMI : BACKEND_COLOROS_TODO;
    }

    /** 用户查看结果应打开的 App 包名（通知「查看待办」跳转用） */
    public static String targetPkg(Context ctx) {
        return targetPkgOf(detect(ctx));
    }

    public static String targetPkgOf(String b) {
        if (BACKEND_COLOROS_NOTE.equals(b)) return PKG_COLOROS_NOTE;
        if (b != null && b.startsWith("coloros")) return PKG_COLOROS_CALENDAR;
        return PKG_XIAOMI;
    }

    /** 直写目标库路径（su sqlite3） */
    public static String dbPath(Context ctx) { return dbPathOf(detect(ctx)); }

    public static String dbPathOf(String b) {
        if (BACKEND_COLOROS_NOTE.equals(b)) {
            return "/data/user/0/" + PKG_COLOROS_NOTE + "/databases/nearme_note.db";
        }
        if (BACKEND_COLOROS_TODO.equals(b)) {
            return "/data/user/0/com.android.providers.calendar/databases/tasks.db";
        }
        return "/data/user/0/" + PKG_XIAOMI + "/databases/todo.db";
    }

    /** 写入前的自愈 chmod（su 子进程 DAC 特权补齐）。ColorOS 下以 root 直写无需放开 */
    public static String chmodCmd(Context ctx) { return chmodCmdOf(detect(ctx)); }

    public static String chmodCmdOf(String b) {
        if (BACKEND_XIAOMI.equals(b)) {
            return "chmod 711 /data/user/0/" + PKG_XIAOMI
                    + "; chmod 771 /data/user/0/" + PKG_XIAOMI + "/databases"
                    + "; chmod 666 /data/user/0/" + PKG_XIAOMI + "/databases/todo.db; ";
        }
        return "";
    }

    /** 新增一条置顶待办/笔记。title 仅 coloros_note 用（列表标题），其余后端忽略 */
    public static String insertSql(Context ctx, String content, String title) {
        return insertSqlOf(detect(ctx), content, title);
    }

    public static String insertSqlOf(String b, String content, String title) {
        String esc = TodoWriter.sqlEscapePub(content);
        String now = "(strftime('%s','now')*1000)";
        if (BACKEND_COLOROS_NOTE.equals(b)) {
            String t = TodoWriter.sqlEscapePub(title);
            String raw = TodoWriter.sqlEscapePub(
                    "<head><meta name=\"feature_list\" content=\"\"></head><div>" + content + "<br></div>");
            return "INSERT INTO rich_notes (local_id, global_id, text, raw_text, html_text, folder_id,"
                    + " timestamp, create_time, update_time, top_time, recycle_time, alarm_time, state, deleted,"
                    + " skin_id, skin_id_pre, recycle_time_pre, alarm_time_pre, extra, version, is_local, is_preset,"
                    + " from_package, web_notes, sysVersion, encrypted, encrypted_pre, encryptSysVersion,"
                    + " attachment_extra, summary_title, summary_content, summary_phone_name, summary_phone_number,"
                    + " summary_attachment, summary_attachment_size, summary_speech_flag, summary_speech_type,"
                    + " summary_show_icon, summary_note_type_flag, is_cover_paint_not_char_count, is_summary_edit,"
                    + " is_paint_pic, summary_version, all_attachment_file_name, attachment_lrc,"
                    + " sys_protocol_version, file_download_time)\nVALUES ("
                    + UUID_SQL + ", " + UUID_SQL + ", '" + esc + "', '" + raw + "', '',"
                    + " '00000000_0000_0000_0000_000000000000',"
                    + " " + now + ", " + now + ", " + now + ", " + now + ", 0, 0, 0, 0,"
                    + " 'color_skin_white', 'color_skin_white', 0, 0,"
                    + " '{\"encryptStatus\":-1,\"featureList\":[],\"moveOutFromPaintFolder\":0,\"pageResults\":[]}',"
                    + " 0, 1, 0, NULL, NULL, NULL, 0, 0, NULL, '[]',"
                    + " '" + t + "', '" + esc + "', '', '', '[]', 0, 0, 0, 0, 0, 0, 0, 0, 3, '', '', 0, 0);\n";
        }
        if (BACKEND_COLOROS_TODO.equals(b)) {
            // 日历待办：字段约定来自日历 App 亲手建的两条样本（无提醒快捷待办）
            return "INSERT INTO Tasks (local_id, global_id, local_global_id, dirty, content, allDay, star,"
                    + " mutators, create_package, create_time, update_time, sort_time, color, timezone,"
                    + " sys_version, force_reminder, canPartiallyUpdate, deleted)\nVALUES ("
                    + UUID_SQL + ", " + UUID_SQL + ", " + UUID_SQL + ", 1,"
                    + " '" + esc + "', 1, 0,"
                    + " 'com.coloros.calendar',"
                    + " 'com.coloros.calendar_10325_7e18a2f30df7f24934d856fca0ba721bc94c728898f30cccbbab398606c921ac',"
                    + " " + now + ", " + now + ", " + now + ", 2, 'UTC', 1, 0, 0, 0);\n";
        }
        return "INSERT INTO todo (content, plain_text, is_finish, list_type, type, category, folder_id, source,"
                + " input_type, remind_type, priority, hide_type, custom_sort_id, sort_id, version, local_status,"
                + " server_status, words_count, create_time, last_modified_time)\nVALUES ('" + esc + "', '" + esc
                + "', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (SELECT COALESCE(MAX(custom_sort_id), 0) FROM todo) + 1048576,"
                + " 0, 1, 0, 0, 0, " + now + ", " + now + ");\n";
    }

    /** 标记已取件：按取件码定位未完成的待办/笔记 */
    public static String markDoneSql(Context ctx, String code) { return markDoneSqlOf(detect(ctx), code); }

    public static String markDoneSqlOf(String b, String code) {
        String esc = TodoWriter.sqlEscapePub(code);
        if (BACKEND_COLOROS_NOTE.equals(b)) {
            return "UPDATE rich_notes SET text = text || '（✅ 已取件）',"
                    + " summary_content = summary_content || '（✅ 已取件）',"
                    + " top_time = 0, update_time = strftime('%s','now')*1000"
                    + " WHERE deleted = 0 AND text LIKE '%" + esc + "%'"
                    + " AND text NOT LIKE '%已取件%';\n";
        }
        if (BACKEND_COLOROS_TODO.equals(b)) {
            return "UPDATE Tasks SET finish_time = strftime('%s','now')*1000,"
                    + " update_time = strftime('%s','now')*1000"
                    + " WHERE deleted = 0 AND finish_time IS NULL AND content LIKE '%" + esc + "%';\n";
        }
        return "UPDATE todo SET is_finish=1, mark_finish_time=strftime('%s','now')*1000 "
                + "WHERE is_finish=0 AND content LIKE '%" + esc + "%';";
    }

    /** 体检/兜底用：目标表里是否已存在包含 code 的未完成待办（进程外去重） */
    public static String existsSqlOf(String b, String code) {
        String esc = TodoWriter.sqlEscapePub(code);
        if (BACKEND_COLOROS_TODO.equals(b)) {
            return "SELECT COUNT(*) FROM Tasks WHERE deleted=0 AND finish_time IS NULL"
                    + " AND content LIKE '%" + esc + "%';";
        }
        if (BACKEND_COLOROS_NOTE.equals(b)) {
            return "SELECT COUNT(*) FROM rich_notes WHERE deleted=0 AND text LIKE '%" + esc + "%'"
                    + " AND text NOT LIKE '%已取件%';";
        }
        return "SELECT COUNT(*) FROM todo WHERE is_finish=0 AND content LIKE '%" + esc + "%';";
    }

    /** 体检用：目标表存在性/count 探针 SQL */
    public static String countProbeSql(Context ctx) { return countProbeSqlOf(detect(ctx)); }

    public static String countProbeSqlOf(String b) {
        if (BACKEND_COLOROS_NOTE.equals(b)) return "SELECT COUNT(*) FROM rich_notes;";
        if (BACKEND_COLOROS_TODO.equals(b)) return "SELECT COUNT(*) FROM Tasks;";
        return "SELECT COUNT(*) FROM todo;";
    }

    /** 体检文案用：目标 App 的通俗名 */
    public static String targetAppName(Context ctx) { return targetAppNameOf(detect(ctx)); }

    public static String targetAppNameOf(String b) {
        if (BACKEND_COLOROS_NOTE.equals(b)) return "ColorOS 便签";
        if (BACKEND_COLOROS_TODO.equals(b)) return "日历待办（com.android.providers.calendar）";
        return "小米笔记";
    }

    /** SQL 内联 UUID v4（SQLite 表达式） */
    private static final String UUID_SQL =
            "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||"
                    + " substr(lower(hex(randomblob(2))),2) || '-' ||"
                    + " substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))),2) || '-' ||"
                    + " lower(hex(randomblob(6)))";

    private static boolean isMiuiRom() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String miui = (String) sp.getMethod("get", String.class).invoke(null, "ro.miui.ui.version.name");
            String miOs = (String) sp.getMethod("get", String.class).invoke(null, "ro.mi.os.version.name");
            return (miui != null && !miui.isEmpty()) || (miOs != null && !miOs.isEmpty());
        } catch (Throwable t) {
            return false;
        }
    }
}
