package io.github.okaidev.pickupcode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 荣耀 MagicOS 待办库的「表结构自适应」SQL 生成器（v4.1.0）。
 *
 * <p><b>为什么不是一张写死的 INSERT</b>：小米侧和 ColorOS 侧的字段约定都是从真机
 * 样本上逆向出来的（见 docs/17-coloros-adaptation.md）。荣耀这边第一版也没有样本，
 * 所以做成「先读表结构，再按实际存在的列拼 SQL」——缺哪列就不写哪列。
 *
 * <p><b>v4.1.0 真机修正（HONOR（MagicOS 11 / Android 17）真机，task.db）</b>：
 * 拿到真机库之后，原来三处猜测全错，已按真实行改掉：
 * <ol>
 *   <li>待办库是 {@code task.db}，表名 {@code Tasks}；</li>
 *   <li>{@code Tasks.uuid} 是 {@code TEXT NOT NULL} 且无默认值——不填就报
 *       {@code NOT NULL constraint failed}（第一版就栽在这，而界面上显示的是
 *       「su 通道不可用」，因为任何非 0 退出都被归成了那一句）；</li>
 *   <li>正文列是 {@code title} + {@code body}（外加 {@code html_content}/{@code search_content}），
 *       完成列叫 {@code complete}（不是 {@code completed}），逻辑删除叫 {@code delete_flag}；</li>
 *   <li>任务要挂在 {@code Folders}/{@code Accounts} 上才看得见，日历时间轴还要一条
 *       {@code Instances} 行。</li>
 * </ol>
 *
 * <p><b>本类不依赖 Android</b>：纯 Java、纯字符串，所以可以在电脑上直接
 * {@code javac} + 真实 sqlite3 跑回归（见 test/HonorSqlTest.java 与
 * tools/honor_sql_check.py）。系统进程（没有 Context）也能用。
 */
public final class HonorSql {

    private HonorSql() { }

    /** 表名候选，按优先级排列；探针会挨个 PRAGMA，取第一个有正文列的 */
    public static final String[] TABLE_CANDIDATES = {
            "Tasks", "task", "tasks", "Task", "todo_task", "todo", "todos"
    };

    /** 日历表：待办要挂在某个日历上，calendar_id 从这里取 */
    public static final String CALENDARS = "Calendars";

    /** 探针输出里的分段标记：这一段之后是库里的表名清单 */
    public static final String MARK_TABLES = "@@@TABLES@@@";
    /** 每张候选表之前的分段标记前缀 */
    public static final String MARK_BEGIN = "@@@BEGIN:";
    /** 写入成功标记（sqlite 输出里出现即视为真的插进去了） */
    public static final String OK = "PICKUP_OK";
    /** 写入空转标记（语句执行了但一行都没改） */
    public static final String NOOP = "PICKUP_NOOP";

    /**
     * 一次探针拿三样东西：库里有哪些表、每张候选表各有哪些列。
     *
     * <p>为什么不用两次往返（先列表名再 PRAGMA 具体表）：系统进程里每次往返都是
     * 一次 {@code su}，而荣耀侧的兜底通道本来就跑在短信热路径上。一次读完缓存起来，
     * 之后每次写入只跑一条 INSERT。
     */
    public static final String PROBE_SQL = buildProbeSql();

    private static String buildProbeSql() {
        StringBuilder sb = new StringBuilder(".timeout 5000\n");
        sb.append("SELECT name FROM sqlite_master WHERE type='table';\n");
        sb.append("SELECT '").append(MARK_TABLES).append("';\n");
        for (String t : TABLE_CANDIDATES) {
            sb.append("SELECT '").append(MARK_BEGIN).append(t).append("@@@';\n");
            sb.append("PRAGMA table_info(").append(t).append(");\n");
        }
        return sb.toString();
    }

    /** 一张候选表的表结构快照 */
    public static final class Schema {
        /** 实际表名（可能是 task / Tasks / todo…） */
        public final String table;
        public final Set<String> columns;
        /** 整库的表名清单 */
        public final Set<String> tables;

        public Schema(String table, Set<String> columns, Set<String> tables) {
            this.table = table;
            this.columns = columns;
            this.tables = tables;
        }

        public boolean has(String column) {
            return column != null && columns.contains(column);
        }

        public boolean usable() {
            return table != null && !table.isEmpty() && !columns.isEmpty();
        }

        /** 能装正文的文本列，按优先级取第一个存在的 */
        public String textColumn() {
            String[] order = {"content", "title", "text", "description", "plain_text",
                    "note", "summary_content", "body"};
            for (String c : order) {
                if (columns.contains(c)) return c;
            }
            return null;
        }

        /** 标记完成用哪一列：finish_time（ColorOS 系）→ completed（AOSP）→ complete（荣耀）→ status */
        public String doneColumn() {
            if (columns.contains("finish_time")) return "finish_time";
            if (columns.contains("completed")) return "completed";
            if (columns.contains("complete")) return "complete";
            if (columns.contains("status")) return "status";
            return null;
        }

        /** 逻辑删除列：AOSP/ColorOS 叫 deleted，荣耀叫 delete_flag */
        public String deleteColumn() {
            if (columns.contains("deleted")) return "deleted";
            if (columns.contains("delete_flag")) return "delete_flag";
            return null;
        }

        /** 更新时间列：AOSP/ColorOS 叫 update_time，荣耀叫 modify_time */
        public String updateColumn() {
            if (columns.contains("update_time")) return "update_time";
            if (columns.contains("modify_time")) return "modify_time";
            return null;
        }

        /** 给人看的摘要（写进诊断报告） */
        public String describe() {
            if (columns.isEmpty()) {
                return table + " 表不存在（库里的表：" + (tables.isEmpty() ? "读不到" : join(tables)) + "）";
            }
            return table + " " + columns.size() + " 列：" + join(columns);
        }

        private static String join(Set<String> set) {
            StringBuilder sb = new StringBuilder();
            for (String s : set) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s);
            }
            return sb.toString();
        }
    }

    /**
     * 解析 {@link #PROBE_SQL} 的输出，返回「有正文列」的候选表，按候选顺序排列。
     *
     * <p>输出形状（与 {@link #buildProbeSql()} 一一对应）：
     * <pre>
     *   Calendars            ← 第一个标记之前：整库表名清单
     *   Tasks
     *   @@@TABLES@@@         ← 分隔标记（保留，便于人眼阅读）
     *   @@@BEGIN:Tasks@@@    ← 每个候选表一段
     *   0|_id|INTEGER|1||1
     *   1|title|TEXT|0||0
     *   @@@BEGIN:task@@@
     *   ...
     * </pre>
     */
    public static List<Schema> parseAll(String out) {
        List<Schema> result = new ArrayList<>();
        Set<String> tables = new LinkedHashSet<>();
        Set<String> columns = null;
        String current = null;
        boolean sawSection = false;
        if (out != null) {
            for (String raw : out.split("\r?\n")) {
                if (raw == null) continue;
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (MARK_TABLES.equals(line)) continue;      // 纯分隔，不承载信息
                if (line.startsWith(MARK_BEGIN)) {
                    if (current != null && columns != null && !columns.isEmpty()) {
                        result.add(new Schema(current, columns, new LinkedHashSet<>(tables)));
                    }
                    int end = line.indexOf("@@@", MARK_BEGIN.length());
                    current = end > 0 ? line.substring(MARK_BEGIN.length(), end) : null;
                    columns = new LinkedHashSet<>();
                    sawSection = true;
                    continue;
                }
                if (!sawSection) {
                    // 第一个候选表之前：整库的表名清单
                    tables.add(line);
                    continue;
                }
                // PRAGMA table_info 的默认输出：cid|name|type|notnull|dflt_value|pk
                String[] f = line.split("\\|", -1);
                if (f.length >= 2 && columns != null) {
                    String name = f[1].trim();
                    if (!name.isEmpty()) columns.add(name);
                }
            }
        }
        if (current != null && columns != null && !columns.isEmpty()) {
            result.add(new Schema(current, columns, new LinkedHashSet<>(tables)));
        }
        // 只保留「能装正文」的候选表：一张没有任何文本列的表不可能是待办表
        List<Schema> usable = new ArrayList<>();
        for (Schema s : result) {
            if (s.textColumn() != null) usable.add(s);
        }
        // 按 TABLE_CANDIDATES 的优先级稳定排序
        final List<String> order = java.util.Arrays.asList(TABLE_CANDIDATES);
        java.util.Collections.sort(usable, new java.util.Comparator<Schema>() {
            @Override
            public int compare(Schema a, Schema b) {
                return Integer.compare(order.indexOf(a.table), order.indexOf(b.table));
            }
        });
        return usable;
    }

    /**
     * 兼容旧调用：只要第一个可用候选表。
     *
     * @deprecated 用 {@link #parseAll(String)}；本方法只是为了少改调用点。
     */
    @Deprecated
    public static Schema parse(String out) {
        List<Schema> all = parseAll(out);
        return all.isEmpty() ? new Schema(TABLE_CANDIDATES[0], new LinkedHashSet<String>(),
                new LinkedHashSet<String>()) : all.get(0);
    }

    /**
     * 生成「新增一条待办」的 SQL。
     *
     * <p>真机结论见类注释：{@code uuid} 必须给（NOT NULL 无默认），正文要同时写
     * {@code title}/{@code body}，任务要挂 {@code folder_uuid}/{@code account_uuid}，
     * 时间轴还要一条 {@code Instances}。
     *
     * @param mutator 写进 mutators/create_package 的包名（荣耀是 com.hihonor.calendar）
     * @return null = 表结构里没有任何能装正文的列，调用方必须当成失败并给出诊断
     */
    public static String insertSql(Schema s, String content, String mutator) {
        if (s == null || !s.usable()) return null;
        String text = s.textColumn();
        if (text == null) return null;

        String esc = escape(content);
        String now = "(strftime('%s','now')*1000)";
        String dayStart = "(strftime('%s','now','start of day','localtime')*1000)";
        String pkg = escape(mutator == null || mutator.isEmpty() ? "com.hihonor.calendar" : mutator);
        String uuid = dollarUuid();
        boolean hasUuid = s.has("uuid");

        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        int n = 0;

        // 0) 主键：荣耀是 uuid（NOT NULL 无默认），AOSP/ColorOS 是 _id 自增，不用管
        if (hasUuid) n += add(cols, vals, n, s, "uuid", "'" + uuid + "'");

        // 1) 正文：所有存在的文本列都填，避免「日历读 title、我们写 content」的错位
        n += add(cols, vals, n, s, "content", "'" + esc + "'");
        n += add(cols, vals, n, s, "title", "'" + esc + "'");
        n += add(cols, vals, n, s, "body", "'" + esc + "'");
        n += add(cols, vals, n, s, "text", "'" + esc + "'");
        n += add(cols, vals, n, s, "description", "'" + esc + "'");
        n += add(cols, vals, n, s, "plain_text", "'" + esc + "'");
        n += add(cols, vals, n, s, "search_content", "'" + esc + "'");
        if (s.has("html_content")) {
            n += add(cols, vals, n, s, "html_content", "'" + escape(htmlOf(content)) + "'");
        }
        if (s.has("message")) {
            n += add(cols, vals, n, s, "message", "'" + escape(messageOf(content)) + "'");
        }

        // 2) 身份列：ColorOS 那套 Tasks 是 UUID 文本主键（不是自增 _id）
        n += add(cols, vals, n, s, "local_id", UUID_SQL);
        n += add(cols, vals, n, s, "global_id", UUID_SQL);
        n += add(cols, vals, n, s, "local_global_id", UUID_SQL);

        // 3) 挂在哪个日历 / 文件夹 / 账号上：能从库里取就取
        if (s.has("calendar_id")) {
            String calExpr = s.tables.contains(CALENDARS)
                    ? "(SELECT _id FROM " + CALENDARS + " ORDER BY _id LIMIT 1)"
                    : "1";
            n += add(cols, vals, n, s, "calendar_id", calExpr);
        }
        if (s.has("folder_uuid") && s.tables.contains("Folders")) {
            n += add(cols, vals, n, s, "folder_uuid", "(SELECT uuid FROM Folders LIMIT 1)");
        }
        if (s.has("account_uuid") && s.tables.contains("Accounts")) {
            n += add(cols, vals, n, s, "account_uuid",
                    "(SELECT uuid FROM Accounts ORDER BY _id LIMIT 1)");
        }
        // 账号列只在「待办表有、Calendars 也有」时才写；写 NULL 反而会被日历过滤掉
        if (s.has("_sync_account") && s.tables.contains(CALENDARS)) {
            n += add(cols, vals, n, s, "_sync_account",
                    "(SELECT _sync_account FROM " + CALENDARS + " ORDER BY _id LIMIT 1)");
        }

        // 4) 状态/排序/时间：有就填，没有就跳过
        n += add(cols, vals, n, s, "dirty", "1");
        n += add(cols, vals, n, s, "_sync_dirty", "1");
        n += add(cols, vals, n, s, "deleted", "0");
        n += add(cols, vals, n, s, "delete_flag", "0");
        n += add(cols, vals, n, s, "delete_time", "0");
        n += add(cols, vals, n, s, "completed", "0");
        n += add(cols, vals, n, s, "complete", "0");
        n += add(cols, vals, n, s, "date_completed", "0");
        n += add(cols, vals, n, s, "status", "0");
        n += add(cols, vals, n, s, "has_attachment", "0");
        n += add(cols, vals, n, s, "importance", "0");
        n += add(cols, vals, n, s, "time_type", "0");
        n += add(cols, vals, n, s, "reminder_type", "1");
        n += add(cols, vals, n, s, "allDay", "1");
        n += add(cols, vals, n, s, "isAllDay", "1");
        n += add(cols, vals, n, s, "star", "0");
        n += add(cols, vals, n, s, "color", "2");
        n += add(cols, vals, n, s, "timezone", "'UTC'");
        n += add(cols, vals, n, s, "sys_version", "1");
        n += add(cols, vals, n, s, "force_reminder", "0");
        n += add(cols, vals, n, s, "canPartiallyUpdate", "0");
        n += add(cols, vals, n, s, "mutators", "'" + pkg + "'");
        n += add(cols, vals, n, s, "create_package", "'" + pkg + "'");
        n += add(cols, vals, n, s, "custom_app_package", "'isMainUser:true,original:" + pkg + "'");
        n += add(cols, vals, n, s, "create_time", now);
        n += add(cols, vals, n, s, "update_time", now);
        n += add(cols, vals, n, s, "modify_time", now);
        n += add(cols, vals, n, s, "sort_time", now);
        n += add(cols, vals, n, s, "dtstart", now);
        n += add(cols, vals, n, s, "due", now);
        n += add(cols, vals, n, s, "due_date", dayStart);
        n += add(cols, vals, n, s, "_sync_time", now);

        if (n == 0) return null;
        StringBuilder out = new StringBuilder();
        out.append("INSERT INTO ").append(s.table)
                .append(" (").append(cols).append(") VALUES (").append(vals).append(");\n")
                .append("SELECT CASE WHEN changes() > 0 THEN '").append(OK)
                .append("' ELSE '").append(NOOP).append("' END;\n");
        out.append(TodoMatch.CAPTURE_ROW_SQL);
        // 日历时间轴：真机每条待办都有一条 Instances，缺了可能列表里有、日历里看不见
        if (hasUuid && s.has("due_date") && s.tables.contains("Instances")) {
            out.append("INSERT INTO Instances (task_id, task_uuid, begin, end, startDay, endDay,")
                    .append(" startMinute, endMinute)\n")
                    .append("SELECT _id, uuid, due_date, 0,")
                    .append(" CAST(julianday('now','localtime') + 0.5 AS INTEGER), 0, 0, 0\n")
                    .append("FROM ").append(s.table).append(" WHERE uuid='").append(uuid).append("';\n");
        }
        return out.toString();
    }

    /**
     * 生成「标记已取件」的 SQL。
     *
     * <p>完成语义按列自适应：{@code finish_time}（ColorOS 系）→ {@code completed}（AOSP）
     * → {@code complete}（荣耀真机，另写 {@code date_completed}）→ {@code status}。
     */
    /** Without a target receipt, generate a no-op instead of touching matching user tasks. */
    @Deprecated public static String doneSql(Schema s, String code) {
        return doneSql(s, code, null);
    }

    /** For a verified row receipt; callers must supply the actual inserted target ID/content. */
    public static String doneSqlForTarget(Schema s, long rowId, String content) {
        if (s == null || !s.usable() || s.textColumn() == null || rowId <= 0 || content == null) return null;
        return doneSql(s, null, "(rowid=" + rowId + " AND " + s.textColumn()
                + "=" + TodoMatch.quote(content) + ")");
    }

    static String doneSql(Schema s, String code, String ownedWhere) {
        if (s == null || !s.usable()) return null;
        String text = s.textColumn();
        String done = s.doneColumn();
        if (text == null || done == null) return null;

        String now = "(strftime('%s','now')*1000)";
        StringBuilder set = new StringBuilder();
        if ("finish_time".equals(done)) {
            set.append("finish_time=").append(now);
        } else if ("completed".equals(done)) {
            set.append("completed=1");
        } else if ("complete".equals(done)) {
            set.append("complete=1");
            if (s.has("date_completed")) set.append(", date_completed=").append(now);
        } else {
            set.append("status=2");
        }
        String upd = s.updateColumn();
        if (upd != null) set.append(", ").append(upd).append("=").append(now);
        if (s.has("dirty")) set.append(", dirty=1");
        if (s.has("_sync_dirty")) set.append(", _sync_dirty=1");

        StringBuilder where = new StringBuilder();
        String del = s.deleteColumn();
        if (del != null) where.append(del).append("=0 AND ");
        if ("finish_time".equals(done)) where.append("finish_time IS NULL AND ");
        else if ("completed".equals(done)) where.append("completed=0 AND ");
        else if ("complete".equals(done)) where.append("complete=0 AND ");

        String like = ownedWhere == null ? "0" : ownedWhere;
        if (like == null) return null;
        return "UPDATE " + s.table + " SET " + set + " WHERE " + where + like + ";\n";
    }

    /** 生成「这条取件码是不是已经有一条未完成待办」的查询（兜底通道去重用） */
    public static String existsSql(Schema s, String code) {
        if (s == null || !s.usable()) return null;
        String text = s.textColumn();
        if (text == null) return null;
        StringBuilder where = new StringBuilder();
        String del = s.deleteColumn();
        if (del != null) where.append(del).append("=0 AND ");
        if (s.has("finish_time")) where.append("finish_time IS NULL AND ");
        else if (s.has("completed")) where.append("completed=0 AND ");
        else if (s.has("complete")) where.append("complete=0 AND ");
        String like = TodoMatch.legacyClause(text, code);
        if (like == null) return null;
        return "SELECT COUNT(*) FROM " + s.table + " WHERE " + where + like + ";\n";
    }

    /** 体检用的计数探针 */
    public static String countSql(Schema s) {
        return s == null || !s.usable() ? null : "SELECT COUNT(*) FROM " + s.table + ";";
    }

    /** 荣耀 UUID 形状：8$4$4$4$12（真机样本就是这样，用 $ 分隔而不是 -） */
    static String dollarUuid() {
        String h = java.util.UUID.randomUUID().toString().replace("-", "");
        return h.substring(0, 8) + "$" + h.substring(8, 12) + "$" + h.substring(12, 16)
                + "$" + h.substring(16, 20) + "$" + h.substring(20);
    }

    /** 真机 html_content 是一整段 HTML 外壳，这里给一个最小可用版本 */
    static String htmlOf(String content) {
        return "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">"
                + "</head><body>" + htmlEscape(content) + "</body></html>";
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** message 列：others_<标题>_<日期>_<颜色>（标题里的下划线换成横线，避免拆错段） */
    static String messageOf(String content) {
        String t = content == null ? "" : content.replace('_', '-');
        if (t.length() > 40) t = t.substring(0, 40);
        String d = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date());
        return "others_" + t + "_" + d + "_10FF03FF";
    }

    private static int add(StringBuilder cols, StringBuilder vals, int n,
                           Schema s, String column, String expr) {
        if (!s.has(column)) return 0;
        if (n > 0) {
            cols.append(", ");
            vals.append(", ");
        }
        cols.append(column);
        vals.append(expr);
        return 1;
    }

    /** SQL 单引号转义（与 TodoWriter.sqlEscape 同语义） */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("'", "''").replace("\0", "");
    }

    /** LIKE 元字符转义：配合 ESCAPE '\' 使用 */
    public static String likeEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** SQLite 内联 UUID v4（ColorOS 那套 local_id/global_id 用） */
    private static final String UUID_SQL =
            "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||"
                    + " substr(lower(hex(randomblob(2))),2) || '-' ||"
                    + " substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))),2) || '-' ||"
                    + " lower(hex(randomblob(6)))";
}
