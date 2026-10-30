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
    private static final String KEY_MISSED_ENABLED = "missed_sms_enabled";
    private static final int MAX_COUNT = 30;

    /**
     * 样本过期时长：30 天。
     *
     * <p>错题本存的是短信正文片段，属于敏感数据。不设过期时间的话，
     * 它会一直躺在本机——所以超过 30 天的样本在读取时自动剔除。
     */
    private static final long EXPIRE_MS = 30L * 24 * 60 * 60 * 1000;

    /**
     * 错题本默认是否开启。
     *
     * <p>v4.0 收口：原先是<b>默认开启</b>的——也就是说用户没做任何选择，
     * 模块就在后台悄悄保存他收到的疑似漏抓短信正文。这与本项目
     * 「默认不存短信正文」的隐私取舍自相矛盾，所以改成默认关闭，
     * 需要用户在「反馈与支持 → 疑似漏抓错题本」里显式打开。
     */
    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_MISSED_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEnabled(Context ctx, boolean on) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_MISSED_ENABLED, on).apply();
        } catch (Throwable ignored) {
        }
    }

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

    /** 记录疑似漏抓短信（去重 + 容量淘汰）。错题本关闭时直接忽略。 */
    public static synchronized void record(Context ctx, String sender, String body) {
        if (ctx == null || body == null || body.trim().isEmpty()) return;
        if (!isEnabled(ctx)) return;
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

    /** 读取所有样本清单（自动剔除过期样本） */
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
            long now = System.currentTimeMillis();
            boolean dropped = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long ts = o.optLong("ts");
                // 过期样本直接剔除：错题本存的是短信正文片段，不该一直躺在本机
                if (ts > 0 && now - ts > EXPIRE_MS) {
                    dropped = true;
                    continue;
                }
                list.add(new Sample(ts, o.optString("sender"), o.optString("body")));
            }
            if (dropped) {
                // 顺手把过期条目从存储里也清掉，避免残留
                saveSamples(sp, list);
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
