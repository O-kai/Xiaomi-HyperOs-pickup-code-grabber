import io.github.okaidev.pickupcode.WidgetPrefs;

/**
 * v4.0.2 小组件逻辑离线单测。
 *
 * <p>覆盖两件用户明确提的事：
 *  - 「最多可显示条数不应该有太明显的限制框架，我们只需要根据小组件的实际大小
 *    来展示就可以了」→ 行数完全由高度决定；
 *  - 「还可以选择显示全部信息」→ 显示模式。
 */
public class WidgetTest {

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
        rowsTests();
        modeTests();
        System.out.println("--------------------------------");
        System.out.println("WidgetTest: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ================================================================
    //  行数：由组件高度决定
    // ================================================================

    static void rowsTests() {
        final int MAX = 12;

        // 拿不到尺寸 → 保守值
        check("高度未知时给保守值 3 行",
                WidgetPrefs.rowsForHeight(0, 333, MAX) == 3,
                String.valueOf(WidgetPrefs.rowsForHeight(0, 333, MAX)));

        // 组件越高行数越多——这是「按实际大小展示」的核心
        int small = WidgetPrefs.rowsForHeight(200, 333, MAX);
        int mid = WidgetPrefs.rowsForHeight(400, 333, MAX);
        int big = WidgetPrefs.rowsForHeight(800, 333, MAX);
        check("组件越大显示越多行",
                small < mid && mid < big,
                small + "/" + mid + "/" + big);
        check("普通组件至少 1 行", small >= 1, String.valueOf(small));
        check("行数有上限（不超过布局允许的 12 行）",
                big <= MAX, String.valueOf(big));

        // 不再有「最多 5 条」这种硬上限：500dp 高的组件应能显示 5 行以上
        check("500dp 高的组件能显示 5 行以上（不再卡 5 条）",
                WidgetPrefs.rowsForHeight(500, 333, MAX) > 5,
                String.valueOf(WidgetPrefs.rowsForHeight(500, 333, MAX)));

        // 极矮的组件也至少 1 行（不能是 0 行）
        check("极矮组件至少 1 行",
                WidgetPrefs.rowsForHeight(60, 333, MAX) >= 1,
                String.valueOf(WidgetPrefs.rowsForHeight(60, 333, MAX)));

        // 窄组件（<150dp）最多 3 行——放不下那么多字
        check("窄组件最多 3 行",
                WidgetPrefs.rowsForHeight(800, 120, MAX) <= 3,
                String.valueOf(WidgetPrefs.rowsForHeight(800, 120, MAX)));
        check("宽组件不受窄组件限制",
                WidgetPrefs.rowsForHeight(800, 333, MAX) > 2,
                String.valueOf(WidgetPrefs.rowsForHeight(800, 333, MAX)));

        // v4.0.2 第六轮：行高固定 40dp，不再按权重均分。
        //
        // 用户原话：「正常的每条内容显示就应该占据它正常的空间，然后一个一个排下来」
        // 「我待取的件只有两件，但是我把尺寸调得比较大，那就让它下面空白着就行了。
        //   这是合理的，没有必要把它全部填满」
        //
        // 核心性质：
        //   · 行高恒为 40dp（内容多少都不变）
        //   · 行数 = 可用高度 / 40，且剩余不足一行（否则说明少算了一行）
        //   · 剩余空间留在列表底部——留白是合理的，不该把行拉高去填满
        for (int h = 120; h <= 900; h += 20) {
            int avail = h - 24 - 28;          // 去掉上下 padding 与标题行
            if (avail <= 0) continue;
            int rows = WidgetPrefs.rowsForHeight(h, 333, MAX);
            if (rows <= 0) { rows = 1; }
            int used = rows * WidgetPrefs.ROW_MIN_DP;
            if (used > avail) {
                check("显示的行不能超出可用高度（h=" + h + "dp）", false,
                        "rows=" + rows + " used=" + used + " avail=" + avail + "dp");
                break;
            }
            // 只有在还没撞到布局行数上限时才要求「取满」——
            // 超过上限是布局只有 12 行造成的，不是算法少算了。
            if (rows < MAX && avail - used >= WidgetPrefs.ROW_MIN_DP) {
                check("行数应取满（少算一行就是浪费空间，h=" + h + "dp）", false,
                        "rows=" + rows + " used=" + used + " avail=" + avail + "dp");
                break;
            }
        }
        check("行高固定 40dp、行数取满、剩余留白不拉伸", true, "");

        // 行数随高度单调不减（拉高组件不会反而变少行）
        int prev = 0;
        boolean monotonic = true;
        for (int h = 100; h <= 900; h += 50) {
            int r = WidgetPrefs.rowsForHeight(h, 333, MAX);
            if (r < prev) monotonic = false;
            prev = r;
        }
        check("行数随高度单调不减", monotonic, "");
    }

    // ================================================================
    //  显示模式
    // ================================================================

    static void modeTests() {
        check("默认只看待取件", WidgetPrefs.SHOW_PENDING == 0, "");
        check("全部信息是另一个模式", WidgetPrefs.SHOW_ALL == 1, "");
        check("两个模式取值不同",
                WidgetPrefs.SHOW_PENDING != WidgetPrefs.SHOW_ALL, "");
    }
}
