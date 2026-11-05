package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
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
    /**
     * 多选目标里的「软件内部记录」（v4.0.3 第二轮）。
     *
     * <p><b>它不是第三个后端，而是本地库这一侧的开关</b>：
     * 打开 = 取件码落进 Room 本地库，于是首页顶部出现「待取件」、
     * 桌面小组件也有内容可显示；关闭 = 本地库清空、首页整块消失、
     * 桌面小组件随之退场。
     *
     * <p>为什么不能把「桌面小组件」当成一个写入目标：小组件读的是本地库，
     * 它没有自己的库要写。它能显示什么，完全由本地库这一侧决定。
     * 把它当独立目标会造出一个自相矛盾的状态——数据在库里、桌面看得见、
     * 首页却看不见。
     */
    public static final String TARGET_LOCAL = "local";

    /**
     * 旧版多选里的「桌面小组件」值。
     *
     * @deprecated v4.0.3 第二轮起，小组件不再是写入目标，
     *         只是 {@link #TARGET_LOCAL} 的展示投影。仅用于迁移旧设置。
     */
    @Deprecated
    public static final String TARGET_WIDGET = "widget";

    /**
     * 旧版单选的「仅小组件」值。
     *
     * @deprecated v4.0.3 起改成多选（{@code backend_targets}），
     *         这个常量只用于把老用户的设置迁移过来，别再写新逻辑。
     */
    @Deprecated
    public static final String BACKEND_WIDGET_ONLY = "widget_only";

    /** 手动覆盖的 SharedPreferences 名/键（与主配置一致） */
    private static final String PREFS = "dedup";
    private static final String KEY_OVERRIDE = "backend_override";
    /** v4.0.3：多选的目标集合（含 {@link #TARGET_LOCAL}） */
    private static final String KEY_TARGETS = "backend_targets";
    /**
     * 「本地记录这一位已经按新模型初始化过」的标记。
     *
     * <p>迁移时要把 {@link #TARGET_LOCAL} 补成「开」——老用户的本地库里
     * 本来就有数据，如果因为集合里没有这个新值就当成「关闭」，一升级
     * 就会把记录清空。只有用户自己动手关过才算关，所以这个补位只做一次。
     */
    private static final String KEY_LOCAL_INIT = "local_target_initialized";

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

    /**
     * 设置页切换「写入目标」后调用，使下次 detect() 重新判定
     */
    public static void invalidateCache() {
        cached = null;
    }

    /**
     * 「软件内部记录」打开了吗。
     *
     * <p>这一位同时决定三件事：本地库要不要落数据、首页顶部要不要出现「待取件」、
     * 桌面小组件有没有内容可显示。三者是同一个决定的三种表现，不是三个开关。
     */
    public static boolean localSelected(Context ctx) {
        return selection(ctx).contains(TARGET_LOCAL);
    }

    /**
     * 只改「软件内部记录」这一位，别的勾选保持不动。
     *
     * <p>调用方<b>必须</b>先确认关掉之后还剩至少一个目标（{@link #canTurnLocalOff}），
     * 否则这里会因为集合变空而静默不保存，界面与落盘状态就会对不上。
     */
    public static void setLocalSelected(Context ctx, boolean on) {
        if (ctx == null) return;
        java.util.Set<String> sel = selection(ctx);
        if (on) {
            sel.add(TARGET_LOCAL);
        } else {
            sel.remove(TARGET_LOCAL);
        }
        if (sel.isEmpty()) {
            Log.w(TAG, "setLocalSelected ignored: would leave no target at all");
            return;
        }
        saveSelection(ctx, sel);
    }

    /** 关掉「软件内部记录」之后还会不会剩下至少一个待办目标 */
    public static boolean canTurnLocalOff(Context ctx) {
        return !todoTargets(ctx).isEmpty();
    }

    /**
     * 「主目标」——只读场景（诊断显示、跳转 App、导入历史）用它。
     *
     * <p>写入是另一回事：那是多目标循环，见 {@link #todoTargets(Context)}。
     * 这里取用户勾选的第一个可用目标；一个都没勾（只写小组件）时回落到
     * 系统默认目标，因为这些只读场景仍然需要一个具体的库位置。
     */
    private static String resolve(Context ctx) {
        try {
            java.util.List<String> t = todoTargets(ctx);
            if (!t.isEmpty()) return t.get(0);
        } catch (Throwable ignored) { }
        return systemDefaultTodo();
    }

    /** 本机默认的待办后端（不看用户选择） */
    public static String systemDefaultTodo() {
        return isMiuiRom() ? BACKEND_XIAOMI : BACKEND_COLOROS_TODO;
    }

    /**
     * 云同步所属的账号体系。
     *
     * <p>用来向用户解释「系统待办为什么值得开着」：它写进的是系统级的库，
     * 会跟着这个账号同步到同账号的其他设备；而 App 内部的记录只在本机。
     * 小米是小米账号，ColorOS 是 OPPO 账号（HeyTap 云服务）。
     */
    public static String cloudAccountName() {
        return isMiuiRom() ? "小米账号" : "OPPO 账号";
    }

    /**
     * 本机可选的「待办类」目标（不含小组件）。
     *
     * <p>小米只有小米笔记待办；ColorOS 有两个——日历待办与便签笔记。
     * 两个是各自独立的 SQLite 库、独立的 SQL，<b>可以同时写</b>，
     * 所以这里允许都勾上（要不要都写由用户决定）。
     */
    public static java.util.List<String> availableTodoTargets() {
        java.util.List<String> l = new java.util.ArrayList<>();
        if (isMiuiRom()) {
            l.add(BACKEND_XIAOMI);
        } else {
            l.add(BACKEND_COLOROS_TODO);
            l.add(BACKEND_COLOROS_NOTE);
        }
        return l;
    }

    /**
     * 读取用户勾选的目标集合（{@link #TARGET_LOCAL} + 若干待办后端）。
     *
     * <p>缺失时先尝试从旧的单选 {@code backend_override} 迁移过来：
     * <ul>
     *   <li>{@code widget_only} → 只留内部记录（不写任何待办，旧行为等价）</li>
     *   <li>某个具体后端 → 勾它 + 内部记录（旧行为的等价形式）</li>
     *   <li>空/自动 → 系统默认后端 + 内部记录</li>
     * </ul>
     *
     * <p><b>升级补位</b>：老版本的集合里有 {@code widget}（小组件），
     * 没有 {@code local}（内部记录）。{@link #normalize} 会把 {@code widget}
     * 换成 {@code local}；但如果用户当时手动关掉了小组件，集合里就一个本地位都没有。
     * 这时必须补上 {@code local}——本地库里本来就有他的记录，不补就等于
     * 一升级就清空数据。补位只做一次（{@link #KEY_LOCAL_INIT}），
     * 之后用户自己关掉不会再被加回来。
     */
    public static java.util.Set<String> selection(Context ctx) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (ctx == null) {
            out.add(TARGET_LOCAL);
            out.add(systemDefaultTodo());
            return out;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            java.util.Set<String> stored = sp.getStringSet(KEY_TARGETS, null);
            if (stored != null && !stored.isEmpty()) {
                out.addAll(normalize(stored));
                if (!sp.getBoolean(KEY_LOCAL_INIT, false)) {
                    boolean added = out.add(TARGET_LOCAL);
                    sp.edit().putBoolean(KEY_LOCAL_INIT, true).apply();
                    if (added) Log.i(TAG, "local target backfilled on upgrade -> " + out);
                }
                if (out.isEmpty()) {
                    // 存了一堆本机不认识的脏值：回到默认，但不清用户数据
                    out.add(TARGET_LOCAL);
                    out.add(systemDefaultTodo());
                }
                // 归一只在内存里做是不够的：磁盘上留着 widget 这种废弃值，
                // 下次排查配置时看到的和真正生效的对不上。变了就写回去。
                if (!out.equals(stored)) {
                    sp.edit().putStringSet(KEY_TARGETS, new java.util.LinkedHashSet<>(out)).apply();
                    Log.i(TAG, "selection normalized on disk -> " + out);
                }
                return out;
            }
            // 迁移旧单选
            String old = sp.getString(KEY_OVERRIDE, "");
            java.util.Set<String> migrated = new java.util.LinkedHashSet<>();
            if (BACKEND_WIDGET_ONLY.equals(old)) {
                migrated.add(TARGET_WIDGET);
            } else if (BACKEND_XIAOMI.equals(old) || BACKEND_COLOROS_TODO.equals(old)
                    || BACKEND_COLOROS_NOTE.equals(old)) {
                migrated.add(old);
                migrated.add(TARGET_WIDGET);
            } else {
                migrated.add(systemDefaultTodo());
                migrated.add(TARGET_WIDGET);
            }
            out.addAll(normalize(migrated));
            sp.edit().putStringSet(KEY_TARGETS, new java.util.LinkedHashSet<>(out))
                    .putBoolean(KEY_LOCAL_INIT, true)
                    .remove(KEY_OVERRIDE).apply();
            Log.i(TAG, "selection migrated from '" + old + "' -> " + out);
            return out;
        } catch (Throwable t) {
            out.clear();
            out.add(TARGET_LOCAL);
            out.add(systemDefaultTodo());
            return out;
        }
    }

    /**
     * 旧值 / 脏值归一。
     *
     * <p>{@code widget} 是旧模型里的「桌面小组件」，新模型下它就是
     * {@code local}（小组件只是本地记录的投影），所以直接换掉而不是丢掉。
     */
    private static java.util.Set<String> normalize(java.util.Set<String> raw) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        java.util.List<String> avail = availableTodoTargets();
        for (String t : raw) {
            if (t == null || t.trim().isEmpty()) continue;
            if (TARGET_WIDGET.equals(t)) {
                out.add(TARGET_LOCAL);
            } else if (TARGET_LOCAL.equals(t) || avail.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 保存勾选（调用方保证非空） */
    public static void saveSelection(Context ctx, java.util.Set<String> sel) {
        if (ctx == null || sel == null || sel.isEmpty()) return;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putStringSet(KEY_TARGETS, new java.util.LinkedHashSet<>(sel))
                    .apply();
            invalidateCache();
        } catch (Throwable t) {
            Log.w(TAG, "saveSelection failed: " + t);
        }
    }

    /**
     * 要写的待办目标（0~N 个）。空 = 只留本地记录，不碰系统待办。
     */
    public static java.util.List<String> todoTargets(Context ctx) {
        java.util.Set<String> sel = selection(ctx);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : availableTodoTargets()) {
            if (sel.contains(t)) out.add(t);
        }
        return out;
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

    /**
     * 用户查看结果应打开的 App 包名（通知「查看待办」跳转用）。
     *
     * <p>v4.0.3 第二轮：一个待办目标都没勾时回本 App——
     * 那条取件码只存在于内部记录里，跳到小米笔记只会让用户找不到东西。
     */
    public static String targetPkg(Context ctx) {
        java.util.List<String> targets = todoTargets(ctx);
        if (targets.isEmpty()) return RuntimePaths.PKG;
        return targetPkgOf(targets.get(0));
    }

    public static String targetPkgOf(String b) {
        if (BACKEND_WIDGET_ONLY.equals(b)) return RuntimePaths.PKG;   // 旧值：只写小组件时回本 App
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

    /**
     * LIKE 模式里的字面量转义（v3.1.0 新增）。
     *
     * 单引号转义（sqlEscapePub）只处理 '，但 LIKE 还有两个元字符：%（任意长）_（任意单字符）。
     * 此前 markDone 入参未校验，传 % 就变成 LIKE '%%'，会批量标记该用户全部未完成待办。
     * SQLite 用 ESCAPE 指定转义符，此处统一用反斜杠。
     */
    static String escapeLike(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** LIKE 条件统一片段 */
    private static String likeOf(String code) {
        return " LIKE '%" + escapeLike(TodoWriter.sqlEscapePub(code)) + "%' ESCAPE '\\'";
    }

    /** 标记已取件：按取件码定位未完成的待办/笔记 */
    public static String markDoneSql(Context ctx, String code) { return markDoneSqlOf(detect(ctx), code); }

    public static String markDoneSqlOf(String b, String code) {
        String pat = likeOf(code);
        if (BACKEND_COLOROS_NOTE.equals(b)) {
            return "UPDATE rich_notes SET text = text || '（已取件）',"
                    + " summary_content = summary_content || '（已取件）',"
                    + " top_time = 0, update_time = strftime('%s','now')*1000"
                    + " WHERE deleted = 0 AND text" + pat
                    + " AND text NOT LIKE '%已取件%';\n";
        }
        if (BACKEND_COLOROS_TODO.equals(b)) {
            return "UPDATE Tasks SET finish_time = strftime('%s','now')*1000,"
                    + " update_time = strftime('%s','now')*1000"
                    + " WHERE deleted = 0 AND finish_time IS NULL AND content" + pat + ";\n";
        }
        return "UPDATE todo SET is_finish=1, mark_finish_time=strftime('%s','now')*1000 "
                + "WHERE is_finish=0 AND content" + pat + ";";
    }

    /** 体检/兜底用：目标表里是否已存在包含 code 的未完成待办（进程外去重） */
    public static String existsSqlOf(String b, String code) {
        String pat = likeOf(code);
        if (BACKEND_COLOROS_TODO.equals(b)) {
            return "SELECT COUNT(*) FROM Tasks WHERE deleted=0 AND finish_time IS NULL"
                    + " AND content" + pat + ";";
        }
        if (BACKEND_COLOROS_NOTE.equals(b)) {
            return "SELECT COUNT(*) FROM rich_notes WHERE deleted=0 AND text" + pat
                    + " AND text NOT LIKE '%已取件%';";
        }
        return "SELECT COUNT(*) FROM todo WHERE is_finish=0 AND content" + pat + ";";
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
