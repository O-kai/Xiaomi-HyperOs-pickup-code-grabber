package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 疑似漏抓短信错题本（v2.8.0 闭环体系）：
 * 当短信命中了快递特征词，但提取引擎返回空时，自动捕获为「疑似漏抓样本」。
 * 存放在本地私有存储（最多保留 30 条），支持用户在设置页查看、一键脱敏复制上报。
 */
public class MissedSmsStore {

    private static final String TAG = "PICKUPDEBUG";
    private static final String PREFS = "dedup";
    private static final String KEY_MISSED_LIST = "missed_sms_samples";
    private static final int MAX_COUNT = 30;

    public static class Sample {
        public long timestamp;
        public String sender;
        public String body;

        public Sample(long ts, String sender, String body) {
            this.timestamp = ts;
            this.sender = sender;
            this.body = body;
        }
    }

    /** 记录疑似漏抓短信（去重 + 容量淘汰） */
    public static synchronized void record(Context ctx, String sender, String body) {
        if (ctx == null || body == null || body.trim().isEmpty()) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            List<Sample> list = loadSamples(sp);

            // 简单去重：如果已有相同正文则更新时间
            for (Sample s : list) {
                if (body.equals(s.body)) {
                    s.timestamp = System.currentTimeMillis();
                    saveSamples(sp, list);
                    return;
                }
            }

            // 新增条目
            list.add(0, new Sample(System.currentTimeMillis(), sender, body));
            while (list.size() > MAX_COUNT) {
                list.remove(list.size() - 1);
            }
            saveSamples(sp, list);
            Log.i(TAG, "MissedSmsStore: recorded 1 missed sample, total: " + list.size());
        } catch (Throwable t) {
            Log.e(TAG, "record missed sms err: " + t);
        }
    }

    /** 读取所有样本清单 */
    public static synchronized List<Sample> getAll(Context ctx) {
        if (ctx == null) return new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return loadSamples(sp);
    }

    /** 清空错题本 */
    public static synchronized void clear(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_MISSED_LIST).apply();
    }

    /** 智能脱敏处理：掩码手机号与链接，保护隐私 */
    public static String sanitize(String body) {
        if (body == null) return "";
        // 掩码手机号（13812345678 -> 138****5678）
        String s = body.replaceAll("(1[3-9]\\d)\\d{4}(\\d{4})", "$1****$2");
        // 掩码 URL 链接
        s = s.replaceAll("https?://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]", "[链接]");
        return s;
    }

    /** 生成标准格式的复制上报文本 */
    public static String formatReport(List<Sample> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 【取件码助手】疑似漏抓短信样本上报 ===\n");
        sb.append("规则版本: v").append(PickupExtractor.getActiveRulesVersion()).append("\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            sb.append("\n【样本 ").append(i + 1).append("】 时间: ").append(sdf.format(new Date(s.timestamp))).append("\n");
            sb.append("正文(已脱敏): ").append(sanitize(s.body)).append("\n");
        }
        sb.append("\n=========================================");
        return sb.toString();
    }

    private static List<Sample> loadSamples(SharedPreferences sp) {
        List<Sample> list = new ArrayList<>();
        String jsonStr = sp.getString(KEY_MISSED_LIST, null);
        if (jsonStr == null || jsonStr.trim().isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Sample(o.optLong("ts"), o.optString("sender"), o.optString("body")));
            }
        } catch (Throwable ignored) { }
        return list;
    }

    private static void saveSamples(SharedPreferences sp, List<Sample> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Sample s : list) {
                JSONObject o = new JSONObject();
                o.put("ts", s.timestamp);
                o.put("sender", s.sender);
                o.put("body", s.body);
                arr.put(o);
            }
            sp.edit().putString(KEY_MISSED_LIST, arr.toString()).apply();
        } catch (Throwable ignored) { }
    }
}
