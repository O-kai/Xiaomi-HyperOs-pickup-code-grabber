package io.github.okaidev.pickupcode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Process-local throttle for nested NMS overloads; not the persistent code/place dedup. */
final class NotiEventWindow {
    private static final long WINDOW_MS = 30_000L;
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
