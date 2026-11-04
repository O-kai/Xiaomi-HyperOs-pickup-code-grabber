package io.github.okaidev.pickupcode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知事件的进程内节流（v4.0.2 第八轮，来自 PR #14）。
 *
 * <p><b>为什么需要</b>：{@code NotificationManagerService.enqueueNotificationInternal}
 * 在新系统上有<b>同名重载</b>，一次入队会连续走进两个重载，
 * 同一条通知因此被处理两遍。表现是同一个取件码被上报两次——
 * 最终靠持久化去重挡掉，但只要两遍之间隔了去重写入的时间窗，
 * 或者文案不完全一致，就会重复写待办、重复发通知。
 *
 * <p><b>与持久化去重的关系</b>：这里只做「30 秒内同一包名 + 同一文本不重复处理」，
 * 是<b>进程内</b>的粗筛，不替代 {@code TodoWriter} 里按「码 + 地点」指纹的持久化去重。
 * 进程重启后本窗口清空，不影响跨重启去重。
 *
 * <p>运行在 system_server 的多线程环境，方法加锁。
 */
final class NotiEventWindow {

    /** 同一 (包名, 文本) 在这个窗口内只处理一次 */
    private static final long WINDOW_MS = 30_000L;
    /** 最多记多少条，防止白名单 App 大量通知把内存撑大 */
    private static final int MAX_EVENTS = 256;

    private static final LinkedHashMap<String, Long> RECENT = new LinkedHashMap<>();

    static synchronized boolean accept(String pkg, String text, long now) {
        String key = pkg + '\u0000' + text;
        Long previous = RECENT.get(key);
        if (previous != null && now >= previous && now - previous < WINDOW_MS) return false;

        Iterator<Map.Entry<String, Long>> it = RECENT.entrySet().iterator();
        while (it.hasNext()) {
            long then = it.next().getValue();
            if (now < then || now - then >= WINDOW_MS) it.remove();
        }
        RECENT.put(key, now);
        while (RECENT.size() > MAX_EVENTS) {
            Iterator<String> keys = RECENT.keySet().iterator();
            keys.next();
            keys.remove();
        }
        return true;
    }

    private NotiEventWindow() { }
}
