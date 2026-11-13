package io.github.okaidev.pickupcode;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;

/** Bounded callback association; values cannot keep the callback or its payload alive. */
final class WeakIdentityMap<V> {
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final LinkedHashMap<Key, V> values = new LinkedHashMap<>();
    private final int limit;
    WeakIdentityMap(int limit) { this.limit = limit; }
    synchronized void put(Object key, V value) {
        clean();
        values.remove(new Key(key, null));
        if (value == null) return;
        values.put(new Key(key, queue), value);
        if (values.size() > limit) values.remove(values.keySet().iterator().next());
    }
    synchronized V get(Object key) { clean(); return values.get(new Key(key, null)); }
    private void clean() {
        Key stale;
        while ((stale = (Key) queue.poll()) != null) values.remove(stale);
    }
    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key && get() != null && get() == ((Key) other).get();
        }
    }
}
