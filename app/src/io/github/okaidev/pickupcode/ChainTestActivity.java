package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Random;

/**
 * 🧪 一键链路测试（v3.0.0）
 *
 * 为什么用独立 Activity 而不是 AlertDialog 多步弹窗：
 *   实测发现 AlertDialog.setView() 承载可聚焦 EditText + 多个自绘按钮时，
 *   在部分 ROM 上触摸事件被 dialog window 吞掉（按钮 clickable=false、输入框拿不到焦点），
 *   表现为"点了没反应"。独立 Activity 没有这层 window 限制，行为稳定可预期。
 *
 * 三步流程（用户可逐步感知，而非一键黑盒）：
 *   Step 1 输入   手动输入/粘贴（可选填发送方），或一键填入官方案例
 *   Step 2 解析   只解析不写库，展示 码/来源/地点/规则版本
 *   Step 3 写入   用户确认后才真实写入，走完整链路（含去重与通知）
 * 全程不消耗真实短信、不产生任何费用。
 */
public class ChainTestActivity extends Activity {

    private static final String KEY_BODY = "lastBody";
    private static final String KEY_SENDER = "lastSender";
    /** 官方案例用的示例发送方（虚拟号前几位），与参考案例里的「精确匹配：1069888877」一致 */
    private static final String SAMPLE_SENDER = "1069888877";

    private EditText input;
    private EditText senderInput;

    /**
     * 上一次输入（跨 recreate 保留）。
     * recreate() 销毁并重建的是「控件」而不是 Activity 实例本身，所以控件引用在 recreate 之后
     * 已属于即将销毁的旧实例——再对它 setText 是无效的（历史上就因此丢过内容）。
     * 因此统一用成员字段保存，在 onCreate 里回填。
     */
    private String lastBody = "";
    private String lastSender = "";

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 只落盘字段、不回读控件：此时 setContentView 可能已切到第 2/3 步，
        // input 仍指向第 1 步那个已脱离窗口的控件，回读会把「再测一条」的清空覆盖回去。
        outState.putString(KEY_BODY, lastBody);
        outState.putString(KEY_SENDER, lastSender);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PickupExtractor.init(this);
        if (savedInstanceState != null) {
            lastBody = savedInstanceState.getString(KEY_BODY, lastBody);
            lastSender = savedInstanceState.getString(KEY_SENDER, lastSender);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(20));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("🧪 一键链路测试");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("验证「短信进来 → 识别 → 写入待办」整条链路是否正常\n"
                + "不消耗短信 · 不产生费用 · 测试记录可随时删除");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(Color.parseColor("#888888"));
        sub.setLineSpacing(dp(3), 1.0f);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        // ---- 操作区：统一布局（避免两个按钮 + 一个按钮造成的视觉不对称）----
        // 第一行：两个辅助操作并排（等宽对称）
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(btn("📋 填入官方案例", "#7A5AF8", v -> {
            input.setText(sample());
            // 发送方一并填好：否则用户永远看不到「号码专属规则」这条最推荐的用法
            senderInput.setText(SAMPLE_SENDER);
            Toast.makeText(this, "已填入官方案例（含示例发送方），点下方「② 解析」继续", Toast.LENGTH_SHORT).show();
        }), lp());
        row1.addView(btn("📥 粘贴剪贴板", "#0FA968", v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                    if (cs != null && cs.length() > 4) {
                        input.setText(cs);
                        Toast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "读取剪贴板失败", Toast.LENGTH_SHORT).show();
            }
        }), lp());
        root.addView(row1);

        // ---- 输入区 ----
        TextView step = new TextView(this);
        step.setText("第 1 步 / 共 3 步：输入要测试的短信");
        step.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        step.setTextColor(Color.parseColor("#333333"));
        step.setTypeface(null, Typeface.BOLD);
        step.setPadding(0, dp(16), 0, dp(6));
        root.addView(step);

        // ---- 发送方（可选）----
        // 为什么必须有它：用户自定义规则里重点推荐「号码专属规则」（精确/前缀/包含匹配），
        // 而这三种模式在 sender 为 null 时一律不匹配——不填发送方，用户在本页
        // 永远无法验证自己最需要验证的规则，只能看到"未识别到"。
        TextView senderLabel = new TextView(this);
        senderLabel.setText("发送方（可选，留空 = 不限来源）");
        senderLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        senderLabel.setTextColor(Color.parseColor("#333333"));
        senderLabel.setTypeface(null, Typeface.BOLD);
        senderLabel.setPadding(0, dp(4), 0, dp(4));
        root.addView(senderLabel);

        senderInput = new EditText(this);
        senderInput.setHint("1069888877 或 通知:com.cainiao.wireless（可留空，留空=不限来源）");
        senderInput.setTextColor(Color.parseColor("#333333"));
        senderInput.setHintTextColor(Color.parseColor("#AAAAAA"));
        senderInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        senderInput.setSingleLine(true);
        senderInput.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        senderInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        senderInput.setBackground(roundRect("#F7F8FA"));
        if (lastSender != null && !lastSender.isEmpty()) senderInput.setText(lastSender);
        LinearLayout.LayoutParams senderLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        senderLp.setMargins(0, 0, 0, dp(6));
        senderInput.setLayoutParams(senderLp);
        root.addView(senderInput);

        TextView senderTip = new TextView(this);
        senderTip.setText("💡 填了发送方，第 2 步解析与第 3 步写入都会用同一个号码，\n"
                + "号码专属规则（精确/前缀/包含）才有可能匹配上。");
        senderTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        senderTip.setTextColor(Color.parseColor("#999999"));
        senderTip.setLineSpacing(dp(3), 1.0f);
        senderTip.setPadding(0, 0, 0, dp(8));
        root.addView(senderTip);

        input = new EditText(this);
        input.setHint("在此粘贴或输入短信的完整内容");
        input.setTextColor(Color.parseColor("#333333"));
        input.setHintTextColor(Color.parseColor("#AAAAAA"));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(roundRect("#F7F8FA"));
        if (lastBody != null && !lastBody.isEmpty()) input.setText(lastBody);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        inLp.setMargins(0, 0, 0, dp(10));
        input.setLayoutParams(inLp);
        root.addView(input);

        TextView tip = new TextView(this);
        tip.setText("💡 第一次使用建议点「填入官方案例」；\n"
                + "若某条真实短信没被抓到，粘贴进来点下方按钮看是否匹配，\n"
                + "这正是「🧩 用户自定义规则」该出手的场景。\n"
                + "注：号码专属规则（精确/前缀/包含匹配）必须在上方填「发送方」才能验证，\n"
                + "留空＝不限来源时这类规则永远不会命中。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tip.setTextColor(Color.parseColor("#999999"));
        tip.setLineSpacing(dp(3), 1.0f);
        tip.setPadding(0, 0, 0, dp(10));
        root.addView(tip);

        // 底部主操作：通栏、与其他步骤的主按钮位置保持一致（视觉对称）
        TextView parseBtn = btn("② 解析（不写库）", "#1E6FE8", v -> doParse());
        root.addView(parseBtn);

        setContentView(root);
    }

    /**
     * 官方示例短信（取件码随机）
     *
     * v3.0.0 修复：原先固定用 99-9-8888，导致用户第二次点「写入待办」时
     * 必然被去重机制拦下，看起来像"根本没写进去"。
     * 现在每次生成随机码（如 99-9-3271），保证每次测试都能真实走完写入链路。
     */
    private String sample() {
        String code = "99-9-" + String.format("%04d", new Random().nextInt(10000));
        return "【菜鸟驿站】测试包裹：取件码为" + code
                + "，请到XX小区快递驿站取件（测试条目，可删除）";
    }

    /**
     * 第 2 步：解析并展示（绝不写库）
     * v3.0.0：由弹窗改为【整页显示】，与第 1 步布局统一，避免前后割裂
     */
    private void doParse() {
        snapshotInputs();
        String sms = lastBody;
        if (sms.isEmpty()) {
            Toast.makeText(this, "请先输入或粘贴短信内容", Toast.LENGTH_SHORT).show();
            return;
        }
        // 发送方为空 = 不限来源（传 null，与规则里 MODE_ANY 的语义一致）
        String sender = (lastSender == null || lastSender.isEmpty()) ? null : lastSender;
        // v3.0.1：先清空溯源，再解析——否则会显示"上一条"规则的名字
        UserRules.clearLastHit();
        // 必须用带 sender 的重载：不带 sender 时 EXACT/PREFIX/CONTAINS 一律匹配不上
        List<String> codes = PickupExtractor.extract(sender, sms);
        String place = TodoWriter.extractPlace(sender, sms);
        String source = TodoWriter.resolveSource(sender != null ? sender : "10086", sms);
        boolean hit = !codes.isEmpty();
        // v3.0.1：区分"官方规则"与"用户自定义规则"，并显示具体规则名
        String hitUserRule = PickupExtractor.getLastHitUserRuleName();

        LinearLayout page = pageBase();
        TextView t2 = new TextView(this);
        t2.setText("第 2 步 / 共 3 步：解析结果");
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t2.setTextColor(Color.parseColor(hit ? "#0FA968" : "#D93025"));
        t2.setTypeface(null, Typeface.BOLD);
        t2.setPadding(0, dp(6), 0, dp(14));
        page.addView(t2);

        // 结果卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(hit ? "#EEF9F1" : "#FDF0EF"));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(clp);
        page.addView(card);

        // v3.0.1：明示本次用的是官方规则还是哪条用户自定义规则
        addResultRow(card, "规则来源", hitUserRule != null
                ? "🧩 用户自定义规则 · " + hitUserRule
                : "📘 官方规则集 v" + PickupExtractor.getActiveRulesVersion());
        addResultRow(card, "发 送 方", sender != null
                ? sender + "（号码专属规则已参与匹配）"
                : "未填（不限来源：号码专属规则不会命中）");
        addResultRow(card, "取件码", hit ? String.join("、", codes) : "未识别到");
        addResultRow(card, "来  源", nz(source));
        addResultRow(card, "地  点", nz(place));

        TextView note = new TextView(this);
        note.setText(hit
                ? (hitUserRule != null
                    ? "✅ 识别成功（由你的自定义规则「" + hitUserRule + "」匹配）。\n\n"
                        + "下一步可写入待办，验证完整链路（去重 + 通知）。"
                    : "✅ 识别成功（由官方规则集匹配）。\n\n"
                        + "下一步可写入待办，验证完整链路（去重 + 通知）。")
                : "❌ 没能识别到取件码。\n\n"
                + "可能是短信模板较特殊，官方规则没覆盖到。\n"
                + "这正是「🧩 用户自定义规则」该出手的场景。\n"
                + "也可以把这条短信上报给作者，下一版规则就能适配。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(Color.parseColor("#666666"));
        note.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        page.addView(note, nlp);

        // 底部操作：主操作通栏，次操作在其上（每页同一位置 → 视觉统一）
        if (hit) {
            // v3.1.1：写入已移到后台线程，这里先把按钮置灰并改成"写入中"，
            // 否则长达数秒的 su+sqlite3 期间按钮毫无反馈，用户会以为"点了没反应"
            page.addView(btn("③ 写入待办（验证完整链路）", "#0FA968", v -> {
                v.setEnabled(false);
                ((TextView) v).setText("⏳ 正在写入…（su + sqlite3，可能需数秒）");
                doWrite(sms, sender);
            }), vlp());
        } else {
            page.addView(btn("✍️ 上报这条短信给作者", "#F0A020", v -> report()), vlp());
        }
        page.addView(btn("← 返回修改短信", "#8A8A8A", v -> showStep1(sms, sender)), vlp());

        setContentView(page);
    }

    /** 结果卡片里的一行「字段: 值」 */
    private void addResultRow(LinearLayout card, String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView kv = new TextView(this);
        kv.setText(k);
        kv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        kv.setTextColor(Color.parseColor("#888888"));
        kv.setPadding(0, 0, dp(10), 0);
        row.addView(kv, new LinearLayout.LayoutParams(dp(72),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView vv = new TextView(this);
        vv.setText(v);
        vv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        vv.setTextColor(Color.parseColor("#1A1A1A"));
        row.addView(vv);
        card.addView(row);
    }

    /** 整页基座：统一内边距 */
    private LinearLayout pageBase() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(22), dp(20), dp(20));
        p.setBackgroundColor(Color.WHITE);
        return p;
    }

    /** 把当前控件内容存进成员字段（供 recreate 之后回填） */
    private void snapshotInputs() {
        if (input != null) lastBody = input.getText().toString().trim();
        if (senderInput != null) lastSender = senderInput.getText().toString().trim();
    }

    /**
     * 回到第 1 步：内容靠成员字段带过去，不靠 recreate 之后的控件引用。
     * （传 null 表示清空——「再测一条」用。）
     */
    private void showStep1(String prefill, String senderPrefill) {
        lastBody = prefill == null ? "" : prefill;
        lastSender = senderPrefill == null ? "" : senderPrefill;
        recreate();
    }

    /**
     * 第 3 步：真实写入（走完整链路）
     *
     * v3.1.1：getContentResolver().call() 是【同步阻塞】的——Provider 侧走的是
     * su + sqlite3，最长可达数秒。原先整段（含 call）都在 OnClickListener 的主线程里跑，
     * 表现为按下去界面直接冻住、触摸无响应，极易 ANR。
     * 现在：call 放后台线程，拿到结果 Bundle 后 runOnUiThread 回主线程渲染第 3 步结果页；
     * 结果判定（title / msg / 控件装配）逻辑与原实现完全一致，只是换了线程。
     */
    private void doWrite(final String sms, final String sender) {
        new Thread(() -> {
            final String wrote;
            final String dedup;
            try {
                android.os.Bundle extras = new android.os.Bundle();
                // 必须与第 2 步解析用同一个发送方：Provider 侧会拿它去做用户规则号码匹配，
                // 若这里仍写死 "chain-test"，就会出现「解析按专属规则命中、写入却按全局规则去重」的错位。
                extras.putString("sender", sender != null ? sender : "chain-test");
                extras.putString("body", sms);
                extras.putLong("ts", System.currentTimeMillis());
                android.os.Bundle res = getContentResolver().call(
                        android.net.Uri.parse("content://io.github.okaidev.pickupcode.provider"),
                        "onSms", null, extras);
                String r = res == null ? null : res.getString("result");
                wrote = (r == null) ? "" : r.replace("[", "").replace("]", "").trim();
                // 去重命中情况在写入线程里紧接着读取，与原实现时序一致
                String d = TodoWriter.getLastDedupHits();
                dedup = (d == null) ? "" : d;
            } catch (final Throwable t) {
                final String msg = String.valueOf(t.getMessage());
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("❌ 写入异常")
                        .setMessage(msg)
                        .setPositiveButton("知道了", null)
                        .show());
                return;
            }
            runOnUiThread(() -> showWriteResult(wrote, dedup));
        }).start();
    }

    /**
     * 第 3 步结果页渲染（必须在主线程执行）。
     * 判定逻辑（去重优先于写入失败）保持 v3.0.0 原样未改。
     *
     * @param wrote  写入返回的取件码串（空串=没写进去）
     * @param dedup  本次命中的去重记录（空串=未命中）
     */
    private void showWriteResult(String wrote, String dedup) {
        if (isFinishing() || isDestroyed()) return;
        String title, msg;
        // v3.0.0 修复：必须先判「是否命中去重」。
        // 命中去重时 wrote 同样是空的（本来就写过了），此前被误报成「写入失败」。
        boolean dedupHit = dedup != null && !dedup.trim().isEmpty();
        if (dedupHit) {
            title = "✅ 写入成功（命中去重）";
            msg = "解析 ✅　识别 ✅　去重 ✅　通知 ✅\n\n"
                    + "这条取件码之前已写入过，去重机制正常拦截——"
                    + "说明整条链路是通的，且不会重复打扰你。\n\n"
                    + "想看写入效果？换一条没测过的短信再试一次即可。";
        } else if (wrote.isEmpty()) {
            String diag = TodoWriter.getLastWriteDiag();
            if (diag.length() > 160) diag = diag.substring(0, 160) + "…";
            title = "❌ 写入未成功";
            msg = "解析出了取件码，但写入待办这步没通过。\n\n可能原因：\n" + diag
                    + "\n\n可回主界面展开「⚙️ 更多设置」点「🔍 排查问题」按向导定位。";
        } else {
            title = "🎉 全链路已跑通";
            msg = "解析 ✅　识别 ✅　写入 ✅　去重 ✅　通知 ✅\n\n"
                    + "整条链路工作正常。以后收到真实取件短信会自动写进待办，\n"
                    + "这条测试记录可随时删除。\n\n已写入：" + wrote;
        }
        // v3.0.0：第 3 步同样整页显示，与前两步布局一致
        LinearLayout page = pageBase();
        TextView t3 = new TextView(this);
        t3.setText(title);
        t3.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t3.setTextColor(Color.parseColor(
                dedupHit || !wrote.isEmpty() ? "#0FA968" : "#D93025"));
        t3.setTypeface(null, Typeface.BOLD);
        t3.setPadding(0, dp(6), 0, dp(14));
        page.addView(t3);

        TextView body = new TextView(this);
        body.setText(msg);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(Color.parseColor("#444444"));
        body.setLineSpacing(dp(4), 1.0f);
        page.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        page.addView(btn("✅ 完成", "#1E6FE8", v -> finish()), vlp());
        page.addView(btn("← 再测一条", "#8A8A8A", v -> showStep1(null, null)), vlp());
        setContentView(page);
    }

    /** 上报入口（跳 GitHub Issues 并预填模板） */
    private void report() {
        try {
            String sms = lastBody;
            String tpl = "### 1. 收到的短信原文（请自行脱敏）\n" + sms
                    + (lastSender == null || lastSender.isEmpty()
                        ? "\n发送方：未填（不限来源）"
                        : "\n发送方：" + lastSender)
                    + "\n\n### 2. 期望结果\n（应提取到的取件码 / 来源 / 地点）\n\n"
                    + "### 3. 实际表现\n· 在「🧪 一键链路测试」中解析结果：\n\n"
                    + "### 4. 运行环境\n"
                    + "- 模块版本：v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName + "\n"
                    + "- 规则版本：v" + PickupExtractor.getActiveRulesVersion() + "\n";
            Intent it = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/issues/new"
                            + "?title=" + android.net.Uri.encode("【短信未识别上报】")
                            + "&body=" + android.net.Uri.encode(tpl)));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开浏览器：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static String nz(String s) { return (s == null || s.isEmpty()) ? "—" : s; }

    private TextView btn(String label, String bg, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(Color.WHITE);
        t.setBackground(roundRect(bg));
        t.setPadding(dp(10), dp(14), dp(10), dp(14));
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private android.graphics.drawable.Drawable roundRect(String color) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setColor(Color.parseColor(color));
        gd.setCornerRadius(dp(12));
        return gd;
    }

    /** 纵向按钮间距：上下各留 5dp，避免多个按钮上下黏在一起 */
    private LinearLayout.LayoutParams vlp() {
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p2.setMargins(0, dp(5), 0, dp(5));
        return p2;
    }
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
