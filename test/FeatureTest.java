import io.github.okaidev.pickupcode.NotesFormat;
import io.github.okaidev.pickupcode.PaletteMath;
import io.github.okaidev.pickupcode.PureColor;
import io.github.okaidev.pickupcode.ReminderSchedule;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 4.0 新增功能的离线单测。
 *
 * <p>覆盖三块最容易在后续迭代里悄悄坏掉、又最不容易靠肉眼发现的逻辑：
 * 深色配色的推导与对比度、每日提醒的调度时间计算、历史导入的待办解析。
 *
 * <p>这些类都不依赖 Android 运行时（颜色换算抽到了 PureColor，
 * 时间计算抽成了纯函数，解析是纯字符串处理），所以能在 JVM 上直接跑。
 */
public class FeatureTest {

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
        colorTests();
        paletteContrastTests();
        reminderTests();
        importerTests();
        System.out.println("--------------------------------");
        System.out.println("FeatureTest: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ==================== 深色配色 ====================

    static void colorTests() {
        // HSL 往返：同一色相转回去应基本一致
        int base = 0xFF4A6FA5;
        float[] hsl = PureColor.argbToHsl(base);
        int back = PureColor.hslToArgb(hsl[0], hsl[1], hsl[2]);
        int dr = Math.abs(((base >> 16) & 0xFF) - ((back >> 16) & 0xFF));
        int dg = Math.abs(((base >> 8) & 0xFF) - ((back >> 8) & 0xFF));
        int db = Math.abs((base & 0xFF) - (back & 0xFF));
        check("HSL 往返误差 <=2", dr <= 2 && dg <= 2 && db <= 2,
                "dr=" + dr + " dg=" + dg + " db=" + db);

        // 已知值：纯红
        int red = PureColor.hslToArgb(0f, 1f, 0.5f);
        check("HSL(0,100%,50%) = 纯红", red == 0xFFFF0000, Integer.toHexString(red));

        // 已知值：白 / 黑
        check("HSL(0,0%,100%) = 白", PureColor.hslToArgb(0f, 0f, 1f) == 0xFFFFFFFF, "");
        check("HSL(0,0%,0%) = 黑", PureColor.hslToArgb(0f, 0f, 0f) == 0xFF000000, "");

        // 对比度：黑字白底应为最高对比
        double bw = PureColor.contrastRatio(0xFF000000, 0xFFFFFFFF);
        check("黑白对比度 >= 20", bw >= 20.0, String.valueOf(bw));

        // 深色配色必须「文字比背景亮」
        check("深色底(0.115L) 与深色文字(0.93L) 对比度 >= 7",
                PureColor.contrastRatio(
                        PureColor.hslToArgb(214f, 0.08f, 0.115f),
                        PureColor.hslToArgb(214f, 0.28f, 0.93f)) >= 7.0, "");

        // 浅色配色：白底上的深色文字
        check("浅色底与 navInk 对比度 >= 7",
                PureColor.contrastRatio(0xFFFFFFFF, 0xFF26303E) >= 7.0, "");

        // 强调色按钮：白字压在强调色上必须可读
        // 注意：这一条在 4.0 改成了「按底色自动反推字色」，
        // 所以不能再假定强调色上一定是白字——浅色模式的米金用深墨，
        // 深色模式四套配色全部用深墨。真正的不变量是「字色对底色的对比度」。
        int accent = PureColor.hslToArgb(214f, 0.44f, 0.63f);
        int onAccent = PaletteMath.inkOn(accent);
        check("强调色上的字色对比度 >= 4.5",
                PureColor.contrastRatio(accent, onAccent) >= 4.5,
                "contrast=" + PureColor.contrastRatio(accent, onAccent));
    }

    // ==================== 四套配色的对比度回归 ====================

    /**
     * 这是 4.0 真机验收后发现并修掉的真实缺陷：
     * 深色模式把强调色提亮后，白字压在上面的对比度掉到 1.9–2.9；
     * 浅色模式的米金上白字也只有 3.58。按钮看着有颜色，字却看不清。
     *
     * <p>修法是引入 onAccent（按底色反推字色），这里锁住不变量：
     * 四套配色 × 明暗两套 = 8 种强调色，每种的 onAccent 对比度都必须 ≥ 4.5。
     */
    static void paletteContrastTests() {
        String[] names = {"米金", "雾霾蓝", "青瓷绿", "藕荷玫瑰"};
        String[] lightAccents = {
                PaletteMath.ACCENT_SAND, PaletteMath.ACCENT_MIST,
                PaletteMath.ACCENT_TEAL, PaletteMath.ACCENT_ROSE};

        for (int i = 0; i < lightAccents.length; i++) {
            int light = hex(lightAccents[i]);
            int on = PaletteMath.inkOn(light);
            double c = PureColor.contrastRatio(light, on);
            check("浅色 " + names[i] + "：onAccent 对比度 >= 4.5", c >= 4.5,
                    "contrast=" + c + " ink=" + Integer.toHexString(on));

            PaletteMath.Dark d = PaletteMath.darkOf(light);
            int don = PaletteMath.inkOn(d.accent);
            double dc = PureColor.contrastRatio(d.accent, don);
            check("深色 " + names[i] + "：onAccent 对比度 >= 4.5", dc >= 4.5,
                    "contrast=" + dc + " ink=" + Integer.toHexString(don));

            // 强调色必须能从卡片底上分辨出来（图形元素 3:1 下限）
            double sep = PureColor.contrastRatio(d.accent, d.card);
            check("深色 " + names[i] + "：强调色与卡片底可分辨 >= 3", sep >= 3.0,
                    "contrast=" + sep);

            // 深色模式的正文色必须能从卡片底上读出来
            double ink = PureColor.contrastRatio(d.card, d.navInk);
            check("深色 " + names[i] + "：正文与卡片底对比度 >= 7", ink >= 7.0,
                    "contrast=" + ink);

            // 强调色与卡片底不能太近，否则按钮会「糊」进背景
            double tooClose = PureColor.contrastRatio(d.accent, d.card);
            check("深色 " + names[i] + "：强调色与卡片底拉开 >= 3", tooClose >= 3.0,
                    "contrast=" + tooClose);
        }

        // 深色模式下正文与卡片底不能「反过来」——文字必须比底亮
        PaletteMath.Dark d = PaletteMath.darkOf(hex(PaletteMath.ACCENT_MIST));
        check("深色：正文亮度高于卡片底",
                PureColor.relativeLuminance(d.navInk) > PureColor.relativeLuminance(d.card), "");

        // onAccent 的选取原则：给白与深墨里对比度更高的那个
        int whiteBg = 0xFFFFFFFF;
        check("白底选深墨", PaletteMath.inkOn(whiteBg) == PaletteMath.DARK_INK, "");
        check("黑底选白", PaletteMath.inkOn(0xFF000000) == PaletteMath.WHITE, "");
    }

    static int hex(String s) {
        return (int) Long.parseLong(s.substring(1), 16) | 0xFF000000;
    }

    // ==================== 每日提醒调度 ====================

    static void reminderTests() {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 30, 8, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        long eightAm = c.getTimeInMillis();

        // 今天 08:00 还没到 → 触发点就是今天 08:00
        long now = eightAm - 60 * 60 * 1000L;
        long next = ReminderSchedule.nextTriggerAt(8, 0, now);
        check("未到点 → 今天 08:00", next == eightAm, String.valueOf(next - eightAm));

        // 今天 08:00 已过 → 顺延到明天 08:00
        long now2 = eightAm + 60 * 60 * 1000L;
        long next2 = ReminderSchedule.nextTriggerAt(8, 0, now2);
        check("已过点 → 明天 08:00", next2 == eightAm + 24 * 3600 * 1000L,
                String.valueOf((next2 - eightAm) / 3600000L) + "h");

        // 恰好等于触发点 → 也要顺延（避免原地反复触发）
        long next3 = ReminderSchedule.nextTriggerAt(8, 0, eightAm);
        check("恰好等于触发点 → 顺延到明天", next3 == eightAm + 24 * 3600 * 1000L, "");

        // 分钟数会被清零
        Calendar c2 = Calendar.getInstance();
        c2.setTimeInMillis(eightAm + 37 * 60 * 1000L);
        long next4 = ReminderSchedule.nextTriggerAt(8, 30, c2.getTimeInMillis());
        Calendar c3 = Calendar.getInstance();
        c3.setTimeInMillis(next4);
        check("触发点分钟精确为设定值", c3.get(Calendar.MINUTE) == 30
                && c3.get(Calendar.SECOND) == 0, "");
    }

    // ==================== 历史导入解析 ====================

    static void importerTests() {
        // 模块自己写的待办格式
        String[] f = NotesFormat.ownFormatFields("取件码 6-6-6662｜菜鸟驿站｜XX小区｜2026-10-30 09:47");
        check("自有格式：来源正确", "菜鸟驿站".equals(f[0]), String.valueOf(f[0]));
        check("自有格式：地点正确", "XX小区".equals(f[1]), String.valueOf(f[1]));

        // 半角竖线也兼容
        String[] f2 = NotesFormat.ownFormatFields("取件码 1-2-3456|顺丰|楼下货架|2026-10-30 09:47");
        check("自有格式：半角竖线兼容", "顺丰".equals(f2[0]) && "楼下货架".equals(f2[1]),
                f2[0] + "/" + f2[1]);

        // 字段是「—」时返回空（表示没有信息，不要把破折号写进库里）
        String[] f3 = NotesFormat.ownFormatFields("取件码 6-6-6662｜菜鸟驿站｜—｜2026-10-30 09:47");
        check("自有格式：「—」视为空地点", "菜鸟驿站".equals(f3[0]) && "".equals(f3[1]),
                f3[0] + "/" + f3[1]);

        // 不是自有格式 → 两个都空
        String[] f4 = NotesFormat.ownFormatFields("【菜鸟驿站】您的包裹已到XX小区快递驿站，取件码 6-6-6662");
        check("非自有格式 → 不误解析", "".equals(f4[0]) && "".equals(f4[1]),
                f4[0] + "/" + f4[1]);

        // 【】里的驿站名
        check("【】来源解析", "菜鸟驿站".equals(NotesFormat.sourceOf("【菜鸟驿站】取件码 6-6-6662")), "");
        check("无【】时来源为空", "".equals(NotesFormat.sourceOf("取件码 6-6-6662")), "");
    }
}
