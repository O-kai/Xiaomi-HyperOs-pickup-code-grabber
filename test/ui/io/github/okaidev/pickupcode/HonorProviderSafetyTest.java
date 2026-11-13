package io.github.okaidev.pickupcode;

import android.Manifest;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Actual ContentResolver -> provider -> SQLite path; never invokes root. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class HonorProviderSafetyTest {
    public static class Tasks extends ContentProvider {
        SQLiteDatabase db;
        @Override public boolean onCreate() {
            db = SQLiteDatabase.create(null);
            db.execSQL("CREATE TABLE Tasks(_id INTEGER PRIMARY KEY,uuid TEXT,title TEXT,complete INTEGER DEFAULT 0,delete_flag INTEGER DEFAULT 0,date_completed INTEGER,modify_time INTEGER,dirty INTEGER)");
            return true;
        }
        @Override public Uri insert(Uri uri, ContentValues values) {
            ContentValues kept = new ContentValues();
            kept.put("uuid", values.getAsString("uuid"));
            kept.put("title", values.getAsString("title"));
            return ContentUris.withAppendedId(uri, db.insertOrThrow("Tasks", null, kept));
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            if (uri.getPathSegments().size() > 1) {
                selection = "_id=?"; args = new String[]{uri.getLastPathSegment()};
            }
            return db.query("Tasks", projection, selection, args, null, null, order);
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return db.update("Tasks", values, selection, args);
        }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { return null; }
    }

    @Test public void providerUsesInsertedIdEvenForIdenticalCustomText() {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf((Application) context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR);
        context.getSharedPreferences("todo_links", Context.MODE_PRIVATE).edit().clear().commit();
        Tasks provider = Robolectric.buildContentProvider(Tasks.class).create("com.android.calendar.task").get();
        try {
            provider.db.execSQL("INSERT INTO Tasks(title) VALUES('同名自定义待办'),('取件码 11-2-3456｜菜鸟｜测试站'),('普通备注：1-2-3456')");
            assertTrue(HonorWriter.write(context, NotesBackend.BACKEND_HONOR_TODO, "1-2-3456", "同名自定义待办", "ignored"));
            assertTrue(HonorWriter.markDone(context, NotesBackend.BACKEND_HONOR_TODO, "1-2-3456"));
            try (Cursor cursor = provider.db.rawQuery("SELECT _id,complete FROM Tasks ORDER BY _id", null)) {
                for (int id = 1; id <= 4; id++) {
                    assertTrue(cursor.moveToNext());
                    assertEquals(id, cursor.getInt(0));
                    assertEquals(id == 4 ? 1 : 0, cursor.getInt(1));
                }
            }
        } finally { provider.db.close(); }
    }

    @Test public void legacyTasksWithoutReceiptsAreLeftUntouched() {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf((Application) context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR);
        context.getSharedPreferences("todo_links", Context.MODE_PRIVATE).edit().clear().commit();
        Tasks provider = Robolectric.buildContentProvider(Tasks.class).create("com.android.calendar.task").get();
        try {
            provider.db.execSQL("INSERT INTO Tasks(title) VALUES('1-2-3456'),('取件码 1-2-3456｜菜鸟｜测试站'),('普通备注：1-2-3456')");
            assertFalse(HonorWriter.markDone(context, NotesBackend.BACKEND_HONOR_TODO, "1-2-3456"));
            try (Cursor cursor = provider.db.rawQuery("SELECT COUNT(*) FROM Tasks WHERE complete=1", null)) {
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0));
            }
        } finally { provider.db.close(); }
    }
}
