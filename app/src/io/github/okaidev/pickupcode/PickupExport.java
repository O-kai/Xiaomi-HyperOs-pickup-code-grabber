package io.github.okaidev.pickupcode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地取件记录的导出 / 导入格式（JSON 与 CSV 双向）。
 *
 * <p>设计约束：
 * <ul>
 *   <li>导出只含结构化字段（码/来源/地点/时间/状态），<b>不含短信正文</b>——
 *       这是本项目一贯的隐私取舍，导出文件也要守住；</li>
 *   <li>导入必须能安全地处理坏文件：解析不了的行跳过，不中断整份导入，
 *       并把「跳过了多少条」如实报给用户；</li>
 *   <li>JSON 供机器读与备份，CSV 供 Excel 打开；两者可以互相转换。</li>
 * </ul>
 *
 * <p>类名里的 JSON 读写依赖 Android 自带的 org.json；单元测试用
 * {@code test/libs/org.json.jar} 放在 classpath 前面跑同一份代码。
 */
public final class PickupExport {

    /** 导出文件里的格式标记，便于将来识别版本 */
    public static final String FORMAT_TAG = "pickup-code-grabber/records";
    /** 当前导出格式版本 */
    public static final int FORMAT_VERSION = 1;

    /** CSV 表头 */
    public static final String CSV_HEADER =
            "code,source,place,receivedAt,status,pickedAt";

    private PickupExport() {
    }

    // ================================================================
    //  导出
    // ================================================================

    /** 导出为 JSON */
    public static String toJson(List<PickupRow> rows, long exportedAt) {
        JSONArray arr = new JSONArray();
        if (rows != null) {
            for (PickupRow r : rows) {
                if (r == null || isBlank(r.code)) continue;
                JSONObject o = new JSONObject();
                try {
                    o.put("code", r.code);
                    o.put("source", nz(r.source));
                    o.put("place", nz(r.place));
                    o.put("receivedAt", r.receivedAt);
                    o.put("status", r.status == null ? PickupRecord.STATUS_PENDING : r.status);
                    o.put("pickedAt", r.pickedAt);
                } catch (JSONException ignored) {
                    // JSONObject.put 只在值为 NaN/Infinite 时抛异常，这里不会发生
                }
                arr.put(o);
            }
        }
        JSONObject root = new JSONObject();
        try {
            root.put("format", FORMAT_TAG);
            root.put("version", FORMAT_VERSION);
            root.put("exportedAt", exportedAt);
            root.put("records", arr);
        } catch (JSONException ignored) {
        }
        return root.toString();
    }

    /** 导出为 CSV（带 BOM，Excel 打开中文不乱码） */
    public static String toCsv(List<PickupRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF'); // UTF-8 BOM
        sb.append(CSV_HEADER).append('\n');
        if (rows != null) {
            for (PickupRow r : rows) {
                if (r == null || isBlank(r.code)) continue;
                sb.append(csvEscape(r.code)).append(',')
                        .append(csvEscape(nz(r.source))).append(',')
                        .append(csvEscape(nz(r.place))).append(',')
                        .append(r.receivedAt).append(',')
                        .append(csvEscape(r.status == null ? PickupRecord.STATUS_PENDING : r.status)).append(',')
                        .append(r.pickedAt).append('\n');
            }
        }
        return sb.toString();
    }

    // ================================================================
    //  导入
    // ================================================================

    /** 解析结果：成功解析的记录 + 跳过的坏行数 + 出错原因 */
    public static class ParseResult {
        public final List<PickupRow> rows = new ArrayList<>();
        /** 跳过的坏行数 */
        public int skipped;
        /** 出错原因（成功时为空） */
        public String error = "";

        public boolean ok() {
            return error.isEmpty();
        }
    }

    /**
     * 从 JSON 导入。
     *
     * <p>容错策略：整体不是合法 JSON → 直接报错；
     * 但个别记录缺字段/类型不对 → 跳过该条并计数，不影响其它记录。
     */
    public static ParseResult fromJson(String text) {
        ParseResult res = new ParseResult();
        if (text == null || text.trim().isEmpty()) {
            res.error = "文件是空的";
            return res;
        }
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Throwable t) {
            res.error = "不是有效的 JSON 文件";
            return res;
        }
        JSONArray arr = root.optJSONArray("records");
        if (arr == null) {
            res.error = "文件里没有 records 列表，可能不是本模块导出的记录";
            return res;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                res.skipped++;
                continue;
            }
            String code = o.optString("code", "").trim();
            if (isBlank(code)) {
                res.skipped++;
                continue;
            }
            PickupRow r = new PickupRow();
            r.code = code;
            r.source = o.optString("source", "");
            r.place = o.optString("place", "");
            r.receivedAt = o.optLong("receivedAt", 0L);
            String st = o.optString("status", PickupRecord.STATUS_PENDING);
            r.status = PickupRecord.STATUS_PICKED_UP.equals(st)
                    ? PickupRecord.STATUS_PICKED_UP : PickupRecord.STATUS_PENDING;
            r.pickedAt = o.optLong("pickedAt", 0L);
            res.rows.add(r);
        }
        return res;
    }

    /**
     * 从 CSV 导入。
     *
     * <p>容错策略：表头不对 → 报错；行字段数不对 → 跳过该行并计数。
     */
    public static ParseResult fromCsv(String text) {
        ParseResult res = new ParseResult();
        if (text == null || text.trim().isEmpty()) {
            res.error = "文件是空的";
            return res;
        }
        String[] lines = text.split("\r?\n");
        int start = 0;
        // 跳过 BOM
        if (lines.length > 0 && lines[0].startsWith("\uFEFF")) {
            lines[0] = lines[0].substring(1);
        }
        // 跳过表头
        if (lines.length > 0) {
            String head = lines[0].trim();
            if (head.toLowerCase(java.util.Locale.US).startsWith("code")) {
                start = 1;
            } else {
                res.error = "CSV 表头不对，应以 code,source,place,receivedAt,status,pickedAt 开头";
                return res;
            }
        }
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.trim().isEmpty()) continue;
            List<String> f = parseCsvLine(line);
            if (f.size() < 3) {
                res.skipped++;
                continue;
            }
            String code = f.get(0).trim();
            if (isBlank(code)) {
                res.skipped++;
                continue;
            }
            PickupRow r = new PickupRow();
            r.code = code;
            r.source = f.get(1).trim();
            r.place = f.get(2).trim();
            try {
                r.receivedAt = f.size() > 3 ? Long.parseLong(f.get(3).trim()) : 0L;
            } catch (Throwable t) {
                r.receivedAt = 0L;
            }
            String st = f.size() > 4 ? f.get(4).trim() : PickupRecord.STATUS_PENDING;
            r.status = PickupRecord.STATUS_PICKED_UP.equals(st)
                    ? PickupRecord.STATUS_PICKED_UP : PickupRecord.STATUS_PENDING;
            try {
                r.pickedAt = f.size() > 5 ? Long.parseLong(f.get(5).trim()) : 0L;
            } catch (Throwable t) {
                r.pickedAt = 0L;
            }
            res.rows.add(r);
        }
        return res;
    }

    /** 自动判断是 JSON 还是 CSV 并解析 */
    public static ParseResult parseAuto(String text) {
        if (text != null) {
            String head = text.trim();
            // CSV 导出带 UTF-8 BOM，Excel 才不会把中文显示成乱码；
            // 但 BOM 会让「以 code 开头」的判断落空，这里先剥掉。
            if (head.startsWith("\uFEFF")) head = head.substring(1).trim();
            if (head.startsWith("{")) return fromJson(text);
            if (head.toLowerCase(java.util.Locale.US).startsWith("code")) {
                return fromCsv(text);
            }
        }
        return fromJson(text);
    }

    // ================================================================
    //  工具
    // ================================================================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** CSV 字段转义：含逗号/引号/换行时用双引号包起来，内部引号翻倍 */
    public static String csvEscape(String s) {
        if (s == null) return "";
        boolean need = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!need) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** 解析一行 CSV（支持带引号字段） */
    public static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuote = true;
                } else if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }
}
