import io.github.okaidev.pickupcode.PickupExport;
import io.github.okaidev.pickupcode.PickupRecord;
import io.github.okaidev.pickupcode.PickupRow;
import io.github.okaidev.pickupcode.Retention;
import io.github.okaidev.pickupcode.RuleIntegrity;

import java.util.ArrayList;
import java.util.List;

/**
 * 4.0 数据层离线单测：保留期策略、导出/导入格式、规则完整性校验。
 *
 * <p>这三块都是「错了不会报错、只会安静地做错事」的地方：
 * 保留期算错会把用户还没取的件删掉；导出格式坏了一导就丢数据；
 * 规则校验失效则会让一份被改过的规则悄悄替换掉内置规则。
 */
public class DataTest {

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

    public static void main(String[] args) {
        retentionTests();
        exportTests();
        importTests();
        integrityTests();
        System.out.println("--------------------------------");
        System.out.println("DataTest: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ==================== 保留期 ====================

    static void retentionTests() {
        long now = 1_700_000_000_000L;
        long day = 24L * 60 * 60 * 1000;

        check("默认保留 90 天", Retention.sanitize(999) == Retention.KEEP_90D, "");
        check("合法值原样保留", Retention.sanitize(30) == 30, "");
        check("负数回落默认", Retention.sanitize(-5) == Retention.KEEP_90D, "");
        check("永久保留保留 0", Retention.sanitize(0) == 0, "");

        check("永久保留 → 不清理", Retention.cutoffBefore(0, now) == 0L, "");
        check("30 天 → 阈值正确",
                Retention.cutoffBefore(30, now) == now - 30 * day, "");
        check("选项集合正确", Retention.options().length == 5, "");

        check("文案：永久保留", Retention.label(0).equals("永久保留"), Retention.label(0));
        check("文案：90 天", Retention.label(90).equals("90 天"), Retention.label(90));
        check("文案：1 年", Retention.label(365).equals("1 年"), Retention.label(365));

        // v4.0.2：带说明的文案（选择器里每一项都要说清楚管哪类记录、何时清理）
        check("说明文案：90 天提到保留天数",
                Retention.labelWithHint(90).contains("90 天"), Retention.labelWithHint(90));
        check("说明文案：提到自动清理",
                Retention.labelWithHint(90).contains("自动清理"), Retention.labelWithHint(90));
        check("说明文案：提到只管已取件",
                Retention.labelWithHint(90).contains("已取件"), Retention.labelWithHint(90));
        check("说明文案：永久保留也不删",
                Retention.labelWithHint(0).contains("永久保留")
                        && Retention.labelWithHint(0).contains("不会自动删除"),
                Retention.labelWithHint(0));
        check("说明文案：每个选项都有说明",
                Retention.labelWithHint(30).length() > Retention.label(30).length(), "");

        // 滞留提示：v4.0.2 从「3 天才算过期」改成「超过一天就标滞留天数」。
        // 用户反馈「单纯写『可能』已失效概念太模糊」——
        // 于是改成把「滞留了几天」这个事实直接写出来。
        long hour = 3600_000L;
        check("24 小时内不算滞留", !Retention.isProbablyExpired(now - 23 * hour, now), "");
        check("超过 24 小时算滞留", Retention.isProbablyExpired(now - 25 * hour, now), "");
        check("时间戳为 0 不算滞留", !Retention.isProbablyExpired(0, now), "");
        check("未来时间不算滞留", !Retention.isProbablyExpired(now + day, now), "");

        // 滞留天数：向下取整（25 小时算 1 天、49 小时算 2 天）
        check("不足一天 staleDays=0", Retention.staleDays(now - 23 * hour, now) == 0, "");
        check("25 小时 staleDays=1", Retention.staleDays(now - 25 * hour, now) == 1, "");
        check("49 小时 staleDays=2", Retention.staleDays(now - 49 * hour, now) == 2, "");
        check("staleDays 时间戳为 0 时为 0", Retention.staleDays(0, now) == 0, "");

        // 标签文案：一天之内不标，最多到「滞留 7 天」，超过写「7 天以上」
        check("0 天不显示标签", Retention.staleBadge(0).isEmpty(), Retention.staleBadge(0));
        check("1 天", "滞留 1 天".equals(Retention.staleBadge(1)), Retention.staleBadge(1));
        check("2 天", "滞留 2 天".equals(Retention.staleBadge(2)), Retention.staleBadge(2));
        check("6 天", "滞留 6 天".equals(Retention.staleBadge(6)), Retention.staleBadge(6));
        check("7 天封顶", "滞留 7 天".equals(Retention.staleBadge(7)), Retention.staleBadge(7));
        check("8 天写以上", "滞留 7 天以上".equals(Retention.staleBadge(8)), Retention.staleBadge(8));
        check("30 天也写以上", "滞留 7 天以上".equals(Retention.staleBadge(30)), Retention.staleBadge(30));
        check("负数不显示标签", Retention.staleBadge(-1).isEmpty(), Retention.staleBadge(-1));

        check("daysSince 向下取整", Retention.daysSince(now - 5 * day - 100, now) == 5, "");
        check("daysSince 不为负", Retention.daysSince(now + day, now) == 0, "");
    }

    // ==================== 导出 ====================

    static void exportTests() {
        List<PickupRow> rows = new ArrayList<>();
        rows.add(new PickupRow("16-4-9626", "菜鸟驿站", "XX小区",
                1_700_000_000_000L, PickupRecord.STATUS_PENDING, 0L));
        rows.add(new PickupRow("D4-7048", "妈妈驿站", "天和人家快递点",
                1_700_000_100_000L, PickupRecord.STATUS_PICKED_UP, 1_700_000_200_000L));

        String json = PickupExport.toJson(rows, 1_700_000_500_000L);
        check("JSON 含格式标记", json.contains(PickupExport.FORMAT_TAG), "");
        check("JSON 含第一条码", json.contains("16-4-9626"), "");
        check("JSON 含第二条码", json.contains("D4-7048"), "");
        check("JSON 不含表头残留", !json.contains("code,source"), "");

        String csv = PickupExport.toCsv(rows);
        check("CSV 有表头", csv.contains(PickupExport.CSV_HEADER), "");
        check("CSV 带 BOM", csv.startsWith("\uFEFF"), "");
        check("CSV 含第一条码", csv.contains("16-4-9626"), "");
        check("CSV 含已取状态", csv.contains(PickupRecord.STATUS_PICKED_UP), "");

        // 转义：字段里带逗号、引号、换行
        List<PickupRow> tricky = new ArrayList<>();
        tricky.add(new PickupRow("1-2-3", "驿站,一", "地点\"东\"侧\n第二行",
                1L, PickupRecord.STATUS_PENDING, 0L));
        String csv2 = PickupExport.toCsv(tricky);
        check("CSV 转义含逗号", csv2.contains("\"驿站,一\""), csv2);
        check("CSV 转义含引号", csv2.contains("\"地点\"\"东\"\"侧"), csv2);

        List<String> parsed = PickupExport.parseCsvLine("a,\"b,c\",\"d\"\"e\"");
        check("CSV 解析：3 段", parsed.size() == 3, String.valueOf(parsed.size()));
        check("CSV 解析：逗号段", "b,c".equals(parsed.get(1)), "");
        check("CSV 解析：引号段", "d\"e".equals(parsed.get(2)), "");
    }

    // ==================== 导入 ====================

    static void importTests() {
        List<PickupRow> rows = new ArrayList<>();
        rows.add(new PickupRow("16-4-9626", "菜鸟驿站", "XX小区",
                1_700_000_000_000L, PickupRecord.STATUS_PENDING, 0L));
        rows.add(new PickupRow("D4-7048", "妈妈驿站", "天和人家快递点",
                1_700_000_100_000L, PickupRecord.STATUS_PICKED_UP, 1_700_000_200_000L));

        // JSON 往返
        String json = PickupExport.toJson(rows, 1_700_000_500_000L);
        PickupExport.ParseResult r = PickupExport.fromJson(json);
        check("JSON 导入：解析成功", r.ok(), r.error);
        check("JSON 导入：条数正确", r.rows.size() == 2, String.valueOf(r.rows.size()));
        check("JSON 导入：码一致", "16-4-9626".equals(r.rows.get(0).code), "");
        check("JSON 导入：状态一致",
                PickupRecord.STATUS_PICKED_UP.equals(r.rows.get(1).status), "");
        check("JSON 导入：取走时间保留", r.rows.get(1).pickedAt == 1_700_000_200_000L, "");

        // CSV 往返
        String csv = PickupExport.toCsv(rows);
        PickupExport.ParseResult r2 = PickupExport.fromCsv(csv);
        check("CSV 导入：解析成功", r2.ok(), r2.error);
        check("CSV 导入：条数正确", r2.rows.size() == 2, String.valueOf(r2.rows.size()));
        check("CSV 导入：码一致", "D4-7048".equals(r2.rows.get(1).code), "");
        check("CSV 导入：来源保留", "菜鸟驿站".equals(r2.rows.get(0).source), "");
        check("CSV 导入：时间保留", r2.rows.get(0).receivedAt == 1_700_000_000_000L, "");

        // CSV 里带特殊字符的字段也要能原样回来
        List<PickupRow> tricky = new ArrayList<>();
        tricky.add(new PickupRow("1-2-3", "驿站,一", "地点\"东\"侧",
                1L, PickupRecord.STATUS_PENDING, 0L));
        PickupExport.ParseResult r3 = PickupExport.fromCsv(PickupExport.toCsv(tricky));
        check("CSV 往返：带逗号来源", r3.rows.size() == 1
                && "驿站,一".equals(r3.rows.get(0).source), r3.error);

        // 坏输入
        PickupExport.ParseResult bad = PickupExport.fromJson("这不是 JSON");
        check("坏 JSON 报错", !bad.ok(), bad.error);
        PickupExport.ParseResult empty = PickupExport.fromJson("");
        check("空文件报错", !empty.ok(), empty.error);
        PickupExport.ParseResult noRec = PickupExport.fromJson("{\"foo\":1}");
        check("缺 records 报错", !noRec.ok(), noRec.error);

        PickupExport.ParseResult badCsv = PickupExport.fromCsv("foo,bar\n1,2");
        check("CSV 表头不对报错", !badCsv.ok(), badCsv.error);

        // 个别坏行跳过，不影响其它行
        String mixed = "{\"records\":["
                + "{\"code\":\"11-1-1\",\"source\":\"a\",\"place\":\"b\",\"receivedAt\":1,\"status\":\"PENDING\",\"pickedAt\":0},"
                + "{\"source\":\"no code\"},"
                + "{\"code\":\"22-2-2\",\"source\":\"c\",\"place\":\"d\",\"receivedAt\":2,\"status\":\"PICKED_UP\",\"pickedAt\":3}"
                + "]}";
        PickupExport.ParseResult mr = PickupExport.fromJson(mixed);
        check("混合内容：好行保留", mr.rows.size() == 2, String.valueOf(mr.rows.size()));
        check("混合内容：坏行计数", mr.skipped == 1, String.valueOf(mr.skipped));

        // 自动识别格式
        check("自动识别 JSON",
                PickupExport.parseAuto(json).rows.size() == 2, "");
        check("自动识别 CSV",
                PickupExport.parseAuto(csv).rows.size() == 2, "");
    }

    // ==================== 规则完整性 ====================

    static void integrityTests() {
        String a = "{\"version\":8}";
        String b = "{\"version\":8}";
        String c = "{\"version\":9}";

        check("双镜像一致 → 通过",
                RuleIntegrity.verify(a, b, null, 8, 7) == RuleIntegrity.OK, "");
        check("双镜像不一致 → 拒绝",
                RuleIntegrity.verify(a, c, null, 8, 7) == RuleIntegrity.MIRROR_MISMATCH, "");
        check("两个都没取到 → 无内容",
                RuleIntegrity.verify(null, null, null, -1, 7) == RuleIntegrity.NO_CONTENT, "");
        check("只有一个镜像 → 单源",
                RuleIntegrity.verify(a, null, null, 8, 7) == RuleIntegrity.SINGLE_SOURCE, "");

        // 同版本锚定
        String hash = RuleIntegrity.sha256(a);
        check("同版本 + 摘要一致 → 通过",
                RuleIntegrity.verify(a, a, hash, 8, 8) == RuleIntegrity.OK, "");
        check("同版本 + 摘要不符 → 拒绝",
                RuleIntegrity.verify(a, a, "deadbeef", 8, 8) == RuleIntegrity.BUNDLED_MISMATCH, "");
        check("不同版本不受同版本锚定约束",
                RuleIntegrity.verify(a, a, "deadbeef", 9, 8) == RuleIntegrity.OK, "");

        check("isNewer：更高版本为真", RuleIntegrity.isNewer(9, 8), "");
        check("isNewer：同版本为假", !RuleIntegrity.isNewer(8, 8), "");
        check("isNewer：更低版本为假", !RuleIntegrity.isNewer(7, 8), "");

        // SHA-256 已知值
        check("sha256(\"abc\")",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                        .equals(RuleIntegrity.sha256("abc")), RuleIntegrity.sha256("abc"));
        check("sha256(\"\")",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                        .equals(RuleIntegrity.sha256("")), "");

        // 摘要文件解析
        check("摘要文件解析（两空格）",
                "abc123".equals(RuleIntegrity.parseHashFile("abc123  rules.json\n")), "");
        check("摘要文件解析（制表符）",
                "abc123".equals(RuleIntegrity.parseHashFile("abc123\trules.json")), "");
        check("摘要文件解析（纯哈希）",
                "abc123".equals(RuleIntegrity.parseHashFile("ABC123")), "");
        check("摘要文件解析（空）",
                "".equals(RuleIntegrity.parseHashFile("   ")), "");
    }
}
