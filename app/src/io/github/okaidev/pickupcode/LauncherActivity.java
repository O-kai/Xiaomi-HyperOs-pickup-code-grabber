package io.github.okaidev.pickupcode;

import android.app.Activity;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Random;

/**
 * 设置页（v2.1.1）：
 *  - 状态卡 / 模板模式（极简/完整/自定义）
 *  - 一键测试（随机码，永不撞去重；零短信费）
 *  - 黑名单（预览 + 修改/保存 二段式）
 *  - 通知点击：复制 + 打开便签；"已取件"按钮：勾选待办
 */
public class LauncherActivity extends Activity {

    private static final String PREFS = "dedup";
    private int currentMode = TodoWriter.MODE_FULL;
    private TextView statusCard;
    private TextView deployBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        currentMode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("todo_mode", TodoWriter.MODE_FULL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("📦 取件码助手");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("v2.2.0 · LSPosed 模块 · 自动提取取件码写入小米笔记待办");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(Color.parseColor("#888888"));
        root.addView(sub);

        statusCard = new TextView(this);
        statusCard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusCard.setTextColor(Color.parseColor("#333333"));
        statusCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusCard.setBackgroundColor(Color.parseColor("#F5F6FA"));
        statusCard.setText("⏳ 正在体检（root 检测约需 2-5 秒）…");
        root.addView(statusCard);

        // 一键部署 sqlite3（v2.2.0）：仅当体检发现 sqlite3 缺失且 root 可用时出现
        deployBtn = new TextView(this);
        deployBtn.setText("🚀 一键部署 sqlite3（自动完成，无需 adb/Termux）");
        deployBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        deployBtn.setTextColor(Color.WHITE);
        deployBtn.setBackgroundColor(Color.parseColor("#0FA968"));
        deployBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        deployBtn.setGravity(Gravity.CENTER);
        deployBtn.setVisibility(View.GONE);
        deployBtn.setOnClickListener(v -> {
            deployBtn.setEnabled(false);
            deployBtn.setText("⏳ 部署中…（如弹出 Magisk 授权请允许）");
            Toast.makeText(this, "正在部署 sqlite3…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String res;
                try {
                    res = Repair.deploySqlite3(this);
                } catch (Throwable t) {
                    res = "部署异常：" + t.getMessage();
                }
                final String r2 = res;
                runOnUiThread(() -> {
                    deployBtn.setEnabled(true);
                    deployBtn.setText("🚀 一键部署 sqlite3（自动完成，无需 adb/Termux）");
                    Toast.makeText(this, r2, Toast.LENGTH_LONG).show();
                    refreshHealth();
                });
            }).start();
        });
        LinearLayout.LayoutParams depLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        depLp.setMargins(dp(0), dp(8), dp(0), dp(0));
        deployBtn.setLayoutParams(depLp);
        root.addView(deployBtn);

        // 诊断报告导出按钮（体检下方，随时可点）
        TextView diagBtn = new TextView(this);
        diagBtn.setText("📤 导出诊断报告（发反馈请附上此文件）");
        diagBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        diagBtn.setTextColor(Color.WHITE);
        diagBtn.setBackgroundColor(Color.parseColor("#7A5AF8"));
        diagBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        diagBtn.setGravity(Gravity.CENTER);
        diagBtn.setOnClickListener(v -> {
            Toast.makeText(this, "正在生成诊断报告…（含日志提取，约 5-10 秒）", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    String report = Diagnostics.collectReport(this);
                    String where = Diagnostics.exportAndShare(this, report);
                    runOnUiThread(() -> Toast.makeText(this,
                            "报告已生成：" + where + "（已自动脱敏，可直接发给作者）",
                            Toast.LENGTH_LONG).show());
                } catch (Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "报告生成失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        diagLp.setMargins(dp(0), dp(8), dp(0), dp(0));
        diagBtn.setLayoutParams(diagLp);
        root.addView(diagBtn);

        refreshHealth();

        root.addView(spacer(18));

        // ============ 模板模式 ============
        TextView sec = new TextView(this);
        sec.setText("📝 待办模板");
        sec.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sec.setTextColor(Color.parseColor("#1A1A1A"));
        sec.setTypeface(null, Typeface.BOLD);
        root.addView(sec);

        TextView desc = new TextView(this);
        desc.setText("极简：只写取件码｜完整：码+来源+地点+时间｜自定义：模板占位符 {code} {source} {place} {time}");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setTextColor(Color.parseColor("#999999"));
        root.addView(desc);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.addView(modeButton("极简", TodoWriter.MODE_MIN));
        modeRow.addView(modeButton("完整", TodoWriter.MODE_FULL));
        modeRow.addView(modeButton("自定义", TodoWriter.MODE_CUSTOM));
        root.addView(modeRow);

        EditText tpl = new EditText(this);
        tpl.setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("todo_custom", "📦 取件码 {code}｜{source}｜{place}｜{time}"));
        tpl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        tpl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tpl.setPadding(dp(12), dp(10), dp(12), dp(10));
        tpl.setBackgroundColor(Color.parseColor("#FFFFFF"));
        tpl.setHint("自定义模板（空则用默认）");
        tpl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("todo_custom", tpl.getText().toString().trim()).apply();
            }
        });
        root.addView(tpl);

        root.addView(spacer(16));

        // ============ 一键测试 ============
        TextView testBtn = new TextView(this);
        testBtn.setText("🧪 一键测试（不消耗短信）");
        testBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        testBtn.setTextColor(Color.WHITE);
        testBtn.setBackgroundColor(Color.parseColor("#1E6FE8"));
        testBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        testBtn.setGravity(Gravity.CENTER);
        testBtn.setOnClickListener(v -> runSelfTest());
        root.addView(testBtn);

        root.addView(spacer(16));

        // ============ 黑名单（预览 + 修改/保存） ============
        TextView blLabel = new TextView(this);
        blLabel.setText("🚫 黑名单关键词");
        blLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        blLabel.setTextColor(Color.parseColor("#1A1A1A"));
        blLabel.setTypeface(null, Typeface.BOLD);
        root.addView(blLabel);

        TextView blPreview = new TextView(this);
        blPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        blPreview.setTextColor(Color.parseColor("#666666"));
        blPreview.setPadding(dp(12), dp(8), dp(12), dp(8));
        blPreview.setBackgroundColor(Color.parseColor("#F0F3F8"));
        root.addView(blPreview);

        EditText blEdit = new EditText(this);
        blEdit.setVisibility(View.GONE);
        blEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        blEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        blEdit.setBackgroundColor(Color.parseColor("#FFFFFF"));
        blEdit.setHint("逗号分隔，如：12306,验证码,银行");
        root.addView(blEdit);

        LinearLayout blBtnRow = new LinearLayout(this);
        blBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        blBtnRow.setVisibility(View.GONE);
        TextView blSave = smallButton("💾 保存", "#1E6FE8");
        TextView blCancel = smallButton("取消", "#9AA4B2");
        blBtnRow.addView(blSave);
        blBtnRow.addView(blCancel);
        root.addView(blBtnRow);

        TextView blModify = smallButton("✏️ 修改", "#F0A020");
        // 独立整行按钮：必须 MATCH_PARENT（smallButton 默认 weight 布局只适合横向行）
        LinearLayout.LayoutParams blModifyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blModifyLp.setMargins(dp(0), dp(6), dp(0), dp(0));
        blModify.setLayoutParams(blModifyLp);
        root.addView(blModify);

        final Runnable[] refreshBlacklistUi = new Runnable[1];
        refreshBlacklistUi[0] = () -> {
            String list = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("blacklist", "12306,验证码,余额,充值,账单,银行,优惠券,退订");
            blPreview.setText("当前：\n" + list.replace(",", "，"));
            blModify.setOnClickListener(v -> {
                blEdit.setText(list);
                blEdit.setVisibility(View.VISIBLE);
                blBtnRow.setVisibility(View.VISIBLE);
                blModify.setVisibility(View.GONE);
                blPreview.setVisibility(View.GONE);
            });
            blSave.setOnClickListener(v -> {
                String newList = blEdit.getText().toString().trim();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("blacklist", newList).apply();
                Toast.makeText(this, "黑名单已保存", Toast.LENGTH_SHORT).show();
                blEdit.setVisibility(View.GONE);
                blBtnRow.setVisibility(View.GONE);
                blModify.setVisibility(View.VISIBLE);
                blPreview.setVisibility(View.VISIBLE);
                refreshBlacklistUi[0].run();
            });
            blCancel.setOnClickListener(v -> {
                blEdit.setVisibility(View.GONE);
                blBtnRow.setVisibility(View.GONE);
                blModify.setVisibility(View.VISIBLE);
                blPreview.setVisibility(View.VISIBLE);
            });
        };
        refreshBlacklistUi[0].run();

        root.addView(spacer(18));

        TextView help = new TextView(this);
        help.setText("📖 使用说明\n"
                + "1. LSPosed 激活模块；作用域勾选 5 项：android(系统框架)、电话、短信、"
                + "com.android.providers.telephony、笔记（v2.1.2 起会自动推荐，升级后请重新核对）\n"
                + "2. 收到取件短信后自动写入待办（一码一条，新码置顶）\n"
                + "3. 通知可点击：复制取件码；通知上「已取件」：一键勾选\n"
                + "4. 需要 root（Magisk）授权一次；sqlite3 按 README 部署\n"
                + "5. 遇到问题：先看上方体检❌项 → 点「导出诊断报告」发给作者");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        help.setTextColor(Color.parseColor("#888888"));
        help.setLineSpacing(dp(3), 1.0f);
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    /** 一键测试：随机取件码（永不撞去重）→ 走完整链路 */
    private void runSelfTest() {
        try {
            String code = "99-9-" + String.format("%04d", new Random().nextInt(10000));
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("sender", "test");
            extras.putString("body", "【菜鸟驿站】测试包裹：取件码为" + code + "（测试条目，可删除）");
            extras.putLong("ts", System.currentTimeMillis());
            android.os.Bundle res = getContentResolver().call(
                    android.net.Uri.parse("content://io.github.okaidev.pickupcode.provider"),
                    "onSms", null, extras);
            String r = res == null ? "未响应" : res.getString("result");
            String[] msg = r == null ? new String[]{"未响应"} : r.replace("[", "").replace("]", "").split(",");
            String wrote = msg.length == 0 || msg[0].isEmpty() ? "无" : String.join(",", msg);
            if (wrote.equals("无") || wrote.equals("未响应")) {
                // v2.1.2：失败时直接给出断在哪一步（TodoWriter 记录的具体原因）
                String diag = TodoWriter.getLastWriteDiag();
                if (diag.length() > 140) diag = diag.substring(0, 140) + "…";
                Toast.makeText(this, "测试失败：" + wrote + "\n原因：" + diag
                        + "\n（详查：导出诊断报告）", Toast.LENGTH_LONG).show();
                refreshHealth();
            } else {
                Toast.makeText(this, "测试成功：已写入待办 " + wrote + "（可删除）", Toast.LENGTH_LONG).show();
                refreshHealth();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "测试异常：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 部署体检：异步跑六项检查并刷新状态卡 + 按需显示一键部署按钮 */
    private void refreshHealth() {
        statusCard.setText("⏳ 正在体检…");
        deployBtn.setVisibility(View.GONE);
        new Thread(() -> {
            String text;
            boolean needDeploy = false;
            try {
                StringBuilder sb = new StringBuilder("🩺 部署体检（v2.2.0）\n");
                for (Diagnostics.Check c : Diagnostics.healthCheck(this)) {
                    sb.append(c.ok ? "✅ " : "❌ ").append(c.title)
                      .append("：").append(c.detail).append("\n");
                    if ("sqlite3 部署".equals(c.title) && !c.ok
                            && c.detail != null && c.detail.contains("一键部署")) {
                        needDeploy = true;
                    }
                }
                sb.append("模板模式：").append(modeName(currentMode))
                  .append(" ｜ 写入：小米笔记待办");
                text = sb.toString();
            } catch (Throwable t) {
                text = "体检异常：" + t.getMessage();
            }
            final String t2 = text;
            final boolean show = needDeploy;
            runOnUiThread(() -> {
                statusCard.setText(t2);
                deployBtn.setVisibility(show ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleCopy(intent);
        handleDone(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleCopy(getIntent());
        handleDone(getIntent());
        updateStatus();
    }

    /** 通知点击：复制取件码 + 打开小米笔记 */
    private void handleCopy(Intent intent) {
        if (intent == null) return;
        String copy = intent.getStringExtra("copy");
        if (copy == null || copy.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("pickupcode", copy));
            Toast.makeText(this, "已复制取件码：" + copy, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
        try {
            Intent notes = getPackageManager().getLaunchIntentForPackage("com.miui.notes");
            if (notes != null) startActivity(notes);
        } catch (Throwable ignored) { }
        intent.removeExtra("copy");
    }

    /** 通知"已取件"按钮：勾选对应待办 */
    private void handleDone(Intent intent) {
        if (intent == null) return;
        String code = intent.getStringExtra("done");
        if (code == null || code.isEmpty()) return;
        String sql = "UPDATE todo SET is_finish=1, mark_finish_time=strftime('%s','now')*1000 "
                + "WHERE is_finish=0 AND content LIKE '%" + TodoWriter.escape(code) + "%';";
        int rc = TodoWriter.runSql(this, sql);
        Toast.makeText(this, rc == 0 ? "已标记已取件：" + code : "标记失败（rc=" + rc + "）",
                Toast.LENGTH_LONG).show();
        intent.removeExtra("done");
        if (rc == 0) {
            try {
                Intent notes = getPackageManager().getLaunchIntentForPackage("com.miui.notes");
                if (notes != null) startActivity(notes);
            } catch (Throwable ignored) { }
            finish();
        }
    }

    private void updateStatus() {
        // v2.1.2：状态卡 = 动态体检结果（不再静态宣称"已激活"）
        refreshHealth();
    }

    private TextView modeButton(String label, int mode) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(mode == currentMode ? Color.WHITE : Color.parseColor("#333333"));
        b.setBackgroundColor(mode == currentMode ? Color.parseColor("#1E6FE8") : Color.parseColor("#EDF1F7"));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        b.setOnClickListener(v -> {
            currentMode = mode;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("todo_mode", mode).apply();
            Toast.makeText(this, "待办模板已切换为「" + label + "」", Toast.LENGTH_SHORT).show();
            updateStatus();
            recreate();
        });
        return b;
    }

    private TextView smallButton(String label, String bg) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(bg));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        return b;
    }

    private String modeName(int mode) {
        switch (mode) {
            case TodoWriter.MODE_MIN: return "极简";
            case TodoWriter.MODE_CUSTOM: return "自定义";
            default: return "完整";
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(dp)));
        return v;
    }
}
