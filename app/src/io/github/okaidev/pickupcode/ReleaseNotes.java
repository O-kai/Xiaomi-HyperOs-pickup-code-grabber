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
     * 前四条是关键改动，最后一条把其余细节统一收口。
     * 控制在五条以内 —— 多了就要滚动，用户明确说不要滚动。
     */
    /**
     * 每条 = { 关键标题, 说明 }。标题单独放大加粗，说明保持正文。
     * 前四条是关键改动，最后一条把其余细节统一收口。
     * 控制在五条以内 —— 多了就要滚动，用户明确说不要滚动。
     */
    static final String[][] HIGHLIGHTS = {
            { "取件更准", "规则集 v13，单段「字母-数字」码不再被拆错，运单尾号不算码，混排码也能认" },
            { "提醒不晚点", "改用系统精确闹钟；一条短信多个码时，通知按码分成多条" },
            { "小组件好加", "长按图标一键添加，有权限直接回桌面；外观设置与实时预览对齐" },
            { "记录能聚拢", "按地点聚拢、新消息整组置顶；「完整信息」能看原文并全文复制" },
            { "其他细节", "取件地址划线、滚轮选时、更新日志随时可看等体验细节" },
    };

    private ReleaseNotes() { }
}
