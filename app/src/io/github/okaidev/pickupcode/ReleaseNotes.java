package io.github.okaidev.pickupcode;

import java.util.ArrayList;
import java.util.List;

/**
 * 更新日志的<b>唯一数据源</b>（v4.2.0 起）。
 *
 * <p>以前要点写在 LauncherActivity 里、历史另写一份数组，每次发版要改两处，
 * 而且历史越攒越长。现在只需要：
 * <ol>
 *   <li>在 {@link #ENTRIES} 最上面加一条新版本（code / version / headline / bullets）；</li>
 *   <li>把上一条的 bullets 压成 headline 一句话（bullets 可以留着，历史区不会显示）；</li>
 *   <li>改 version.properties 的两个字段（VERSION_NAME + VERSION_CODE，独立写，不推导）。</li>
 * </ol>
 * 历史区自动只显示最近 {@link #MAX_HISTORY} 条，更早的折叠成一句，
 * 所以老内容会自然缩减，不用手工删。
 *
 * <p>发版检查清单里必须有这一步 —— 忘了改这里，用户更新后看到的就是上版本的要点。
 */
final class ReleaseNotes {

    static final class Entry {
        /** 与 version.properties 的 VERSION_CODE 一致；用于判断「当前版本」 */
        final int code;
        /** 界面显示用，例如 V4.2.0 */
        final String version;
        /** 历史区的一句话概括 */
        final String headline;
        /** 当前版本的详细要点（只有当前版本会显示） */
        final String[] bullets;

        Entry(int code, String version, String headline, String[] bullets) {
            this.code = code;
            this.version = version;
            this.headline = headline;
            this.bullets = bullets;
        }
    }

    /** 新的在最上面。发版时只加顶部这一条。 */
    static final Entry[] ENTRIES = {
            new Entry(420, "V4.2.0",
                    "取件规则 v13 + 小组件与取件记录改造",
                    new String[] {
                            "取件规则 v13：单段「字母-数字」码不再被拆成两个错码，运单尾号排除，混排码可识别",
                            "取件记录新增「地址聚拢」：同一地点的件归在一起，新消息整组置顶",
                            "取件记录新增「完整信息」：展开看短信/通知原文，可一键全文复制",
                            "桌面小组件：两个添加入口合一，有权限直接回桌面；提醒改精确闹钟，不再晚点",
                            "一条短信多个取件码时，通知按码分成多条；已取件地址也一起划掉",
                            "首页版本号可点开「更新日志」；开发者 QQ 群支持一键复制",
                            "其他若干细节与稳定性修复",
                    }),
            new Entry(410, "V4.1.0",
                    "荣耀 MagicOS 适配：补齐第三家 ROM 的待办写入（社区 PR）", null),
            new Entry(406, "V4.0.6",
                    "去重只认取件码；直写开关热生效；不再动不动让人重启", null),
            new Entry(405, "V4.0.5",
                    "一条短信里的多个取件码；规则集 v10", null),
            new Entry(403, "V4.0.3",
                    "升级提示、清理与卸载、配置可读性", null),
            new Entry(400, "V4.0.0",
                    "本地取件库 + 桌面小组件 + 简洁 / 进阶双模式 + 深色主题", null),
    };

    /** 历史区最多显示几条，更早的折叠成一句 */
    private static final int MAX_HISTORY = 5;

    /**
     * 当前版本条目。
     *
     * <p>如果 version.properties 还没跟着改（发版前的预演），找不到对应 code，
     * 就返回最新那条 —— 这样界面上能提前看到即将发布的要点。
     */
    static Entry current(int code) {
        if (ENTRIES.length == 0) return null;
        // 发版前的预演：version.properties 还没改，运行码低于数据里的最新版，
        // 这时也先展示最新那条，方便在真机上核对文案。
        if (ENTRIES[0].code > code) return ENTRIES[0];
        for (Entry e : ENTRIES) {
            if (e.code == code) return e;
        }
        return ENTRIES[0];
    }

    /** 历史条目（跳过当前版本），最多 MAX_HISTORY 条 */
    static List<Entry> history(int code) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ENTRIES) {
            if (e.code == code) continue;
            out.add(e);
            if (out.size() >= MAX_HISTORY) break;
        }
        return out;
    }

    /** 被折叠掉的历史版本数量（0 = 没有更早的） */
    static int olderCount(int code) {
        int seen = 0, total = 0;
        for (Entry e : ENTRIES) {
            if (e.code == code) continue;
            total++;
            if (seen < MAX_HISTORY) seen++;
        }
        return Math.max(0, total - seen);
    }

    private ReleaseNotes() { }
}
