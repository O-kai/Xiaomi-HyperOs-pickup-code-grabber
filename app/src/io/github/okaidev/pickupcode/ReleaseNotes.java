package io.github.okaidev.pickupcode;

/**
 * 更新日志的唯一数据源（v4.2.0 起）。
 *
 * <p>用户明确要求：**不显示历史版本、不出现上下滑动**。所以这里只保留当前版本，
 * 内容压到一屏；历史版本的来龙去脉写在 CHANGELOG.md 里，不在弹窗里堆叠。
 *
 * <p>发版时只改三处：
 * <ol>
 *   <li>{@link #CURRENT_CODE} / {@link #CURRENT_VERSION}；</li>
 *   <li>{@link #HIGHLIGHTS} —— 只写关键改动，不关键的合并成最后一句；</li>
 *   <li>version.properties 的两个字段（独立写，不推导）。</li>
 * </ol>
 */
final class ReleaseNotes {

    /** 与 version.properties 的 VERSION_CODE 一致 */
    static final int CURRENT_CODE = 420;
    /** 与 version.properties 的 VERSION_NAME 一致（界面统一带 V 前缀） */
    static final String CURRENT_VERSION = "V4.2.0";

    static final String QQ_GROUP = "901543676";

    /**
     * 每条 = { 关键标题, 说明 }。标题单独放大加粗，说明保持正文。
     *
     * <p>顺序按用户价值锁定（2026-11-19 确认），发版时不要按改代码的时间排：
     * <ol>
     *   <li>桌面小组件 —— 4.2.0 的最大更新，讲清「现在能用什么」；</li>
     *   <li>取件记录 —— 地点聚拢 / 时间倒序；</li>
     *   <li>规则集 —— 认得更准；</li>
     *   <li>提醒 —— 到点就响。</li>
     *   <li>小地方 —— 一句话带过零散改进，留白让大家自己发现。</li>
     * </ol>
     * 只写用户能感知的价值与功能，不写「闪一下」「弹回桌面」这类开发过程描述。
     * 「更新日志」「QQ 群」不列为更新要点（QQ 群按钮是弹窗常驻入口，另算）。
     * 控制在五条 —— 多了就要滚动，用户明确说不要滚动。
     */
    static final String[][] HIGHLIGHTS = {
            { "桌面小组件", "待取件直接显示在桌面，一眼就能看到；长按图标添加，配色、透明度、字号都能按喜好调" },
            { "取件记录", "可切换「时间倒序」和「地点聚拢」：同地点的件归在一起，新消息整组置顶；点「完整信息」看原文" },
            { "取件更准", "规则集 v13：单段字母-数字码不再被拆错，运单尾号不算码，混排码也能认" },
            { "提醒不晚点", "精确闹钟到点就响；一条短信多个码时，通知按码分成多条" },
            { "小地方", "已取地址划线、配色试过再存、自检能复制诊断信息等" },
    };

    private ReleaseNotes() { }
}
