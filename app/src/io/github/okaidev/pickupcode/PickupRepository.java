package io.github.okaidev.pickupcode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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

    /**
     * 导入一条外部已有的取件记录（例如从小米笔记导入的历史待办）。
     *
     * <p>eventHash 由调用方给出（形如 "notes:&lt;id&gt;"），同一个来源 ID 只会导入一次，
     * 重复点「导入」不会产生重复记录。
     *
     * @param finished true 表示这条在来源里已经是「已完成」，导入后记为历史
     * @return true 表示确实新插入了一条
     */
    public boolean insertImported(String source, String code, String place,
                                  long receivedAt, boolean finished, String eventHash) {
        try {
            if (code == null || code.trim().isEmpty()) return false;
            if (eventHash == null || eventHash.isEmpty()) return false;
            if (dao.findByEventHash(eventHash) != null) return false;
            // 同一个取件码本地已经有了（模块自己写进去的那批），不再重复导入
            if (dao.findAnyByCode(code.trim()) != null) return false;

            long now = System.currentTimeMillis();
            PickupRecord r = new PickupRecord();
            r.code = code.trim();
            r.source = emptyToUnknown(source);
            r.place = emptyToUnknown(place);
            r.receivedAt = receivedAt > 0 ? receivedAt : now;
            r.createdAt = now;
            r.lastSeenAt = now;
            r.status = finished ? PickupRecord.STATUS_PICKED_UP : PickupRecord.STATUS_PENDING;
            r.pickedAt = finished ? r.receivedAt : 0L;
            r.eventHash = eventHash;
            r.syncState = "NONE";
            r.isTest = 0;
            r.backend = NotesBackend.detect(appContext);
            long id = dao.insert(r);
            if (id > 0) {
                PickupWidgetProvider.refreshAsync(appContext);
                notifyChanged();
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "insertImported failed: " + t);
        }
        return false;
    }

    public void pendingAsync(int limit, Callback<List<PickupRecord>> callback) {
        io.execute(() -> {
            final List<PickupRecord> result = pendingSync(limit);
            main.post(() -> callback.onResult(result));
        });
    }

    /** 小组件用：一次拿到「前 N 条 + 待取总数」，避免两次异步查询对不齐。 */
    public static final class PendingSnapshot {
        public final List<PickupRecord> items;
        public final int total;

        PendingSnapshot(List<PickupRecord> items, int total) {
            this.items = items;
            this.total = total;
        }
    }

    public void pendingSnapshotAsync(int limit, Callback<PendingSnapshot> callback) {
        io.execute(() -> {
            final List<PickupRecord> items = pendingSync(limit);
            final int total = countPendingSync();
            main.post(() -> callback.onResult(new PendingSnapshot(items, total)));
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

    // ================================================================
    //  4.0 收口：搜索 / 筛选 / 保留期 / 导出导入
    // ================================================================

    /**
     * 按状态与关键字查询（status 传 null = 不限；kw 传 null/空 = 不过滤）。
     */
    public void queryAsync(String status, String kw, int limit,
                           Callback<List<PickupRecord>> callback) {
        io.execute(() -> {
            List<PickupRecord> result;
            try {
                String st = (status == null || status.isEmpty()) ? null : status;
                String key = (kw == null || kw.trim().isEmpty()) ? null : kw.trim();
                result = dao.query(st, key, Math.max(1, limit));
            } catch (Throwable t) {
                Log.w(TAG, "query failed: " + t);
                result = Collections.emptyList();
            }
            final List<PickupRecord> finalResult = result;
            main.post(() -> callback.onResult(finalResult));
        });
    }

    /**
     * 按保留期清理：只删「已取件」且超过保留期的记录。
     *
     * @param days 保留天数；0 = 永久保留，不删任何东西
     * @return 实际删除条数
     */
    public int purgeExpiredSync(int days) {
        long cutoff = Retention.cutoffBefore(days, System.currentTimeMillis());
        if (cutoff <= 0) return 0;
        try {
            int n = dao.deletePickedBefore(cutoff);
            if (n > 0) {
                Log.i(TAG, "LOCAL DB: purged " + n + " records older than " + days + "d");
                PickupWidgetProvider.refreshAsync(appContext);
                notifyChanged();
            }
            return n;
        } catch (Throwable t) {
            Log.w(TAG, "purge failed: " + t);
            return 0;
        }
    }

    public void purgeExpiredAsync(int days, Callback<Integer> callback) {
        io.execute(() -> {
            final int n = purgeExpiredSync(days);
            main.post(() -> callback.onResult(n));
        });
    }

    /** 清空全部记录（用户明确二次确认后才允许调用） */
    public int clearAllSync() {
        try {
            int n = dao.deleteAll();
            Log.i(TAG, "LOCAL DB: cleared " + n + " records");
            PickupWidgetProvider.refreshAsync(appContext);
            notifyChanged();
            return n;
        } catch (Throwable t) {
            Log.w(TAG, "clearAll failed: " + t);
            return 0;
        }
    }

    public void clearAllAsync(Callback<Integer> callback) {
        io.execute(() -> {
            final int n = clearAllSync();
            main.post(() -> callback.onResult(n));
        });
    }

    public int countAllSync() {
        try {
            return dao.countAll();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 异步取总数。
     *
     * <p>Room 默认不允许主线程查询，{@link #countAllSync()} 在 UI 线程调用会被拒绝
     * 并被吞掉——表现是界面上的「共 N 条」永远是 0。所以界面上要显示条数，
     * 一律走这个异步版本。
     */
    public void countAllAsync(Callback<Integer> callback) {
        io.execute(() -> {
            final int n = countAllSync();
            main.post(() -> callback.onResult(n));
        });
    }

    /** 导出用：拿全部记录并转成可导出的行 */
    public void exportRowsAsync(Callback<List<PickupRow>> callback) {
        io.execute(() -> {
            List<PickupRow> rows = new ArrayList<>();
            try {
                List<PickupRecord> all = dao.query(null, null, 20000);
                for (PickupRecord r : all) rows.add(PickupRow.of(r));
            } catch (Throwable t) {
                Log.w(TAG, "export rows failed: " + t);
            }
            final List<PickupRow> finalRows = rows;
            main.post(() -> callback.onResult(finalRows));
        });
    }

    /** 导入结果 */
    public static class ImportResult {
        /** 新插入的条数 */
        public int inserted;
        /** 因为已存在而跳过的条数 */
        public int duplicated;
        /** 出错原因（成功时为空） */
        public String error = "";
    }

    /**
     * 导入外部记录。
     *
     * <p>去重规则：本地已有同码的记录 → 跳过（不覆盖用户已有的状态）。
     * 已取件状态会一并带进来，这样从旧设备导出的历史不会变成"待取"。
     */
    public ImportResult importRowsSync(List<PickupRow> rows) {
        ImportResult res = new ImportResult();
        if (rows == null || rows.isEmpty()) return res;
        long now = System.currentTimeMillis();
        for (PickupRow row : rows) {
            try {
                if (row == null || row.code == null || row.code.trim().isEmpty()) continue;
                String code = row.code.trim();
                if (dao.findAnyByCode(code) != null) {
                    res.duplicated++;
                    continue;
                }
                PickupRecord r = new PickupRecord();
                r.code = code;
                r.source = emptyToUnknown(row.source);
                r.place = emptyToUnknown(row.place);
                r.receivedAt = row.receivedAt > 0 ? row.receivedAt : now;
                r.createdAt = now;
                r.lastSeenAt = now;
                boolean picked = PickupRecord.STATUS_PICKED_UP.equals(row.status);
                r.status = picked ? PickupRecord.STATUS_PICKED_UP : PickupRecord.STATUS_PENDING;
                r.pickedAt = picked ? (row.pickedAt > 0 ? row.pickedAt : r.receivedAt) : 0L;
                r.eventHash = "import:" + sha256(code + "\u0000" + r.receivedAt);
                r.syncState = "NONE";
                r.isTest = 0;
                r.backend = NotesBackend.detect(appContext);
                if (dao.insert(r) > 0) res.inserted++;
                else res.duplicated++;
            } catch (Throwable t) {
                Log.w(TAG, "import row failed: " + t);
            }
        }
        if (res.inserted > 0) {
            PickupWidgetProvider.refreshAsync(appContext);
            notifyChanged();
        }
        return res;
    }

    public void importRowsAsync(List<PickupRow> rows, Callback<ImportResult> callback) {
        io.execute(() -> {
            final ImportResult res = importRowsSync(rows);
            main.post(() -> callback.onResult(res));
        });
    }

    // ------------------------------------------------------------
    //  本地库偏好（保留期等）
    // ------------------------------------------------------------

    private static final String PREFS_DATA = "pickup_data";
    private static final String KEY_RETENTION_DAYS = "retention_days";

    /** 保留期（天），0 = 永久保留 */
    public static int retentionDays(Context ctx) {
        try {
            return Retention.sanitize(ctx.getApplicationContext()
                    .getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)
                    .getInt(KEY_RETENTION_DAYS, Retention.DEFAULT_DAYS));
        } catch (Throwable t) {
            return Retention.DEFAULT_DAYS;
        }
    }

    public static void setRetentionDays(Context ctx, int days) {
        try {
            ctx.getApplicationContext()
                    .getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_RETENTION_DAYS, Retention.sanitize(days)).apply();
        } catch (Throwable ignored) {
        }
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
