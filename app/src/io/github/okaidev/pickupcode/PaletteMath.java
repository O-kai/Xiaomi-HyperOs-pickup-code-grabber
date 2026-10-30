package io.github.okaidev.pickupcode;

/**
 * 配色的纯计算部分（不依赖 Android，可离线单测）。
 *
 * <p>为什么要单独抽出来：深色配色是用 HSL 从浅色强调色推导出来的，
 * 而「压在强调色上的字是白还是深墨」又要按对比度反推。
 * 这两件事是整个界���里最容易悄悄坏掉、坏掉时又最不容易被肉眼发现的逻辑
 * ——对比度从 4.5 掉到 2.9 界面看着「就是有点浅」，并不会报错。
 *
 * <p>抽到这里之后，单元测试可以直接验证「四套配色 × 明暗两套共 8 种
 * 强调色，每一种的 onAccent 对比度都 ≥ 4.5」，不会和实现漂移。
 */
public final class PaletteMath {

    private PaletteMath() {
    }

    /** 深色模式下压在强调色上的文字/图标颜色（比纯黑柔和一点） */
    public static final int DARK_INK = 0xFF1F1F1F;

    public static final int WHITE = 0xFFFFFFFF;

    /**
     * 四套配色的浅色强调色。
     *
     * <p>放在这里而不是散在 UiKit 的四个 Palette 定义里，是为了让单元测试
     * 能直接引用同一份值——否则测试里抄一份 hex，改配色时测试不会跟着变，
     * 「对比度达标」就成了空话。
     */
    public static final String ACCENT_SAND = "#A8813C";
    public static final String ACCENT_MIST = "#4A6FA5";
    public static final String ACCENT_TEAL = "#2F7F6F";
    public static final String ACCENT_ROSE = "#A65D78";

    /**
     * 压在给定底色上的字色：白或深墨，取对比度更高的一方。
     *
     * <p>这解决的是真实存在过的问题：
     * 深色模式把强调色提亮后（要在深底上醒目），白字对比度会掉到 1.9–2.9；
     * 浅色模式的米金强调色上白字也只有 3.58。都不能算「看得清」。
     */
    public static int inkOn(int bgColor) {
        double withWhite = PureColor.contrastRatio(bgColor, WHITE);
        double withDark = PureColor.contrastRatio(bgColor, DARK_INK);
        return withWhite >= withDark ? WHITE : DARK_INK;
    }

    /** 一套深色配色的计算结果 */
    public static final class Dark {
        public final int bg, card, cardSoft, track,
                hero, heroWarn, heroInk, heroSubInk, heroWarnInk, heroWarnSubInk,
                accent, accentDeep, accentInk, accentSoft, accentTint,
                label, muteBg, muteInk, navInk, chipBg, line;

        Dark(int bg, int card, int cardSoft, int track,
            int hero, int heroWarn, int heroInk, int heroSubInk,
            int heroWarnInk, int heroWarnSubInk,
            int accent, int accentDeep, int accentInk, int accentSoft, int accentTint,
            int label, int muteBg, int muteInk, int navInk, int chipBg, int line) {
            this.bg = bg; this.card = card; this.cardSoft = cardSoft; this.track = track;
            this.hero = hero; this.heroWarn = heroWarn;
            this.heroInk = heroInk; this.heroSubInk = heroSubInk;
            this.heroWarnInk = heroWarnInk; this.heroWarnSubInk = heroWarnSubInk;
            this.accent = accent; this.accentDeep = accentDeep; this.accentInk = accentInk;
            this.accentSoft = accentSoft; this.accentTint = accentTint;
            this.label = label; this.muteBg = muteBg; this.muteInk = muteInk;
            this.navInk = navInk; this.chipBg = chipBg; this.line = line;
        }
    }

    /**
     * 由浅色强调色推导整套夜间配色。
     *
     * <p>做法是按角色重新给亮度，而不是整体反色：
     * 页面底最暗 → 卡片略亮 → 分割线再亮一点；文字统一提亮；
     * 强调色提高明度，保证在深底上仍然醒目。
     */
    public static Dark darkOf(int lightAccent) {
        float h = PureColor.argbToHsl(lightAccent)[0];
        return new Dark(
                hsl(h, 0.08f, 0.060f),   // bg
                hsl(h, 0.08f, 0.115f),  // card
                hsl(h, 0.09f, 0.145f),  // cardSoft
                hsl(h, 0.10f, 0.185f),  // track
                hsl(h, 0.30f, 0.185f),  // hero
                hsl(16f, 0.34f, 0.165f), // heroWarn
                hsl(h, 0.28f, 0.93f),   // heroInk
                hsl(h, 0.16f, 0.70f),   // heroSubInk
                hsl(16f, 0.50f, 0.82f), // heroWarnInk
                hsl(16f, 0.34f, 0.66f), // heroWarnSubInk
                hsl(h, 0.44f, 0.63f),   // accent
                hsl(h, 0.48f, 0.75f),   // accentDeep
                hsl(h, 0.55f, 0.82f),   // accentInk
                hsl(h, 0.34f, 0.185f),  // accentSoft
                hsl(h, 0.30f, 0.135f),  // accentTint
                hsl(h, 0.10f, 0.62f),   // label
                hsl(h, 0.10f, 0.165f),  // muteBg
                hsl(h, 0.09f, 0.55f),   // muteInk
                hsl(h, 0.06f, 0.93f),   // navInk
                hsl(h, 0.13f, 0.205f),  // chipBg
                hsl(h, 0.10f, 0.235f)); // line
    }

    private static int hsl(float h, float s, float l) {
        return PureColor.hslToArgb(h, s, l);
    }
}
