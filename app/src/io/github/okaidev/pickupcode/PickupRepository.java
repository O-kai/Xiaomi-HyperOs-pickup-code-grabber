package io.github.okaidev.pickupcode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地取件库的仓储层（4.0 Stage 2）。
 *
 * 设计约束：
 *  - 只在 App 进程使用；Hook 进程不引用 Room。
 *  - 提取后的码/来源/地点/时间落库；默认不存短信正文。
 *  - event_hash 保证同一事件经多个 Hook 点重复上报时只落一条。
 *  - 本地记录是事实源；系统待办写穿在后续阶段接入。
 */
public final class PickupRepository {

    private static final String TAG = "PICKUPDEBUG";
    private static final String DEFAULT_UNKNOWN = "—";

    private static volatile PickupRepository INSTANCE;

    private final PickupDao dao;
    private final ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context appContext;

    private PickupRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.dao = PickupDatabase.get(context).pickupDao();
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pickup-db");
            t.setDaemon(true);
            return t;
        });
    }

    public static PickupRepository get(Context context) {
        if (INSTANCE == null) {
            synchronized (PickupRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new PickupRepository(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    public static final class IngestResult {
        public final int inserted;
        public final int touched;

        IngestResult(int inserted, int touched) {
            this.inserted = inserted;
            this.touched = touched;
        }

        public boolean hasNew() {
            return inserted > 0;
        }
    }

    public interface Callback<T> {
        void onResult(T value);
    }

    /**
     * 本地库变更监听（4.0 Stage 2.1）。
     *
     * 为什么需要：Provider 在 Binder 线程写入时，Activity 已经在前台，
     * 不会触发 onResume；首页待取件卡必须能被事件驱动刷新，否则用户
     * 收到短信后看到的还是「0 个」。只在本进程内回调，不走广播。
     */
    public interface ChangeListener {
        void onPickupChanged();
    }

    private static final java.util.concurrent.CopyOnWriteArrayList<ChangeListener> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addChangeListener(ChangeListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeChangeListener(ChangeListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    private void notifyChanged() {
        if (LISTENERS.isEmpty()) return;
        main.post(() -> {
            for (ChangeListener listener : LISTENERS) {
                try {
                    listener.onPickupChanged();
                } catch (Throwable t) {
                    Log.w(TAG, "change listener failed: " + t);
                }
            }
        });
    }

    /** 同步写入，调用方必须是后台线程（Provider Binder / 测试线程）。 */
    public IngestResult ingest(String sender, String body,
                               List<String> codes, String source, String place,
                               boolean isTest) {
        if (codes == null || codes.isEmpty()) {
            return new IngestResult(0, 0);
        }
        long now = System.currentTimeMillis();
        String sourceVal = emptyToUnknown(source);
        String placeVal = emptyToUnknown(place);
        String base = sha256((sender == null ? "" : sender) + "\u0000"
                + (body == null ? "" : body));
        int inserted = 0;
        int touched = 0;

        for (String code : codes) {
            if (code == null || code.trim().isEmpty()) {
                continue;
            }
            String normalizedCode = code.trim();
            String eventHash = sha256(base + "\u0000" + normalizedCode);

            PickupRecord existing = dao.findByEventHash(eventHash);
            if (existing != null) {
                dao.touch(existing.id, now);
                touched++;
                continue;
            }

            PickupRecord active = dao.findActive(normalizedCode, placeVal);
            if (active != null) {
                dao.touch(active.id, now);
                touched++;
                continue;
            }

            PickupRecord record = new PickupRecord();
            record.code = normalizedCode;
            record.source = sourceVal;
            record.place = placeVal;
            record.receivedAt = now;
            record.createdAt = now;
            record.lastSeenAt = now;
            record.status = PickupRecord.STATUS_PENDING;
            record.eventHash = eventHash;
            record.syncState = "PENDING";
            record.isTest = isTest ? 1 : 0;
            long id = dao.insert(record);
            if (id > 0) {
                inserted++;
            } else {
                touched++;
            }
        }
        if (inserted > 0) {
            Log.i(TAG, "LOCAL DB: inserted=" + inserted + " touched=" + touched);
            PickupWidgetProvider.refreshAsync(appContext);
            notifyChanged();
        }
        return new IngestResult(inserted, touched);
    }

    public PickupRecord getByIdSync(long id) {
        try {
            return dao.byId(id);
        } catch (Throwable t) {
            Log.w(TAG, "getById failed: " + t);
            return null;
        }
    }

    public void getByIdAsync(long id, Callback<PickupRecord> callback) {
        io.execute(() -> {
            final PickupRecord record = getByIdSync(id);
            main.post(() -> callback.onResult(record));
        });
    }

    public PickupRecord findPendingByCodeSync(String code) {
        try {
            return dao.findPendingByCode(code);
        } catch (Throwable t) {
            Log.w(TAG, "findPendingByCode failed: " + t);
            return null;
        }
    }

    public int markDoneByCodeSync(String code) {
        PickupRecord record = findPendingByCodeSync(code);
        return record == null ? 0 : markDoneSync(record.id);
    }

    public List<PickupRecord> pendingSync(int limit) {
        try {
            return dao.pendingLimit(Math.max(1, limit));
        } catch (Throwable t) {
            Log.w(TAG, "pending query failed: " + t);
            return Collections.emptyList();
        }
    }

    public int countPendingSync() {
        try {
            return dao.countPending();
        } catch (Throwable t) {
            return 0;
        }
    }

    public void pendingAsync(int limit, Callback<List<PickupRecord>> callback) {
        io.execute(() -> {
            final List<PickupRecord> result = pendingSync(limit);
            main.post(() -> callback.onResult(result));
        });
    }

    public void recentAsync(int limit, Callback<List<PickupRecord>> callback) {
        io.execute(() -> {
            List<PickupRecord> result;
            try {
                result = dao.recent(Math.max(1, limit));
            } catch (Throwable t) {
                result = Collections.emptyList();
            }
            final List<PickupRecord> finalResult = result;
            main.post(() -> callback.onResult(finalResult));
        });
    }

    public int markDoneSync(long id) {
        try {
            int changed = dao.markDone(id, System.currentTimeMillis());
            if (changed > 0) {
                PickupWidgetProvider.refreshAsync(appContext);
                notifyChanged();
            }
            return changed;
        } catch (Throwable t) {
            Log.w(TAG, "markDone failed: " + t);
            return 0;
        }
    }

    public void markDoneAsync(long id, Callback<Integer> callback) {
        io.execute(() -> {
            final int changed = markDoneSync(id);
            main.post(() -> callback.onResult(changed));
        });
    }

    public void deleteAsync(long id) {
        io.execute(() -> {
            try {
                dao.delete(id);
                PickupWidgetProvider.refreshAsync(appContext);
                notifyChanged();
            } catch (Throwable t) {
                Log.w(TAG, "delete failed: " + t);
            }
        });
    }

    private static String emptyToUnknown(String s) {
        return s == null || s.trim().isEmpty() ? DEFAULT_UNKNOWN : s.trim();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
