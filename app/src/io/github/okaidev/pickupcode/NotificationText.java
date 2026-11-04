package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;

import java.util.LinkedHashSet;

/**
 * 通知文本提取（v4.0.2 第八轮，来自 PR #14）。
 *
 * <p><b>为什么单独抽一个类</b>：这段逻辑在 system_server 里跑，
 * 任何一次异常都会带崩系统进程。抽出来之后可以离线单测，
 * 也能把「只取哪些字段、最多取多少」写成明确的约定。
 *
 * <p><b>边界</b>：
 * <ul>
 *   <li>只读 {@link Notification} 里<b>有文档的公开字段</b>
 *       （title / text / bigText / subText / summaryText / infoText / textLines /
 *        {@code android.messages} / tickerText），
 *       不再遍历 extras 里的任意私有键——那是别人 App 的内部结构，
 *       形态随版本变化，读它既不准也不安全；</li>
 *   <li>总长度上限 {@link #MAX_CHARS}，单条上限同样是 MAX_CHARS，
 *       条目数上限 {@link #MAX_ITEMS}——防止某个 App 塞一条超长通知把系统拖慢；</li>
 *   <li>任何异常都吞掉并返回已收集到的部分：<b>绝不让 system_server 崩</b>。</li>
 * </ul>
 *
 * <p><b>保留的兼容逻辑</b>：MessagingStyle 的正文在部分 ROM 上不在
 * {@code android.messages} 里，仍用反射读一次（@hide API，失败即跳过）。
 * 电商 App（拼多多/淘宝/京东）大量使用这种样式。
 */
final class NotificationText {

    private static final int MAX_CHARS = 8192;
    private static final int MAX_ITEMS = 32;
    private static final int MAX_PIECES = 128;

    static String build(Notification n) {
        LinkedHashSet<String> pieces = new LinkedHashSet<>();
        if (n == null) return "";
        try {
            Bundle e = n.extras;
            if (e != null) {
                String[] keys = {
                        Notification.EXTRA_TITLE,
                        Notification.EXTRA_TEXT,
                        Notification.EXTRA_BIG_TEXT,
                        Notification.EXTRA_SUB_TEXT,
                        Notification.EXTRA_SUMMARY_TEXT,
                        Notification.EXTRA_INFO_TEXT,
                };
                for (String key : keys) add(pieces, e.getCharSequence(key));

                CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) {
                    for (int i = 0; i < lines.length && i < MAX_ITEMS; i++) add(pieces, lines[i]);
                }
                // MessagingStyle 的标准化导出（Android 7+ 写入 android.messages）
                Parcelable[] messages = e.getParcelableArray("android.messages");
                if (messages != null) {
                    for (int i = Math.max(0, messages.length - MAX_ITEMS);
                         i < messages.length; i++) {
                        if (messages[i] instanceof Bundle) {
                            add(pieces, ((Bundle) messages[i]).getCharSequence("text"));
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // OEM 自定义 extras 结构异常时不能崩 system_server
        }

        // 兜底：部分 ROM 的 android.messages 为空，用 @hide API 再取一次
        try {
            java.lang.reflect.Method m = Notification.MessagingStyle.class
                    .getDeclaredMethod("extractMessagingStyleFromNotification",
                            Notification.class);
            m.setAccessible(true);
            Object ms = m.invoke(null, n);
            if (ms instanceof Notification.MessagingStyle) {
                for (Notification.MessagingStyle.Message msg
                        : ((Notification.MessagingStyle) ms).getMessages()) {
                    if (msg != null) add(pieces, msg.getText());
                }
            }
        } catch (Throwable ignored) { }

        add(pieces, n.tickerText);

        StringBuilder out = new StringBuilder();
        for (String piece : pieces) {
            if (out.length() > 0) out.append('\n');
            int remaining = MAX_CHARS - out.length();
            if (remaining <= 0) break;
            out.append(piece, 0, Math.min(piece.length(), remaining));
            if (out.length() >= MAX_CHARS) break;
        }
        return out.toString();
    }

    private static void add(LinkedHashSet<String> pieces, CharSequence value) {
        if (value == null || pieces.size() >= MAX_PIECES) return;
        int len = Math.min(value.length(), MAX_CHARS);
        String text = value.subSequence(0, len).toString().trim();
        if (!text.isEmpty()) pieces.add(text);
    }

    private NotificationText() { }
}
