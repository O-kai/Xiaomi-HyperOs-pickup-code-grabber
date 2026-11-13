package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private ownership receipts. Row ID plus unchanged text protects against ID reuse/edits. */
final class TodoLinks {
    private static final String PREFS = "todo_links";

    private static String key(String backend, String code) { return backend + ":" + code; }

    static boolean hasReceipt(Context context, String backend, String code) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(key(backend, code));
    }

    static synchronized void remember(Context context, String backend, String code,
                                      String db, String table, String textColumn,
                                      long id, String content) {
        if (code == null || id <= 0) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String key = key(backend, code);
            JSONArray entries = new JSONArray(prefs.getString(key, "[]"));
            // Replace a repeated receipt, retaining other rows from partially successful writes.
            JSONArray out = new JSONArray();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                if (!(db.equals(item.getString("db")) && table.equals(item.getString("table"))
                        && id == item.getLong("id"))) out.put(item);
            }
            out.put(new JSONObject().put("db", db).put("table", table).put("column", textColumn)
                    .put("id", id).put("content", content));
            if (!prefs.edit().putString(key, out.toString()).commit()) {
                Log.w("PICKUPDEBUG", "Could not persist todo ownership receipt");
            }
        } catch (Exception e) {
            Log.w("PICKUPDEBUG", "Could not persist todo ownership: " + e.getClass().getSimpleName());
        }
    }

    /** Null means legacy record; a present receipt never falls back to code matching. */
    static String where(Context context, String backend, String code, String db,
                        String table, String textColumn, String idColumn) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(key(backend, code), null);
        if (value == null) return null;
        try {
            JSONArray entries = new JSONArray(value);
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                if (!db.equals(item.getString("db")) || !table.equals(item.getString("table"))
                        || !textColumn.equals(item.getString("column"))) continue;
                long id = item.getLong("id");
                if (id <= 0) continue;
                if (where.length() > 0) where.append(" OR ");
                where.append('(').append(idColumn).append('=').append(id).append(" AND ")
                        .append(textColumn).append('=').append(TodoMatch.quote(item.getString("content")))
                        .append(')');
            }
            return where.length() == 0 ? "0" : "(" + where + ")";
        } catch (Exception e) {
            return "0"; // A damaged receipt must never widen an UPDATE.
        }
    }

    static String notesWhere(Context context, String backend, String code) {
        return where(context, backend, code, NotesBackend.dbPathOf(backend),
                table(backend), column(backend), "rowid");
    }

    static String table(String backend) {
        return NotesBackend.BACKEND_COLOROS_NOTE.equals(backend) ? "rich_notes"
                : NotesBackend.BACKEND_COLOROS_TODO.equals(backend) ? "Tasks" : "todo";
    }

    static String column(String backend) {
        return NotesBackend.BACKEND_COLOROS_NOTE.equals(backend) ? "text" : "content";
    }

    private TodoLinks() { }
}
