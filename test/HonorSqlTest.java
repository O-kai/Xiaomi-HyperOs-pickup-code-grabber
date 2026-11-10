import io.github.okaidev.pickupcode.HonorSql;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 荣耀侧「表结构自适应 SQL」离线单测（v4.1.0）。
 *
 * <p>为什么这块必须单独测：{@link HonorSql} 的作用是「在不知道 ROM 表结构的情况下
 * 拼出能跑的 SQL」。它一旦拼错，表现是「sqlite 退出码 0、但一行没写进去」——
 * 上层会当成成功，去重指纹照常落盘，于是这条取件码永远不会再被写入。
 * 这是最难查的一类故障，所以这里把几种典型表结构（AOSP / ColorOS 系 /
 * 荣耀真机形状 / 缺正文列）逐一钉死，并单独验 LIKE 转义与表名自适应。
 *
 * <p>跑法（不需要 Android SDK，HonorSql 是纯 Java）：
 * <pre>
 *   javac -encoding UTF-8 -d out app/src/io/github/okaidev/pickupcode/HonorSql.java test/HonorSqlTest.java
 *   java -cp out HonorSqlTest
 * </pre>
 * 想连真实 sqlite 一起验（推荐）：
 * <pre>
 *   java -cp out HonorSqlTest --dump /tmp/honor-sql
 *   python tools/honor_sql_check.py /tmp/honor-sql
 * </pre>
 */
public class HonorSqlTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            fail++;
            System.out.println("FAIL  " + name + (detail == null ? "" : "  -> " + detail));
        }
    }

    /**
     * 用探针输出文本构造表结构（与真机上 PRAGMA table_info 的格式一致）。
     *
     * <p>注意格式是 v4.1.0 的「分段探针」：先列表名，再对每个候选表打一段标记 + PRAGMA。
     */
    static String probeText(String[] tableNames, String[]... tablesAndCols) {
        StringBuilder sb = new StringBuilder(".timeout 5000\n");
        for (String t : tableNames) sb.append(t).append('\n');
        sb.append(HonorSql.MARK_TABLES).append('\n');
        for (String[] pair : tablesAndCols) {
            String table = pair[0];
            sb.append(HonorSql.MARK_BEGIN).append(table).append("@@@\n");
            for (int i = 1; i < pair.length; i++) {
                sb.append(i - 1).append('|').append(pair[i]).append("|TEXT|0||0\n");
            }
        }
        return sb.toString();
    }

    static String[] t(String table, String... cols) {
        String[] out = new String[cols.length + 1];
        out[0] = table;
        System.arraycopy(cols, 0, out, 1, cols.length);
        return out;
    }

    static final String[] AOSP_COLS = {"_id", "calendar_id", "title", "description", "dtstart",
            "due", "completed", "priority", "_sync_account", "_sync_dirty"};
    static final String[] COLOROS_COLS = {"local_id", "global_id", "local_global_id", "dirty",
            "content", "allDay", "star", "mutators", "create_package", "create_time",
            "update_time", "sort_time", "color", "timezone", "sys_version", "force_reminder",
            "canPartiallyUpdate", "deleted", "finish_time"};
    /**
     * 荣耀真机（HONOR · MagicOS 11 / task.db）的真实列，节选自
     * PRAGMA table_info(Tasks)：uuid NOT NULL 无默认、正文 title+body、完成列 complete。
     */
    static final String[] HONOR_COLS = {"_id", "uuid", "title", "guid", "_sync_id", "complete",
            "importance", "time_type", "body", "folder_uuid", "account_uuid", "has_attachment",
            "dirty", "delete_flag", "create_time", "modify_time", "html_content",
            "search_content", "reminder_type", "date_completed", "due_date", "delete_time",
            "isAllDay", "custom_app_package", "message"};

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--dump".equals(args[0])) {
            dump(new File(args[1]));
            return;
        }
        run();
        System.out.println();
        System.out.println("TOTAL pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void run() {
        String content = "取件码 12-3-4567｜菜鸟驿站｜O'Brien 100%_test";

        // ---------- AOSP 系：表名 Tasks / title / completed ----------
        List<HonorSql.Schema> aospAll = HonorSql.parseAll(probeText(
                new String[]{"Calendars", "Tasks"},
                t("Tasks", AOSP_COLS), t("task"), t("todo")));
        check("AOSP: 只挑出有正文列的候选表", aospAll.size() == 1, String.valueOf(aospAll.size()));
        HonorSql.Schema aosp = aospAll.get(0);
        check("AOSP: 表名 Tasks", "Tasks".equals(aosp.table), aosp.table);
        check("AOSP: 正文列是 title", "title".equals(aosp.textColumn()), aosp.textColumn());
        check("AOSP: 完成列是 completed", "completed".equals(aosp.doneColumn()), aosp.doneColumn());
        String aIns = HonorSql.insertSql(aosp, content, "com.hihonor.calendar");
        check("AOSP: 生成 INSERT", aIns != null && aIns.contains("INSERT INTO Tasks"), String.valueOf(aIns));
        check("AOSP: calendar_id 从 Calendars 取",
                aIns != null && aIns.contains("(SELECT _id FROM Calendars"), null);
        check("AOSP: 带成功标记", aIns != null && aIns.contains(HonorSql.OK), null);
        String aDone = HonorSql.doneSql(aosp, "12-3-4567");
        check("AOSP: 完成 = completed=1", aDone != null && aDone.contains("completed=1"), String.valueOf(aDone));
        check("AOSP: 只改未完成的", aDone != null && aDone.contains("completed=0"), null);

        // ---------- ColorOS 系：content / finish_time / UUID ----------
        HonorSql.Schema cos = HonorSql.parseAll(probeText(
                new String[]{"Tasks", "Calendars"}, t("Tasks", COLOROS_COLS))).get(0);
        check("ColorOS: 正文列优先 content", "content".equals(cos.textColumn()), cos.textColumn());
        check("ColorOS: 完成列是 finish_time", "finish_time".equals(cos.doneColumn()), cos.doneColumn());
        String cIns = HonorSql.insertSql(cos, content, "com.hihonor.calendar");
        check("ColorOS: 不写 finish_time（保持 NULL）",
                cIns != null && !cIns.contains("finish_time"), null);
        check("ColorOS: 三个 UUID 列都在",
                cIns != null && cIns.contains("local_id") && cIns.contains("global_id")
                        && cIns.contains("local_global_id"), null);
        String cDone = HonorSql.doneSql(cos, "12-3-4567");
        check("ColorOS: 完成 = 写 finish_time",
                cDone != null && cDone.contains("finish_time="), String.valueOf(cDone));

        // ---------- 荣耀真机形状：task.db / Tasks / uuid + title + body + complete ----------
        HonorSql.Schema hon = HonorSql.parseAll(probeText(
                new String[]{"Accounts", "Folders", "Instances", "Tasks"},
                t("Tasks", HONOR_COLS))).get(0);
        check("荣耀: 认出表名 Tasks", "Tasks".equals(hon.table), hon.table);
        check("荣耀: 正文列 title", "title".equals(hon.textColumn()), hon.textColumn());
        check("荣耀: 完成列 complete", "complete".equals(hon.doneColumn()), hon.doneColumn());
        check("荣耀: 逻辑删除列 delete_flag", "delete_flag".equals(hon.deleteColumn()), hon.deleteColumn());
        String hIns = HonorSql.insertSql(hon, content, "com.hihonor.calendar");
        check("荣耀: 必填 uuid 已写入", hIns != null && hIns.contains("uuid"), String.valueOf(hIns));
        check("荣耀: uuid 用 $ 分隔（真机形状）",
                hIns != null && hIns.matches("(?s).*'[0-9a-f]{8}\\$[0-9a-f]{4}\\$.*"), null);
        check("荣耀: title 与 body 同时写",
                hIns != null && hIns.contains("title") && hIns.contains(" body"), null);
        check("荣耀: 挂到 Folders/Accounts 上",
                hIns != null && hIns.contains("(SELECT uuid FROM Folders") 
                        && hIns.contains("(SELECT uuid FROM Accounts"), null);
        check("荣耀: 初始未完成", hIns != null && hIns.contains("complete, ") && hIns.contains(", 0,"), null);
        check("荣耀: 时间轴补 Instances 行",
                hIns != null && hIns.contains("INSERT INTO Instances"), null);
        String hDone = HonorSql.doneSql(hon, "12-3-4567");
        check("荣耀: 完成 = complete=1", hDone != null && hDone.contains("complete=1"), String.valueOf(hDone));
        check("荣耀: 同时写 date_completed", hDone != null && hDone.contains("date_completed="), null);
        check("荣耀: 只改未完成的", hDone != null && hDone.contains("delete_flag=0 AND complete=0"), null);
        check("荣耀: count 探针用真实表名",
                "SELECT COUNT(*) FROM Tasks;".equals(HonorSql.countSql(hon)), HonorSql.countSql(hon));

        // ---------- 表名候选顺序：Tasks 优先于 task ----------
        List<HonorSql.Schema> both = HonorSql.parseAll(probeText(
                new String[]{"task", "Tasks"},
                t("task", HONOR_COLS), t("Tasks", AOSP_COLS)));
        check("多候选: 按候选顺序取 Tasks", both.size() == 2 && "Tasks".equals(both.get(0).table),
                both.isEmpty() ? "空" : both.get(0).table);

        // ---------- 拒绝路径：宁可失败，也不要「假装写成功」 ----------
        List<HonorSql.Schema> noText = HonorSql.parseAll(probeText(
                new String[]{"Tasks"}, t("Tasks", "_id", "completed")));
        check("无正文列: 候选表被过滤掉", noText.isEmpty(), String.valueOf(noText.size()));
        HonorSql.Schema empty = HonorSql.parse(null);
        check("无输出: 返回空 Schema 而不是 null", empty != null && !empty.usable(), null);
        check("无输出: INSERT 返回 null", HonorSql.insertSql(empty, content, "x") == null, null);
        check("无输出: DONE 返回 null", HonorSql.doneSql(empty, "1") == null, null);

        // ---------- 转义 ----------
        HonorSql.Schema esc = HonorSql.parseAll(probeText(
                new String[]{"Tasks"}, t("Tasks", "_id", "content", "completed", "deleted"))).get(0);
        String eIns = HonorSql.insertSql(esc, "O'Brien 100%_test", "x");
        check("转义: 单引号变两个", eIns != null && eIns.contains("O''Brien"), String.valueOf(eIns));
        String eEx = HonorSql.existsSql(esc, "1%2_3");
        check("转义: % 被转义", eEx != null && eEx.contains("1\\%2"), String.valueOf(eEx));
        check("转义: _ 被转义", eEx != null && eEx.contains("2\\_3"), null);
        check("转义: 带 ESCAPE 子句", eEx != null && eEx.contains("ESCAPE"), null);
        check("转义: escape() 处理 null", "".equals(HonorSql.escape(null)), null);

        // ---------- 探针解析 ----------
        check("解析: 表名清单进 Schema", aosp.tables.contains("Calendars"), aosp.describe());
        check("解析: 描述里带真实表名", hon.describe().startsWith("Tasks "), hon.describe());
    }

    /** 把几种表结构的 SQL 落成文件，交给 tools/honor_sql_check.py 用真实 sqlite 跑 */
    static void dump(File dir) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdirs failed: " + dir);
        String content = "取件码 12-3-4567｜菜鸟驿站｜O'Brien 100%_test";

        HonorSql.Schema aosp = HonorSql.parseAll(probeText(
                new String[]{"Calendars", "Tasks"}, t("Tasks", AOSP_COLS))).get(0);
        HonorSql.Schema cos = HonorSql.parseAll(probeText(
                new String[]{"Tasks", "Calendars"}, t("Tasks", COLOROS_COLS))).get(0);
        HonorSql.Schema hon = HonorSql.parseAll(probeText(
                new String[]{"Accounts", "Folders", "Instances", "Tasks"},
                t("Tasks", HONOR_COLS))).get(0);
        HonorSql.Schema esc = HonorSql.parseAll(probeText(
                new String[]{"Tasks"}, t("Tasks", "_id", "content", "completed", "deleted"))).get(0);
        HonorSql.Schema empty = HonorSql.parse(null);

        write(dir, "aosp", aosp, content, "12-3-4567");
        write(dir, "coloros", cos, content, "12-3-4567");
        write(dir, "honor_real", hon, content, "12-3-4567");
        write(dir, "escape", esc, content, "1%2_3");
        // 「没有可用表」的两种情形：SQL 必须是 NULL，让上层明确失败
        write(dir, "no_table", empty, content, "12-3-4567");
        System.out.println("dumped to " + dir.getAbsolutePath());
    }

    static void write(File dir, String name, HonorSql.Schema s, String content, String code)
            throws Exception {
        put(dir, name + ".insert.sql", HonorSql.insertSql(s, content, "com.hihonor.calendar"));
        put(dir, name + ".done.sql", HonorSql.doneSql(s, code));
        put(dir, name + ".exists.sql", HonorSql.existsSql(s, code));
        put(dir, name + ".columns.txt", s.describe());
    }

    static void put(File dir, String file, String text) throws Exception {
        FileOutputStream fos = new FileOutputStream(new File(dir, file));
        try {
            fos.write((text == null ? "NULL" : text).getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }
}
