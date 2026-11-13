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
        Notifier.refreshExisting(appContext);
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
        boolean placeChanged = false;

        for (String code : codes) {
            if (code == null || code.trim().isEmpty()) {
                continue;
            }
            String normalizedCode = code.trim();
            String eventHash = sha256(base + "\u0000" + normalizedCode);

            PickupRecord existing = dao.findByEventHash(eventHash);
            if (existing != null) {
                if (DEFAULT_UNKNOWN.equals(existing.place) && !DEFAULT_UNKNOWN.equals(placeVal)) {
                    dao.updatePlace(existing.id, placeVal);
                    placeChanged = true;
                }
                dao.touch(existing.id, now);
                touched++;
                continue;
            }

            // v4.0.6：按「码」去重，不再带地点。
            // 同一个包裹的短信与通知，地点一个有一个没有（—），
            // 带上地点就会被判成两条，列表里并排出现两个相同的码。
            PickupRecord active = dao.findPendingByCode(normalizedCode);
            if (active != null) {
                // 后到的来源若给出更完整的地点，补进去；已有真地点就不覆盖
                if (DEFAULT_UNKNOWN.equals(active.place) && !DEFAULT_UNKNOWN.equals(placeVal)) {
                    dao.updatePlace(active.id, placeVal);
                    placeChanged = true;
                }
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
        if (inserted > 0 || placeChanged) {
            Log.i(TAG, "LOCAL DB: inserted=" + inserted + " touched=" + touched);
            PickupWidgetProvider.refreshAsync(appContext);
            notifyChanged();
            // v4.0.2：写入后顺带做一次过期清理。
            // 用户要求「过了日期立马就清掉」，那清理就不能只发生在用户点按钮时——
            // 每新增一条记录正是最容易发现「有旧记录该清了」的时刻。
            autoPurgeIfNeeded(appContext);
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
        /** 待取件总数（无论是否显示全部模式，这个数字始终是「还有几件没取」） */
        public final int pendingTotal;
        /** true = 显示全部信息模式（items 里含已取件） */
        public final boolean showAll;

        PendingSnapshot(List<PickupRecord> items, int total, int pendingTotal, boolean showAll) {
            this.items = items;
            this.total = total;
            this.pendingTotal = pendingTotal;
            this.showAll = showAll;
        }
    }

    public void pendingSnapshotAsync(int limit, Callback<PendingSnapshot> callback) {
        pendingSnapshotAsync(limit, false, callback);
    }

    /**
     * 小组件数据快照。
     *
     * @param showAll true = 连已取件的一起给（待取在上、已取在下，已取划掉）
     */
    public void pendingSnapshotAsync(int limit, boolean showAll,
                                     Callback<PendingSnapshot> callback) {
        io.execute(() -> {
            final List<PickupRecord> items;
            final int total;
            final int pendingTotal = countPendingSync();
            if (showAll) {
                items = allForWidgetSync(limit);
                total = items.size();
            } else {
                items = pendingSync(limit);
                total = pendingTotal;
            }
            main.post(() -> callback.onResult(
                    new PendingSnapshot(items, total, pendingTotal, showAll)));
        });
    }

    /** 全部记录（待取 + 已取），待取在上。 */
    public List<PickupRecord> allForWidgetSync(int limit) {
        try {
            return dao.allForWidget(Math.max(1, limit));
        } catch (Throwable t) {
            return Collections.emptyList();
        }
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
                // v4.0.1：小组件刷新交给调用方决定要不要带「补位动画」。
                // 这里如果直接 refreshAsync，会先刷一版没有动画的内容，
                // 把动画的中间态顶掉——补位效果就没了。
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

    /**
     * 恢复为「待取」（v4.0.1）。
     *
     * <p>用户反馈「已取的也要有一个按钮把它返回成为待取，防止误操作」——
     * 点错「已取件」之后能反悔，不需要去数据库里改。
     */
    public void restorePendingAsync(long id, Callback<Integer> callback) {
        io.execute(() -> {
            int changed = 0;
            try {
                changed = dao.markPending(id);
                if (changed > 0) {
                    PickupWidgetProvider.refreshAsync(appContext);
                    notifyChanged();
                }
            } catch (Throwable t) {
                Log.w(TAG, "restorePending failed: " + t);
            }
            final int n = changed;
            main.post(() -> callback.onResult(n));
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

    /**
     * 删除【全部】已取件记录（不论时间）。
     *
     * <p>v4.0.2：用户要求把管理页的「立刻清理过期记录」改成
     * 「立刻清理所有已取件信息」——理由是「过了日期应该立马就被清理掉，
     * 不需要我再点击管理并选择『立刻清理过期的记录』」。
     *
     * <p>即：过期清理是<b>自动</b>的（见 {@link #autoPurgeIfNeeded}），
     * 手动按钮就应该是「一次性清掉所有已取件」，两件事分工明确。
     *
     * <p>待取件永远不删——那是要去取的东西。
     */
    public int deleteAllPickedSync() {
        try {
            int n = dao.deleteAllPicked();
            Log.i(TAG, "LOCAL DB: deleted " + n + " picked-up records");
            if (n > 0) {
                PickupWidgetProvider.refreshAsync(appContext);
                notifyChanged();
            }
            return n;
        } catch (Throwable t) {
            Log.w(TAG, "deleteAllPicked failed: " + t);
            return 0;
        }
    }

    public void deleteAllPickedAsync(Callback<Integer> callback) {
        io.execute(() -> {
            final int n = deleteAllPickedSync();
            main.post(() -> callback.onResult(n));
        });
    }

    /**
     * 按当前保留期自动清理过期记录。
     *
     * <p>v4.0.2 新增。用户原话：「如果设置的过期时间是 90 天，
     * 那么过了日期应该立马就被清理掉，不需要我再点击管理并选择
     * 『立刻清理已过期的记录』」。
     *
     * <p>所以自动清理必须真的自动：App 每次启动（以及每次写入记录后）
     * 都跑一次，不需要用户点任何按钮。代价是每次启动一次轻量 SQL——
     * 可以接受，因为本地库本来就在这个进程里。
     *
     * @param ctx 用于读保留期设置
     * @return 实际删除条数
     */
    public int autoPurgeIfNeeded(Context ctx) {
        try {
            int days = retentionDays(ctx);
            if (days <= 0) return 0;   // 永久保留
            return purgeExpiredSync(days);
        } catch (Throwable t) {
            Log.w(TAG, "auto purge failed: " + t);
            return 0;
        }
    }

    /** 自动清理的异步版本（不回调结果，只做清理） */
    public void autoPurgeAsync(Context ctx) {
        io.execute(() -> autoPurgeIfNeeded(ctx));
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
