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
 * <p>超过三天还没取的待取件，界面上会标「可能已过期」，但同样不自动删——
 * 这条提示在 {@link #isProbablyExpired(long, long)} 里。
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

    /** 待取件超过这个天数就提示「可能已过期」 */
    public static final long EXPIRED_AFTER_MS = 3L * 24 * 60 * 60 * 1000;

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

    /** 展示用的文案 */
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
     * 待取件是否「可能已过期」。
     *
     * <p>超过三天没取的件多半驿站已经清掉了，提醒一下用户，
     * 但绝不自动删——说不定用户过两天才去。
     */
    public static boolean isProbablyExpired(long receivedAt, long now) {
        if (receivedAt <= 0) return false;
        return now - receivedAt > EXPIRED_AFTER_MS;
    }

    /** 距离收到过了几天（向下取整，最少 0） */
    public static int daysSince(long receivedAt, long now) {
        if (receivedAt <= 0) return 0;
        if (now <= receivedAt) return 0;
        return (int) ((now - receivedAt) / DAY_MS);
    }
}
