package io.github.okaidev.pickupcode;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real SQLite/Room regression cases from the data-integrity audit. No su or device writes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class AuditDataRegressionTest {
    private Context context;
    private static final String CODE = "1-2-3456";

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("todo_links", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("dedup", Context.MODE_PRIVATE).edit()
                .putStringSet("backend_targets", java.util.Set.of(NotesBackend.TARGET_LOCAL)).commit();
        inWorker(() -> PickupDatabase.get(context).pickupDao().deleteAll());
    }

    @After public void tearDown() throws Exception {
        inWorker(() -> PickupDatabase.get(context).pickupDao().deleteAll());
    }

    @Test public void completionWithoutReceiptDoesNotModifyLegacyOrOrdinaryNotes() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE todo(content TEXT,is_finish INTEGER DEFAULT 0,mark_finish_time INTEGER)");
            for (String text : Arrays.asList("取件码 " + CODE + "｜菜鸟｜测试站｜2026-11-13 10:00",
                    "取件码 11-2-3456｜菜鸟｜测试站｜2026-11-13 10:00",
                    "会议：核对 " + CODE + " 编号", "备注：取件码 " + CODE)) {
                ContentValues value = new ContentValues();
                value.put("content", text);
                db.insertOrThrow("todo", null, value);
            }
            db.execSQL(NotesBackend.markDoneSqlOf("xiaomi", CODE));
            try (Cursor rows = db.rawQuery("SELECT is_finish FROM todo ORDER BY rowid", null)) {
                while (rows.moveToNext()) assertEquals(0, rows.getInt(0));
            }
            assertEquals(1, count(db, NotesBackend.existsSqlOf("xiaomi", CODE)));
            db.execSQL("DELETE FROM todo WHERE rowid=1");
            assertEquals("A longer code must not hide a new code", 0,
                    count(db, NotesBackend.existsSqlOf("xiaomi", CODE)));
        }
    }

    @Test public void ownershipCompletesOnlyInsertedIdAndStillSupportsCustomTemplates() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE todo(content TEXT,is_finish INTEGER DEFAULT 0,mark_finish_time INTEGER)");
            String content = "去测试站拿包裹，备注 O'Brien 100%_";
            ContentValues value = new ContentValues(); value.put("content", content);
            long owned = db.insertOrThrow("todo", null, value);
            long unrelated = db.insertOrThrow("todo", null, value);
            TodoLinks.remember(context, "xiaomi", CODE, NotesBackend.dbPathOf("xiaomi"),
                    "todo", "content", owned, content);
            // Reload from persisted preferences, never from an in-memory ownership cache.
            String where = TodoLinks.notesWhere(context, "xiaomi", CODE);
            assertNotNull(where);
            db.execSQL(NotesBackend.markDoneSqlOf("xiaomi", CODE, where));
            assertEquals(1, count(db, "SELECT is_finish FROM todo WHERE rowid=" + owned));
            assertEquals(0, count(db, "SELECT is_finish FROM todo WHERE rowid=" + unrelated));
        }
    }

    @Test public void editedOrReusedTargetIdNeverFallsBackToCodeMatching() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE todo(content TEXT,is_finish INTEGER DEFAULT 0,mark_finish_time INTEGER)");
            db.execSQL("INSERT INTO todo(rowid,content) VALUES(7,'取件码 1-2-3456｜菜鸟｜测试站')");
            TodoLinks.remember(context, "xiaomi", CODE, NotesBackend.dbPathOf("xiaomi"),
                    "todo", "content", 7, "old content");
            db.execSQL(NotesBackend.markDoneSqlOf("xiaomi", CODE, TodoLinks.notesWhere(context, "xiaomi", CODE)));
            assertEquals(0, count(db, "SELECT is_finish FROM todo WHERE rowid=7"));
        }
    }

    @Test public void invalidCodeCannotWidenLegacyCompletion() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE todo(content TEXT,is_finish INTEGER DEFAULT 0,mark_finish_time INTEGER)");
            db.execSQL("INSERT INTO todo(content) VALUES('ordinary note')");
            for (String code : Arrays.asList("", "%", "_", "' OR 1=1 --", "取")) {
                db.execSQL(NotesBackend.markDoneSqlOf("xiaomi", code));
            }
            assertEquals(0, count(db, "SELECT is_finish FROM todo"));
        }
    }

    @Test public void colorOsCompletionUsesTheSameIdentityGuard() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE Tasks(content TEXT,deleted INTEGER DEFAULT 0,finish_time INTEGER,update_time INTEGER)");
            db.execSQL("INSERT INTO Tasks(content) VALUES('1-2-3456'),('11-2-3456'),('会议：1-2-3456')");
            TodoLinks.remember(context, NotesBackend.BACKEND_COLOROS_TODO, CODE,
                    NotesBackend.dbPathOf(NotesBackend.BACKEND_COLOROS_TODO), "Tasks", "content", 1, CODE);
            db.execSQL(NotesBackend.markDoneSqlOf(NotesBackend.BACKEND_COLOROS_TODO, CODE,
                    TodoLinks.notesWhere(context, NotesBackend.BACKEND_COLOROS_TODO, CODE)));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM Tasks WHERE finish_time IS NOT NULL"));
            db.execSQL("CREATE TABLE rich_notes(text TEXT,summary_content TEXT,top_time INTEGER,update_time INTEGER,deleted INTEGER DEFAULT 0)");
            db.execSQL("INSERT INTO rich_notes(text,summary_content) VALUES('1-2-3456','1'),('11-2-3456','2'),('会议：1-2-3456','3')");
            TodoLinks.remember(context, NotesBackend.BACKEND_COLOROS_NOTE, CODE,
                    NotesBackend.dbPathOf(NotesBackend.BACKEND_COLOROS_NOTE), "rich_notes", "text", 1, CODE);
            db.execSQL(NotesBackend.markDoneSqlOf(NotesBackend.BACKEND_COLOROS_NOTE, CODE,
                    TodoLinks.notesWhere(context, NotesBackend.BACKEND_COLOROS_NOTE, CODE)));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rich_notes WHERE text LIKE '%已取件%'"));
        }
    }

    @Test public void honorCompletionUsesTheSameCodeBoundaryAndOwnedId() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE Tasks(_id INTEGER PRIMARY KEY,title TEXT,complete INTEGER DEFAULT 0,delete_flag INTEGER DEFAULT 0,date_completed INTEGER)");
            db.execSQL("INSERT INTO Tasks(title) VALUES('取件码 1-2-3456｜菜鸟｜测试站'),('取件码 11-2-3456｜菜鸟｜测试站'),('会议：1-2-3456')");
            HonorSql.Schema schema = new HonorSql.Schema("Tasks",
                    java.util.Set.of("_id", "title", "complete", "delete_flag", "date_completed"), java.util.Set.of("Tasks"));
            db.execSQL(HonorSql.doneSql(schema, CODE));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM Tasks WHERE complete=1"));
            TodoLinks.remember(context, NotesBackend.BACKEND_HONOR_TODO, CODE,
                    Honor.DB_TASK, "Tasks", "title", 3, "会议：1-2-3456");
            String owned = TodoLinks.where(context, NotesBackend.BACKEND_HONOR_TODO, CODE,
                    Honor.DB_TASK, "Tasks", "title", "rowid");
            db.execSQL(HonorSql.doneSql(schema, CODE, owned));
            assertEquals(0, count(db, "SELECT complete FROM Tasks WHERE _id=1"));
            assertEquals(1, count(db, "SELECT complete FROM Tasks WHERE _id=3"));
        }
    }

    @Test public void simultaneousDifferentChannelsCreateOnePendingRecord() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            PickupRepository repository = PickupRepository.get(context);
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<PickupRepository.IngestResult>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int channel = i;
                futures.add(workers.submit(() -> {
                    ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                    return repository.ingest("channel" + channel, "body" + channel,
                            List.of(CODE), "source", "测试站", false);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            int inserted = 0;
            for (Future<PickupRepository.IngestResult> future : futures) inserted += future.get(10, TimeUnit.SECONDS).inserted;
            assertEquals(1, inserted);
            assertEquals(1, (int) workers.submit(repository::countAllSync).get(5, TimeUnit.SECONDS));
        } finally { workers.shutdownNow(); }
    }

    @Test public void completedCodeCanBeReusedWithoutDeletingHistory() throws Exception {
        inWorker(() -> {
            PickupRepository repository = PickupRepository.get(context);
            long now = System.currentTimeMillis();
            assertEquals(1, repository.ingest("sender", "same body", List.of(CODE), "source", "测试站", false,
                    now - 2 * PickupRepository.COMPLETED_REPLAY_WINDOW_MS).inserted);
            PickupRecord old = repository.findPendingByCodeSync(CODE);
            PickupDatabase.get(context).pickupDao().markDone(old.id, now - TimeUnit.HOURS.toMillis(25));
            assertEquals(1, repository.ingest("sender", "same body", List.of(CODE), "source", "测试站", false, now).inserted);
            PickupRecord fresh = repository.findPendingByCodeSync(CODE);
            assertNotNull(fresh); assertNotEquals(old.id, fresh.id);
            assertEquals(PickupRecord.STATUS_PICKED_UP, repository.getByIdSync(old.id).status);
            assertEquals(2, repository.countAllSync());
            assertEquals(0, repository.ingest("sender", "same body", List.of(CODE), "source", "测试站", false, now).inserted);
            return null;
        });
    }

    @Test public void oldSmsReplayAndImmediateAppReloadDoNotReopenCompletedParcels() throws Exception {
        inWorker(() -> {
            PickupRepository repository = PickupRepository.get(context);
            long oldEvent = System.currentTimeMillis() - 2 * PickupRepository.COMPLETED_REPLAY_WINDOW_MS;
            repository.ingest("sender", "same body", List.of(CODE), "source", "测试站", false, oldEvent);
            PickupRecord old = repository.findPendingByCodeSync(CODE);
            repository.markDoneSync(old.id);
            assertEquals(0, repository.ingest("sender", "same body", List.of(CODE), "source", "测试站", false, oldEvent).inserted);
            assertNull(repository.findPendingByCodeSync(CODE));
            String appCode = "2-3-4567";
            repository.ingest("App", "data", List.of(appCode), "拼多多", "测试站", false);
            repository.markDoneByCodeSync(appCode);
            assertEquals(0, repository.ingest("App", "data", List.of(appCode), "拼多多", "测试站", false).inserted);
            assertNull(repository.findPendingByCodeSync(appCode));
            return null;
        });
    }

    @Test public void repeatedLoadsDoNotExtendTheReuseWindowForever() throws Exception {
        inWorker(() -> {
            PickupRepository repository = PickupRepository.get(context);
            long now = System.currentTimeMillis();
            repository.ingest("App", "data", List.of(CODE), "拼多多", "测试站", false,
                    now - 2 * PickupRepository.COMPLETED_REPLAY_WINDOW_MS);
            PickupRecord old = repository.findPendingByCodeSync(CODE);
            PickupDatabase.get(context).pickupDao().markDone(old.id, now - TimeUnit.HOURS.toMillis(25));
            PickupDatabase.get(context).pickupDao().touch(old.id, now);
            assertEquals(1, repository.ingest("App", "data", List.of(CODE), "拼多多", "测试站", false, now).inserted);
            return null;
        });
    }

    private static int count(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) { assertTrue(cursor.moveToFirst()); return cursor.getInt(0); }
    }

    private static <T> T inWorker(java.util.concurrent.Callable<T> work) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try { return executor.submit(work).get(10, TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); }
    }
}
