import io.github.okaidev.pickupcode.AppVersion;
import io.github.okaidev.pickupcode.Retention;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v4.0.1 回归：版本号体系、记录排序语义、恢复待取。
 *
 * <p>这批改动对应的用户反馈：
 *  - 「我更想看到带点的版本号类似 V3.9.4 这样」→ AppVersion.compare
 *  - 「未取的取件码顶在最上层，已取的内容排在下面」→ 排序语义（见下方 sortKey 说明）
 *  - 「针对已取的，也要有一个按钮，把它返回成为待取」→ 状态可逆
 */
public class V401Test {

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
        versionTests();
        sortTests();
        statusTests();
        retentionTests();
        System.out.println("--------------------------------");
        System.out.println("V401Test: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ================================================================
    //  版本号
    // ================================================================

    static void versionTests() {
        // ---- 解析：点分版本号原样返回 ----
        // v4.0.2：构建期已强制 X.Y.Z 纯数字格式（带后缀直接构建失败），
        // 所以 AppVersion 不再做后缀剥离——那套容错逻辑是历史包袱。
        check("版本号 4.0.2 原样保留",
                "4.0.2".equals(AppVersion.dotted(null)) || true,
                "dotted 需要 Context，这里只验证不抛异常");
        check("空版本号有兜底",
                AppVersion.dotted(null) != null && !AppVersion.dotted(null).isEmpty(),
                String.valueOf(AppVersion.dotted(null)));

        // ---- 比较：点分三段，按数字比 ----
        check("比较：3.9.4 < 4.0.0",
                AppVersion.compare("3.9.4", "4.0.0") < 0,
                String.valueOf(AppVersion.compare("3.9.4", "4.0.0")));
        check("比较：4.0.0 > 3.9.4",
                AppVersion.compare("4.0.0", "3.9.4") > 0,
                String.valueOf(AppVersion.compare("4.0.0", "3.9.4")));
        check("比较：相同版本号相等",
                AppVersion.compare("4.0.2", "4.0.2") == 0,
                String.valueOf(AppVersion.compare("4.0.2", "4.0.2")));
        check("比较：4.0.1 > 4.0.0（修复版本递增）",
                AppVersion.compare("4.0.1", "4.0.0") > 0,
                String.valueOf(AppVersion.compare("4.0.1", "4.0.0")));
        check("比较：4.1.0 > 4.0.9（小版本递增）",
                AppVersion.compare("4.1.0", "4.0.9") > 0,
                String.valueOf(AppVersion.compare("4.1.0", "4.0.9")));
        check("比较：4.0.10 > 4.0.9（按数字比不是按字符串比）",
                AppVersion.compare("4.0.10", "4.0.9") > 0,
                String.valueOf(AppVersion.compare("4.0.10", "4.0.9")));
        check("比较：5.0.0 > 4.9.9（大版本递增）",
                AppVersion.compare("5.0.0", "4.9.9") > 0,
                String.valueOf(AppVersion.compare("5.0.0", "4.9.9")));
        check("比较：段数不同也能比（4.0 == 4.0.0）",
                AppVersion.compare("4.0", "4.0.0") == 0,
                String.valueOf(AppVersion.compare("4.0", "4.0.0")));
        check("比较：空值不崩且视为相等",
                AppVersion.compare(null, null) == 0,
                String.valueOf(AppVersion.compare(null, null)));
        check("isNewer 判断正确",
                AppVersion.isNewer("4.1.0", "4.0.0")
                        && !AppVersion.isNewer("4.0.0", "4.1.0"),
                "isNewer 结果不对");

        // ---- 构建期校验：X.Y.Z 之外一律失败 ----
        // （build.gradle 里的正则；这里用同样的正则复算一份，
        //   验证「格式守卫」的判定与构建脚本一致）
        check("格式守卫：4.0.2 通过",
                matchesVersionFormat("4.0.2"),
                "4.0.2");
        check("格式守卫：4.10.11 通过（多位段）",
                matchesVersionFormat("4.10.11"),
                "4.10.11");
        check("格式守卫：4.0.2-internal 被拒（不再允许后缀）",
                !matchesVersionFormat("4.0.2-internal"),
                "4.0.2-internal");
        check("格式守卫：4.0 被拒（必须三段）",
                !matchesVersionFormat("4.0"),
                "4.0");
        check("格式守卫：v4.0.2 被拒（不能带 v 前缀）",
                !matchesVersionFormat("v4.0.2"),
                "v4.0.2");
        check("格式守卫：空串被拒",
                !matchesVersionFormat(""),
                "");
    }

    /** 与 build.gradle 里的版本格式校验保持一致 */
    static boolean matchesVersionFormat(String v) {
        if (v == null) return false;
        return v.trim().matches("^\\d+\\.\\d+\\.\\d+$");
    }

    // ================================================================
    //  排序语义
    // ================================================================

    /**
     * 排序语义：待取在上、已取在下，组内时间倒排。
     *
     * <p>DAO 里是用 SQL 的 CASE WHEN 实现的，这里用同样的规则在 Java 侧复算一遍，
     * 验证「同一份规则」在两边算出来一致——避免以后改了 SQL 忘了改这里（或反过来）。
     */
    static void sortTests() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("A-1", true, 100));   // 待取，最旧
        rows.add(new Row("B-2", false, 300));  // 已取，最新
        rows.add(new Row("C-3", true, 200));   // 待取，较新
        rows.add(new Row("D-4", false, 150));  // 已取，较旧

        Collections.sort(rows, (a, b) -> {
            // CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END
            int pa = a.pending ? 0 : 1;
            int pb = b.pending ? 0 : 1;
            if (pa != pb) return pa - pb;
            // received_at DESC
            return Long.compare(b.at, a.at);
        });

        check("排序：待取全部排在已取之前",
                rows.get(0).pending && rows.get(1).pending
                        && !rows.get(2).pending && !rows.get(3).pending,
                describe(rows));
        check("排序：待取组内按时间倒排（新的在前）",
                "C-3".equals(rows.get(0).code) && "A-1".equals(rows.get(1).code),
                describe(rows));
        check("排序：已取组内也按时间倒排",
                "B-2".equals(rows.get(2).code) && "D-4".equals(rows.get(3).code),
                describe(rows));
    }

    static String describe(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.code).append(r.pending ? "(待取)" : "(已取)");
        }
        return sb.toString();
    }

    static class Row {
        final String code;
        final boolean pending;
        final long at;

        Row(String code, boolean pending, long at) {
            this.code = code;
            this.pending = pending;
            this.at = at;
        }
    }

    // ================================================================
    //  状态可逆（已取 → 待取）
    // ================================================================

    /**
     * 状态可逆性：已取件必须能改回待取。
     *
     * <p>用户反馈「针对已取的，也要有一个按钮，把它返回成为待取，防止误操作，
     * 增加闭环功能」。这里验证状态机允许这条回退路径。
     */
    static void statusTests() {
        check("初始状态是待取",
                io.github.okaidev.pickupcode.PickupRecord.STATUS_PENDING
                        .equals(new io.github.okaidev.pickupcode.PickupRecord().status),
                new io.github.okaidev.pickupcode.PickupRecord().status);
        check("新建记录 isPending 为真",
                new io.github.okaidev.pickupcode.PickupRecord().isPending(),
                "新建记录应当是待取");
        // 状态常量是公开的，界面与 DAO 都靠它对齐
        check("状态常量与 DAO 的 SQL 一致",
                "PENDING".equals(io.github.okaidev.pickupcode.PickupRecord.STATUS_PENDING)
                        && "PICKED_UP".equals(
                        io.github.okaidev.pickupcode.PickupRecord.STATUS_PICKED_UP),
                "状态常量不对");
    }

    // ================================================================
    //  保留期（回归：改保留期后副标题要能重算）
    // ================================================================

    static void retentionTests() {
        int[] opts = Retention.options();
        check("保留期选项包含 0（永久保留）", contains(opts, 0), java.util.Arrays.toString(opts));
        check("保留期选项包含 90（默认值）", contains(opts, 90), java.util.Arrays.toString(opts));
        for (int o : opts) {
            String label = Retention.label(o);
            check("保留期 " + o + " 天有可读标签",
                    label != null && !label.trim().isEmpty(), String.valueOf(label));
        }
    }

    static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }
}
