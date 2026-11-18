package io.github.okaidev.pickupcode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 取件记录的来源原文（v4.2.1）。
 *
 * <p>一条取件记录可能由多条消息聚成（短信 + 通知），所以原文按列表存，
 * 存在 {@code pickup_records.raw_sources} 这一列里，JSON 数组：
 * <pre>
 * [{"type":"短信","from":"1069...","at":1731990000000,"text":"【菜鸟驿站】..."}]
 * </pre>
 *
 * <p>为什么不拆一张表：原文只有几百字节，一条记录通常 1~2 条来源；
 * 加一列 + 一次 Migration 就能覆盖，表和 join 都是多余复杂度。
 *
 * <p>注意：<b>全文复制只复制这里的内容</b>，解析好的码/地点由原来的「复制」按钮负责，
 * 两条路不重复。
 */
final class RawSources {

    static final class Item {
        String type;
        String from;
        long at;
        String text;

        String label() {
            String t = empty(type) ? "消息" : type;
            if (!empty(from)) t += " · " + from;
            return t + " · " + Stamp.at(at);
        }
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 追加一条来源原文，返回可直接落库的 JSON */
    static String append(String json, String type, String from, String text, long at) {
        List<Item> items = parse(json);
        Item it = new Item();
        it.type = type;
        it.from = from;
        it.text = text;
        it.at = at > 0 ? at : System.currentTimeMillis();
        items.add(it);
        try {
            JSONArray arr = new JSONArray();
            for (Item x : items) {
                JSONObject o = new JSONObject();
                o.put("type", x.type == null ? "" : x.type);
                o.put("from", x.from == null ? "" : x.from);
                o.put("at", x.at);
                o.put("text", x.text == null ? "" : x.text);
                arr.put(o);
            }
            return arr.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static List<Item> parse(String json) {
        List<Item> out = new ArrayList<>();
        if (empty(json)) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.type = o.optString("type", "");
                it.from = o.optString("from", "");
                it.at = o.optLong("at", 0L);
                it.text = o.optString("text", "");
                out.add(it);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** 全文复制用：只有来源信息，不带解析后的码/地点 */
    static String fullText(String json) {
        List<Item> items = parse(json);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(it.label()).append('\n').append(it.text == null ? "" : it.text);
        }
        return sb.toString();
    }

    /** 来源类型：通知通道的 sender 是「通知:包名」 */
    static String typeOf(String sender) {
        return sender != null && sender.startsWith("通知:") ? "通知" : "短信";
    }

    static final class Stamp {
        private static final SimpleDateFormat FMT =
                new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

        static String at(long ts) {
            if (ts <= 0) return "";
            try {
                synchronized (FMT) {
                    return FMT.format(new Date(ts));
                }
            } catch (Throwable t) {
                return "";
            }
        }
    }

    private RawSources() { }
}
