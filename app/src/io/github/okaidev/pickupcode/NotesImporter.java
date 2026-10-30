package io.github.okaidev.pickupcode;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 历史取件记录导入（4.0.x）
 *
 * <p>3.1.0 时代没有本地库，取件码只落在小米笔记的待办里。
 * 升级到 4.0 后用户会发现「历史」空了一截——那些老待办还在小米笔记里，
 * 但模块看不见。这个类负责把它们读回来，导入本地库：
 *
 * <ul>
 *   <li>已完成的 → 记为历史（已取件）</li>
 *   <li>未完成的 → 仍然出现在待取件清单里</li>
 * </ul>
 *
 * <p>读取走 root + 内置 sqlite3（与写入同一套机制），查询结果用
 * {@code |PICKUPROW|} 分隔，避免正文里的换行与竖线把行拆坏。
 */
public final class NotesImporter {

    private NotesImporter() { }

    private static final String TAG = "PICKUPDEBUG";
    private static final String SEP = "|PICKUPROW|";
    /** 文本里取【】里的驿站名 */
    private static final Pattern P_BRACKET = Pattern.compile("【([^】]{1,20})】");
    /**
     * 模块自己写进小米笔记的待办格式：取件码 X｜来源｜地点｜时间
     * （全角/半角竖线都兼容）。这类记录带不了更多上下文，
     * 但来源与地点是现成的，比从正文里猜更准。
     */
    private static final Pattern P_OWN_FORMAT =
            Pattern.compile("取件码\\s*[^｜|]*[｜|]\\s*([^｜|]*)\\s*[｜|]\\s*([^｜|]*)");

    public static class Result {
        /** 扫描到的待办条数 */
        public int scanned;
        /** 实际导入条数 */
        public int imported;
        /** 不是取件码、被跳过的条数 */
        public int skipped;
        /** 结果说明（人话） */
        public String message = "";

        public boolean ok() {
            return message.isEmpty();
        }
    }

    /**
     * 从小米笔记导入历史取件记录。
     *
     * @param limit 最多扫描多少条待办（避免一次读太多）
     */
    public static Result importHistory(Context ctx, int limit) {
        Result res = new Result();
        try {
            String backend = NotesBackend.detect(ctx);
            if (!NotesBackend.BACKEND_XIAOMI.equals(backend)) {
                res.message = "当前写入后端不是小米笔记，暂不支持导入历史";
                return res;
            }

            String sql = ".mode list\n"
                    + ".separator " + SEP + "\n"
                    + "select id, is_finish, create_time, mark_finish_time,\n"
                    + "replace(replace(coalesce(plain_text, content), char(10), ' '), char(13), ' ')\n"
                    + "from todo where coalesce(plain_text, '') <> ''\n"
                    + "order by id desc limit " + Math.max(1, limit) + ";\n";

            String sqlPath;
            try {
                File f = new File(ctx.getCacheDir(), "pickup_import.sql");
                writeText(f, sql);
                sqlPath = f.getAbsolutePath();
            } catch (Throwable t) {
                res.message = "写入查询文件失败：" + t.getMessage();
                return res;
            }

            String[] out = Diagnostics.suExecPublic(
                    "LD_LIBRARY_PATH=" + Repair.LIBS + " " + Repair.SQLITE + " "
                            + NotesBackend.dbPath(ctx) + " < " + sqlPath + " 2>&1; echo "
                            + Diagnostics.PROBE_MARK, 30);
            if (!Diagnostics.suRan(out)) {
                res.message = "读取小米笔记失败：root 未授权或 sqlite3 未部署";
                return res;
            }
            String text = Diagnostics.suStdout(out);

            PickupRepository repo = PickupRepository.get(ctx);
            for (String line : text.split("\n")) {
                String row = line.trim();
                if (row.isEmpty() || row.startsWith("Error:")) continue;
                String[] parts = row.split(java.util.regex.Pattern.quote(SEP), -1);
                if (parts.length < 5) continue;
                res.scanned++;
                try {
                    long id = Long.parseLong(parts[0].trim());
                    boolean finished = "1".equals(parts[1].trim());
                    long created = parseLong(parts[2]);
                    long finishedAt = parseLong(parts[3]);
                    String body = parts[4].trim();

                    List<String> codes = PickupExtractor.extract(body);
                    if (codes == null || codes.isEmpty()) {
                        res.skipped++;
                        continue;
                    }
                    String place = PickupExtractor.extractPlace(body);
                    String source = sourceOf(body);
                    // 模块自己写的待办：优先用它带的信息（更准）
                    Matcher own = P_OWN_FORMAT.matcher(body);
                    if (own.find()) {
                        String s2 = own.group(1) == null ? "" : own.group(1).trim();
                        String p2 = own.group(2) == null ? "" : own.group(2).trim();
                        if (!s2.isEmpty() && !"—".equals(s2)) source = s2;
                        if (!p2.isEmpty() && !"—".equals(p2)) place = p2;
                    }
                    long when = finished && finishedAt > 0 ? finishedAt : created;
                    String hash = "notes:" + id;
                    boolean ok = false;
                    for (String c : codes) {
                        if (repo.insertImported(source, c, place, when, finished, hash + ":" + c)) {
                            ok = true;
                        }
                    }
                    if (ok) res.imported++; else res.skipped++;
                } catch (Throwable t) {
                    res.skipped++;
                }
            }
            Log.i(TAG, "IMPORT: scanned=" + res.scanned + " imported=" + res.imported
                    + " skipped=" + res.skipped);
        } catch (Throwable t) {
            Log.w(TAG, "IMPORT failed: " + t);
            res.message = "导入异常：" + t.getMessage();
        }
        return res;
    }

    /** 取【】里的驿站名；没有就返回空 */
    private static String sourceOf(String body) {
        Matcher m = P_BRACKET.matcher(body);
        if (m.find()) return m.group(1);
        return "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void writeText(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }
}
