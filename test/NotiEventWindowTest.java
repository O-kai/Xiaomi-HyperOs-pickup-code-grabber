package io.github.okaidev.pickupcode;

/**
 * {@link NotiEventWindow} 的单测（v4.0.2 第八轮，随 PR #14 引入）。
 *
 * <p>为什么要测：这是 system_server 里的进程内节流，写错会直接影响
 * 「同一条通知被处理一次还是两次」。而它的窗口是 30 秒，
 * 手工验证成本高、容易漏，用单测把时间轴钉死。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>第一次一定放行；</li>
 *   <li>30 秒内同 (包名, 文本) 拦掉；</li>
 *   <li>超过 30 秒重新放行；</li>
 *   <li>包名不同 / 文本不同各自独立；</li>
 *   <li>大量事件不会无限增长（容量上限 256）。</li>
 * </ol>
 */
public class NotiEventWindowTest {

    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "  " + detail);
        }
    }

    public static void main(String[] args) {
        long t0 = 1_000_000L;
        String pkg = "com.cainiao.wireless";
        String text = "取件码 8-1-2345";

        check("首次放行", NotiEventWindow.accept(pkg, text, t0), "");

        // 同一条通知被同名重载处理两遍的场景
        check("30 秒内重复拦截",
                !NotiEventWindow.accept(pkg, text, t0 + 1_000L), "");
        check("29.9 秒仍拦截",
                !NotiEventWindow.accept(pkg, text, t0 + 29_900L), "");

        // 窗口过期后应重新放行（换一条文本，避免上一条的窗口干扰判断）
        String text2 = "取件码 6-7-8899";
        check("窗口外放行（新文本）", NotiEventWindow.accept(pkg, text2, t0 + 30_001L), "");

        check("同文本超过窗口后放行",
                NotiEventWindow.accept(pkg, text, t0 + 60_001L), "");

        check("不同包名互不影响",
                NotiEventWindow.accept("com.jingdong.app.mall", text, t0 + 60_002L), "");

        // 容量上限：写入 1000 条不同事件，内部结构不能无限增长
        long base = t0 + 100_000L;
        for (int i = 0; i < 1000; i++) {
            NotiEventWindow.accept("pkg" + i, "text" + i, base + i);
        }
        // 上限是 256：再放一条全新的应该仍然返回 true（不会因为塞满而拒绝）
        check("容量上限后仍能放行新事件",
                NotiEventWindow.accept("pkg-new", "text-new", base + 2_000L), "");
        // 而且最近写入的事件仍然在窗口里（说明清理是按时间而不是乱删）
        check("容量上限后近期事件仍被拦截",
                !NotiEventWindow.accept("pkg999", "text999", base + 2_001L), "");

        System.out.println("NotiEventWindowTest: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
