package io.github.okaidev.pickupcode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小米笔记待办文本的解析（纯 Java，不依赖 Android，可离线单测）。
 *
 * <p>模块自己写进小米笔记的待办长这样：
 * {@code 取件码 6-6-6662｜菜鸟驿站｜XX小区｜2026-10-30 09:47}
 * 导入历史时优先用这里带的信息——比从正文里猜来源与地点准得多。
 */
public final class NotesFormat {

    private NotesFormat() {
    }

    /** 取件码 X｜来源｜地点｜时间（全角/半角竖线都兼容） */
    private static final Pattern P_OWN_FORMAT =
            Pattern.compile("取件码\\s*[^｜|]*[｜|]\\s*([^｜|]*)\\s*[｜|]\\s*([^｜|]*)");
    /** 文本里取【】里的驿站名 */
    private static final Pattern P_BRACKET = Pattern.compile("【([^】]{1,20})】");

    /** 表示「没有值」的占位符 */
    private static final String EMPTY_MARK = "—";

    /**
     * 解析模块自己写的待办格式。
     *
     * @return {来源, 地点}；不是这个格式、或字段是「—」时，对应位置返回空串
     */
    public static String[] ownFormatFields(String body) {
        String[] out = new String[]{"", ""};
        Matcher own = P_OWN_FORMAT.matcher(body == null ? "" : body);
        if (!own.find()) return out;
        String s = own.group(1) == null ? "" : own.group(1).trim();
        String p = own.group(2) == null ? "" : own.group(2).trim();
        out[0] = EMPTY_MARK.equals(s) ? "" : s;
        out[1] = EMPTY_MARK.equals(p) ? "" : p;
        return out;
    }

    /** 取【】里的驿站名；没有就返回空 */
    public static String sourceOf(String body) {
        if (body == null) return "";
        Matcher m = P_BRACKET.matcher(body);
        if (m.find()) return m.group(1);
        return "";
    }
}
