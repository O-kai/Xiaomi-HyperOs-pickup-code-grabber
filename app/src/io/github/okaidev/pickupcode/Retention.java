package io.github.okaidev.pickupcode;

/**
 * 本地取件记录的保留期策略（纯 Java，不依赖 Android，可离线单测）。
 *
 * <p>为什么需要它：本地库会随着时间一直长，不设上限的话一年后就是几万条
 * 死数据，既拖慢查询也占空间。但「自动删除」有风险——用户可能还想翻
 * 三个月前的记录。所以这里把策略定死成几条可预期的规则：
 *
 * <ul>
 *   <li>只清理「已取件」的记录，待取件永远不自动删（那是要去取的东西）；</li>
 *   <li>保留期以「标记已取件的时间」为准，不是收到的时间；</li>
 *   <li>保留期为 0 表示永久保留，一个字都不删。</li>
 * </ul>
 *
 * <p>收到后超过一天还没取的待取件，界面上会标「滞留 N 天」，
 * 但同样不自动删——说不定用户过两天才去。
 * 那条提示在 {@link #staleDays(long, long)} 与 {@link #staleBadge(int)} 里。
 */
public final class Retention {

    /** 永久保留 */
    public static final int KEEP_FOREVER = 0;
    /** 保留 30 天 */
    public static final int KEEP_30D = 30;
    /** 保留 90 天（默认） */
    public static final int KEEP_90D = 90;
    /** 保留 180 天 */
    public static final int KEEP_180D = 180;
    /** 保留 365 天 */
    public static final int KEEP_365D = 365;

    /** 默认保留期 */
    public static final int DEFAULT_DAYS = KEEP_90D;

    /**
     * 收到后超过这个时长（24 小时）就算「滞留」。
     *
     * <p>v4.0.2：从 3 天改成 1 天。原来 3 天才提示，用户反馈
     * 「单纯写『可能』已失效，对于用户来说概念太模糊了」——
     * 于是干脆改成把「滞留了几天」这个事实直接写出来，
     * 阈值也放宽到一天：一��之内的新记录干干净净，超过一天才提示。
     */
    public static final long EXPIRED_AFTER_MS = 24L * 60 * 60 * 1000;

    /** 标签上的天数上限：超过 7 天统一写「滞留 7 天以上」 */
    public static final int STALE_LABEL_MAX_DAYS = 7;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private Retention() {
    }

    /** 可选的保留期（天），供设置页做选择器 */
    public static int[] options() {
        return new int[]{KEEP_FOREVER, KEEP_30D, KEEP_90D, KEEP_180D, KEEP_365D};
    }

    /** 把任意输入夹到合法值上 */
    public static int sanitize(int days) {
        for (int o : options()) {
            if (o == days) return days;
        }
        return DEFAULT_DAYS;
    }

    /**
     * 展示用的文案。
     *
     * <p>v4.0.2：补上「已取件」三个字——用户在选择器里要能一眼看出
     * 这个保留期管的是哪一类记录（待取件永远不删）。
     */
    public static String label(int days) {
        switch (sanitize(days)) {
            case KEEP_FOREVER: return "永久保留";
            case KEEP_30D: return "30 天";
            case KEEP_180D: return "180 天";
            case KEEP_365D: return "1 年";
            default: return "90 天";
        }
    }

    /**
     * 选择器里的完整说明（如「90 天 · 已取件记录保留 90 天后自动清理」）。
     *
     * <p>v4.0.2：用户要求「设置页面写清楚记录时间」——
     * 光一个「90 天」说不清是全部记录还是已取件记录。
     */
    public static String labelWithHint(int days) {
        int d = sanitize(days);
        if (d <= 0) return "永久保留 · 已取件记录也不会自动删除";
        return d + " 天 · 已取件记录保留 " + d + " 天后自动清理";
    }

    /**
     * 清理阈值：早于这个时刻的「已取件」记录可以删。
     *
     * @param days 保留天数，0 表示不清理
     * @param now  当前时刻
     * @return 阈值时间戳；返回 0 表示「不清理」
     */
    public static long cutoffBefore(int days, long now) {
        int d = sanitize(days);
        if (d <= 0) return 0L;
        return now - d * DAY_MS;
    }

    /**
     * 待取件是否「已滞留超过一天」。
     *
     * <p>一天之内的新记录不标任何东西（干净），超过一天才提示。
     *
     * <p>注意：这个判断<b>只用于界面上提示</b>，绝不触发任何删除。
     */
    public static boolean isProbablyExpired(long receivedAt, long now) {
        if (receivedAt <= 0) return false;
        return now - receivedAt > EXPIRED_AFTER_MS;
    }

    /**
     * 滞留天数（不足一天返回 0，即「不提示」）。
     *
     * <p>向下取整：收到 25 小时算 1 天、49 小时算 2 天——
     * 与「昨天收到的」「前天收到的」这种日常说法一致。
     */
    public static int staleDays(long receivedAt, long now) {
        if (receivedAt <= 0) return 0;
        return daysSince(receivedAt, now);
    }

    /**
     * 滞留标签的文案。
     *
     * <p>v4.0.2：原来写的是「可能已过期」，后来改成「可能已失效」——
     * 用户反馈「概念太模糊」，没法据此判断还值不值得跑一趟。
     * 现在直接写事实：滞留了几天。
     *
     * <pre>
     *   0      → ""                （一天之内，不标）
     *   1..6   → "滞留 1 天" … "滞留 6 天"
     *   7      → "滞留 7 天"
     *   &gt;7      → "滞留 7 天以上"    （超过一周基本没救了，再精确只是占地方）
     * </pre>
     */
    public static String staleBadge(int days) {
        if (days <= 0) return "";
        if (days > STALE_LABEL_MAX_DAYS) return "滞留 " + STALE_LABEL_MAX_DAYS + " 天以上";
        return "滞留 " + days + " 天";
    }

    /** 距离收到过了几天（向下取整，最少 0） */
    public static int daysSince(long receivedAt, long now) {
        if (receivedAt <= 0) return 0;
        if (now <= receivedAt) return 0;
        return (int) ((now - receivedAt) / DAY_MS);
    }
}
