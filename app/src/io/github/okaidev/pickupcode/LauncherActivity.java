package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Random;

/**
 * 设置页（v2.6.0 / v2.7.0 多 ROM）：
 *  - 折叠式部署体检（全绿一行收起；❌ 自动展开）+ 🔍 排查问题向导（含导出诊断报告/反馈渠道）
 *  - 待办模板（极简/完整只读效果预览；自定义编辑+防御校验）
 *  - 一键测试（随机码，永不撞去重）、黑名单（修改/保存 二段式）
 *  - QQ 群入口（明文群号+一键复制）、右上角 ⋮ 菜单（仓库/检查更新/打赏）
 *  - v2.7.0：写入目标选择器（自动 / 小米笔记 / ColorOS 日历待办 / ColorOS 便签置顶笔记），
 *    副标题按当前后端显示；系统直写兜底开关（默认关，需给系统进程授权 root）
 *  - 通知点击：复制 + 打开对应待办 App；"已取件"按钮：勾选待办
 */
public class LauncherActivity extends Activity {

    private static final String PREFS = "dedup";
    private static final String KEY_BACKEND = "backend_override";
    private static final String KEY_SYS_DIRECT = "sys_direct_write";
    private static final String REPO_URL = "https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber";
    private static final String ISSUES_URL = REPO_URL + "/issues";
    private static final String COOLAPK_URL = "https://www.coolapk.com/feed/73558591";
    private int currentMode = TodoWriter.MODE_FULL;
    private TextView statusCard;
    private TextView deployBtn;
    /** v2.5.3：体检卡折叠态（全绿自动收起；null=体检未完成不折叠） */
    private Boolean healthExpanded = null;
    /** 最近一次完整体检文本（展开时还原用） */
    private String lastHealthText = null;
    /** v2.5.3：模板三按钮 + 高亮刷新器（切换模式不 recreate，只重着色） */
    private final List<TextView> modeBtns = new java.util.ArrayList<>(3);
    private final int[] modeIds = {TodoWriter.MODE_MIN, TodoWriter.MODE_FULL, TodoWriter.MODE_CUSTOM};
    private Runnable restyleModes;
    /** v2.5.3：模式切换后的模板预览刷新器（onCreate 里赋值） */
    private Runnable refreshTplPreview;
    /** v2.5.3：模板编辑控件引用（切模式时收起编辑态用） */
    private EditText tplEditor;
    private LinearLayout tplEditorBtnRow;
    private TextView tplPreviewCard;
    /** v2.7.0：副标题与写入目标选择器文案引用（切换后端时原地刷新） */
    private TextView subTitleView;
    private TextView backendLabelView;
    /** v2.7.1：冻结免疫直写 UI 动态显隐刷新器 */
    private Runnable refreshSdwUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PickupExtractor.init(this);
        RulesUpdater.maybeCheck(this);
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

        // 右上角「⋮」菜单（v2.5.0）：项目仓库 / 打赏作者
        TextView menuBtn = new TextView(this);
        menuBtn.setText("⋮");
        menuBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        menuBtn.setTextColor(Color.parseColor("#444444"));
        menuBtn.setPadding(dp(10), 0, dp(10), 0);
        menuBtn.setOnClickListener(v -> showMainMenu(v));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        titleRow.addView(menuBtn);
        root.addView(titleRow);

        TextView sub = new TextView(this);
        sub.setText("v2.9.1 · LSPosed 模块 · 自动提取取件码写入 " + backendLabel());
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(Color.parseColor("#888888"));
        subTitleView = sub;
        root.addView(sub);

        // v2.5.3：开发者 QQ 群——版本号正下方；群号明文展示 + 右侧一键复制按钮
        LinearLayout qqRow = new LinearLayout(this);
        qqRow.setOrientation(LinearLayout.HORIZONTAL);
        qqRow.setGravity(Gravity.CENTER_VERTICAL);
        qqRow.setPadding(0, dp(2), 0, dp(6));

        TextView qqLabel = new TextView(this);
        qqLabel.setText("💬 开发者 QQ 群：901543676（进群备注「取件码助手」）");
        qqLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        qqLabel.setTextColor(Color.parseColor("#1E6FE8"));
        qqLabel.setPadding(0, 0, dp(8), 0);
        qqRow.addView(qqLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView qqCopy = new TextView(this);
        qqCopy.setText("📋 复制群号");
        qqCopy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        qqCopy.setTextColor(Color.WHITE);
        qqCopy.setBackgroundColor(Color.parseColor("#1E6FE8"));
        qqCopy.setPadding(dp(10), dp(4), dp(10), dp(4));
        qqCopy.setOnClickListener(v -> copyQqGroup());
        qqRow.addView(qqCopy);

        root.addView(qqRow);

        // ============ v2.8.0：允许联网开关（离线优先） ============
        // 关闭后：软件自动更新检查 + 规则自动热更新全部停止（纯离线模式）；
        // 核心功能（提取/写入/通知/体检）零影响——规则引擎本地三级回退常备。
        LinearLayout netRow = new LinearLayout(this);
        netRow.setOrientation(LinearLayout.HORIZONTAL);
        netRow.setGravity(Gravity.CENTER_VERTICAL);
        netRow.setPadding(0, dp(2), 0, dp(2));

        TextView netLabel = new TextView(this);
        netLabel.setText("🌐 允许联网（自动更新软件/规则）");
        netLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        netLabel.setTextColor(Color.parseColor("#333333"));
        netLabel.setPadding(0, 0, dp(8), 0);
        netRow.addView(netLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        final TextView netBtn = new TextView(this);
        boolean netOn = NetPolicy.autoNetAllowed(this);
        netBtn.setText(netOn ? "已开启 · 点我关闭" : "已关闭（纯离线） · 点我开启");
        netBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        netBtn.setTextColor(Color.WHITE);
        netBtn.setBackgroundColor(Color.parseColor(netOn ? "#0FA968" : "#9AA4B2"));
        netBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        netBtn.setOnClickListener(v -> {
            boolean next = !NetPolicy.autoNetAllowed(this);
            NetPolicy.setAutoNetAllowed(this, next);
            netBtn.setText(next ? "已开启 · 点我关闭" : "已关闭（纯离线） · 点我开启");
            netBtn.setBackgroundColor(Color.parseColor(next ? "#0FA968" : "#9AA4B2"));
            Toast.makeText(this, next
                    ? "已开启自动联网（3 天一次静默检查软件与规则更新）"
                    : "已进入纯离线模式 ✓\n所有自动联网已停止；核心功能不受影响，\n可在右上角「⋮」手动检查更新",
                    Toast.LENGTH_LONG).show();
        });
        netRow.addView(netBtn);
        root.addView(netRow);

        // ============ v2.7.0：写入目标选择器（多 ROM 支持） ============
        // 自动（按 ROM/已装 App 识别）→ 小米笔记待办 / ColorOS 日历待办 / ColorOS 便签置顶笔记
        LinearLayout backendRow = new LinearLayout(this);
        backendRow.setOrientation(LinearLayout.HORIZONTAL);
        backendRow.setGravity(Gravity.CENTER_VERTICAL);
        backendRow.setPadding(0, dp(6), 0, dp(2));

        TextView backendLabel = new TextView(this);
        backendLabel.setText("🎯 写入目标：" + backendLabel());
        backendLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        backendLabel.setTextColor(Color.parseColor("#1E6FE8"));
        backendLabel.setPadding(0, 0, dp(8), 0);
        backendLabelView = backendLabel;
        backendRow.addView(backendLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView backendBtn = new TextView(this);
        backendBtn.setText("切换");
        backendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        backendBtn.setTextColor(Color.WHITE);
        backendBtn.setBackgroundColor(Color.parseColor("#1E6FE8"));
        backendBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        backendBtn.setOnClickListener(v -> showBackendPicker(backendLabel));
        backendRow.addView(backendBtn);

        root.addView(backendRow);

        // ============ v2.9.0：通知取件提取开关（双通道，默认关） ============
        // 开启后 system_server 通知管线内拦截快递 App 通知并提取取件码
        // （菜鸟/京东/淘宝/拼多多/菜鸟裹裹/顺丰白名单初筛），与短信通道指纹去重合并。
        // 开关经标志文件同步给 system_server（同冻结免疫直写机制），切换后需重启手机生效。
        LinearLayout notiRow = new LinearLayout(this);
        notiRow.setOrientation(LinearLayout.HORIZONTAL);
        notiRow.setGravity(Gravity.CENTER_VERTICAL);
        notiRow.setPadding(0, dp(2), 0, dp(2));

        TextView notiLabel = new TextView(this);
        notiLabel.setText("🔔 通知取件提取（菜鸟/京东/淘宝等 App 通知）");
        notiLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        notiLabel.setTextColor(Color.parseColor("#333333"));
        notiLabel.setPadding(0, 0, dp(8), 0);
        notiRow.addView(notiLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        final TextView notiBtn = new TextView(this);
        boolean notiOn = isNotiHookFlagOn();
        notiBtn.setText(notiOn ? "已开启 · 点我关闭" : "默认关闭 · 点我开启");
        notiBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        notiBtn.setTextColor(Color.WHITE);
        notiBtn.setBackgroundColor(Color.parseColor(notiOn ? "#0FA968" : "#9AA4B2"));
        notiBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        notiBtn.setOnClickListener(v -> {
            boolean next = !isNotiHookFlagOn();
            toggleNotiHookFlag(next);
            notiBtn.setText(next ? "已开启 · 点我关闭" : "默认关闭 · 点我开启");
            notiBtn.setBackgroundColor(Color.parseColor(next ? "#0FA968" : "#9AA4B2"));
            Toast.makeText(this, next
                    ? "通知取件提取已开启 ✓（重启手机后生效）\n将拦截菜鸟/京东/淘宝/拼多多等 App 的取件通知"
                    : "通知取件提取已关闭（重启手机后系统侧同步生效）",
                    Toast.LENGTH_LONG).show();
        });
        notiRow.addView(notiBtn);
        root.addView(notiRow);

        statusCard = new TextView(this);
        statusCard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusCard.setTextColor(Color.parseColor("#333333"));
        statusCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusCard.setBackgroundColor(Color.parseColor("#F5F6FA"));
        statusCard.setText("⏳ 正在体检（root 检测约需 2-5 秒）…");
        // v2.5.3：体检卡可折叠——全绿时自动收起为一行，点按切换；有 ❌ 自动展开
        statusCard.setOnClickListener(v -> {
            healthExpanded = !healthExpanded;
            applyHealthCollapse();
        });
        root.addView(statusCard);

        // 排查问题（v2.5.0）：按「未注入 → root 未授权 → 作用域 → 组件」分层定位并给出动作
        TextView troubleshootBtn = new TextView(this);
        troubleshootBtn.setText("🔍 排查问题（点不了 / 收不到？点这里）");
        troubleshootBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        troubleshootBtn.setTextColor(Color.WHITE);
        troubleshootBtn.setBackgroundColor(Color.parseColor("#F0A020"));
        troubleshootBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        troubleshootBtn.setGravity(Gravity.CENTER);
        troubleshootBtn.setOnClickListener(v -> runTroubleshoot());
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tsLp.setMargins(dp(0), dp(8), dp(0), dp(0));
        troubleshootBtn.setLayoutParams(tsLp);
        root.addView(troubleshootBtn);

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

        // v2.5.3：独立的「导出诊断报告」按钮已移除——统一收进「🔍 排查问题」向导
        //（每层结果弹窗都带「📤 导出诊断报告」按钮，避免主界面按钮堆叠）

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
        // v2.5.3：保存三个按钮引用——切换模式只刷新高亮，不再 recreate 整个页面
        // （旧实现 recreate 会连带重跑六项体检，模板与体检无关联，纯属扰民）
        String[] labels = {"极简", "完整", "自定义"};
        for (int i = 0; i < 3; i++) {
            TextView mb = modeButton(labels[i], modeIds[i]);
            modeBtns.add(mb);
            modeRow.addView(mb);
        }
        // 高亮刷新器：按 currentMode 重着色，不重建界面
        restyleModes = () -> {
            for (int i = 0; i < modeBtns.size(); i++) {
                boolean sel = modeIds[i] == currentMode;
                modeBtns.get(i).setTextColor(sel ? Color.WHITE : Color.parseColor("#333333"));
                modeBtns.get(i).setBackgroundColor(sel
                        ? Color.parseColor("#1E6FE8") : Color.parseColor("#EDF1F7"));
            }
        };
        root.addView(modeRow);

        // ============ 模板效果预览 + 自定义编辑（v2.5.3 交互重构） ============
        // 极简/完整：只读效果预览；自定义：预览 + ✏️编辑 → 输入框 + 💾保存/↩️恢复默认/取消
        final String TPL_DEFAULT = "📦 取件码 {code}｜{source}｜{place}｜{time}";

        TextView tplPreview = new TextView(this);
        tplPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tplPreview.setTextColor(Color.parseColor("#666666"));
        tplPreview.setPadding(dp(12), dp(8), dp(12), dp(8));
        tplPreview.setBackgroundColor(Color.parseColor("#F0F3F8"));
        tplPreviewCard = tplPreview; // 成员引用：切模式时恢复预览可见性
        root.addView(tplPreview);

        // 「✏️ 编辑模板」按钮：仅自定义模式可见
        TextView tplEditBtn = smallButton("✏️ 编辑模板", "#F0A020");
        LinearLayout.LayoutParams tplEditLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tplEditLp.setMargins(dp(0), dp(6), dp(0), dp(0));
        tplEditBtn.setLayoutParams(tplEditLp);
        tplEditBtn.setVisibility(View.GONE);
        root.addView(tplEditBtn);

        // 编辑区：输入框 + 按钮行（保存/恢复默认/取消）
        EditText tplEdit = new EditText(this);
        tplEdit.setVisibility(View.GONE);
        tplEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        tplEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tplEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        tplEdit.setBackgroundColor(Color.parseColor("#FFFFFF"));
        tplEdit.setHint("自定义模板，占位符：{code} {source} {place} {time}");
        tplEditor = tplEdit; // 成员引用：切模式时收起编辑态
        root.addView(tplEdit);

        LinearLayout tplBtnRow = new LinearLayout(this);
        tplBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        tplBtnRow.setVisibility(View.GONE);
        TextView tplSave = smallButton("💾 保存设置", "#0FA968");
        TextView tplReset = smallButton("↩️ 恢复默认", "#9AA4B2");
        TextView tplCancel = smallButton("取消", "#9AA4B2");
        tplBtnRow.addView(tplSave);
        tplBtnRow.addView(tplReset);
        tplBtnRow.addView(tplCancel);
        tplEditorBtnRow = tplBtnRow; // 成员引用：切模式时收起编辑态
        root.addView(tplBtnRow);

        // 预览渲染器：按 currentMode 生成效果预览文本 + 编辑入口可见性
        Runnable renderTpl = () -> {
            String savedTpl = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("todo_custom", TPL_DEFAULT);
            if (currentMode == TodoWriter.MODE_CUSTOM) {
                tplPreview.setText("效果预览：\n"
                        + renderTemplate(savedTpl, "99-9-8888", "菜鸟驿站", "XX小区", "09-06 18:00"));
                tplEditBtn.setVisibility(View.VISIBLE);
            } else if (currentMode == TodoWriter.MODE_MIN) {
                tplPreview.setText("效果预览：\n99-9-8888");
                tplEditBtn.setVisibility(View.GONE);
            } else {
                tplPreview.setText("效果预览：\n📦 取件码 99-9-8888｜菜鸟驿站｜XX小区｜09-06 18:00");
                tplEditBtn.setVisibility(View.GONE);
            }
        };
        refreshTplPreview = renderTpl;
        renderTpl.run();

        tplEditBtn.setOnClickListener(v -> {
            tplEdit.setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("todo_custom", TPL_DEFAULT));
            tplEdit.setVisibility(View.VISIBLE);
            tplBtnRow.setVisibility(View.VISIBLE);
            tplEditBtn.setVisibility(View.GONE);
            tplPreview.setVisibility(View.GONE);
        });
        tplCancel.setOnClickListener(v -> exitTplEdit(tplEdit, tplBtnRow, tplEditBtn, tplPreview, renderTpl));
        // 保存：防御性校验（必须含 {code}，长度≤500，空/纯占位则自动落默认）
        tplSave.setOnClickListener(v -> {
            String t = tplEdit.getText().toString().trim();
            if (t.isEmpty()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("todo_custom", TPL_DEFAULT).apply();
                Toast.makeText(this, "内容为空，已恢复默认模板", Toast.LENGTH_SHORT).show();
                exitTplEdit(tplEdit, tplBtnRow, tplEditBtn, tplPreview, renderTpl);
                return;
            }
            if (t.length() > 500) {
                Toast.makeText(this, "模板过长（" + t.length() + "/500 字），请精简",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!t.contains("{code}")) {
                Toast.makeText(this, "模板必须包含 {code} 占位符（否则待办里看不到取件码）",
                        Toast.LENGTH_LONG).show();
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("todo_custom", t).apply();
            Toast.makeText(this, "自定义模板已保存并生效 ✓", Toast.LENGTH_SHORT).show();
            exitTplEdit(tplEdit, tplBtnRow, tplEditBtn, tplPreview, renderTpl);
        });
        tplReset.setOnClickListener(v -> {
            tplEdit.setText(TPL_DEFAULT);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("todo_custom", TPL_DEFAULT).apply();
            Toast.makeText(this, "已恢复默认模板", Toast.LENGTH_SHORT).show();
            exitTplEdit(tplEdit, tplBtnRow, tplEditBtn, tplPreview, renderTpl);
        });

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
                if (newList.isEmpty()) {
                    // v2.6.0：空黑名单 = 全放行（不过滤任何短信），给用户说明而不是静默接受
                    Toast.makeText(this, "黑名单已清空（所有短信不再被关键词过滤）", Toast.LENGTH_SHORT).show();
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("blacklist", newList).apply();
                if (!newList.isEmpty()) {
                    Toast.makeText(this, "黑名单已保存", Toast.LENGTH_SHORT).show();
                }
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

        root.addView(spacer(16));

        // ============ v2.7.0：系统直写兜底开关（动态显隐） ============
        // 作用：模块 App 被 ColorOS 冻结时，短信系统进程直接 su 写待办库，保证「划掉后台也能写」。
        // 代价：需要给系统进程（com.android.providers.telephony）授权 root。
        // v2.7.1 动态策略：小米系统下默认隐藏且关闭；ColorOS 下默认显示且开启（可手动关）。
        LinearLayout sdwContainer = new LinearLayout(this);
        sdwContainer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout sdwRow = new LinearLayout(this);
        sdwRow.setOrientation(LinearLayout.HORIZONTAL);
        sdwRow.setGravity(Gravity.CENTER_VERTICAL);
        sdwRow.setPadding(0, dp(2), 0, dp(2));

        TextView sdwLabel = new TextView(this);
        sdwLabel.setText("🛡 冻结免疫直写（ColorOS 划掉后台仍可写入）");
        sdwLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sdwLabel.setTextColor(Color.parseColor("#333333"));
        sdwLabel.setPadding(0, 0, dp(8), 0);
        sdwRow.addView(sdwLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView sdwBtn = new TextView(this);
        sdwBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sdwBtn.setTextColor(Color.WHITE);
        sdwBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        sdwRow.addView(sdwBtn);
        sdwContainer.addView(sdwRow);

        TextView sdwHint = new TextView(this);
        sdwHint.setText("⚠️ 开启后需在 Magisk/KernelSU 里给「短信存储（com.android.providers.telephony）」"
                + "授权 root——给常驻系统进程授权 root 会扩大风险面，请自行评估。"
                + "小米系统不需要此功能，ColorOS 下划掉后台时生效。");
        sdwHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sdwHint.setTextColor(Color.parseColor("#999999"));
        sdwHint.setLineSpacing(dp(2), 1.0f);
        sdwContainer.addView(sdwHint);

        root.addView(sdwContainer);

        // 绑定刷新器并在点击切换后调用
        refreshSdwUi = () -> updateSdwDisplay(sdwContainer, sdwBtn);
        sdwBtn.setOnClickListener(v -> toggleSysDirectWrite(sdwBtn));
        refreshSdwUi.run();

        root.addView(spacer(18));

        TextView help = new TextView(this);
        help.setText("📖 使用说明\n"
                + "1. LSPosed 激活模块；作用域必勾「Android 系统」（android，列表底部、"
                + "不带推荐角标——不是「系统框架」system！），再加 电话、短信、"
                + "com.android.providers.telephony；小米设备另勾「小米笔记」\n"
                + "2. 写入目标自动识别（小米笔记待办 / ColorOS 日历待办），也可点上方「切换」手动指定\n"
                + "3. 收到取件短信后自动写入待办（一码一条，新码置顶）；开启「🔔 通知取件提取」后\n"
                + "   菜鸟/京东/淘宝/拼多多等 App 的取件通知也能提取（双通道自动去重）\n"
                + "4. 通知可点击：复制取件码；通知上「已取件」：一键勾选\n"
                + "5. 需要 root（Magisk/KernelSU）授权一次；sqlite3 缺失时点「一键部署」\n"
                + "6. 遇到问题：点上方「🔍 排查问题」逐步定位；向导里可顺路"
                + "「导出诊断报告」发给作者\n"
                + "7. 短信漏抓反馈：右上角「⋮」可打开「疑似漏抓错题本」一键脱敏复制上报\n"
                + "8. 交流学习 / 反馈问题：QQ 群 901543676（进群备注「取件码助手」）");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        help.setTextColor(Color.parseColor("#888888"));
        help.setLineSpacing(dp(3), 1.0f);
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    /** 右上角菜单（v2.5.0）：项目仓库 / 打赏作者 */
    private void showMainMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "🏠 项目仓库（GitHub）");
        pm.getMenu().add(0, 3, 1, "🔍 检查软件更新");
        pm.getMenu().add(0, 4, 2, "⚡ 检查规则更新（热更新）");
        pm.getMenu().add(0, 5, 3, "📋 疑似漏抓错题本");
        pm.getMenu().add(0, 2, 4, "💰 打赏作者");
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) openUrl(REPO_URL);
            else if (id == 3) {
                Toast.makeText(this, "正在检查软件更新…", Toast.LENGTH_SHORT).show();
                Updater.maybeCheck(this, true);
            }
            else if (id == 4) {
                Toast.makeText(this, "正在拉取最新规则集…", Toast.LENGTH_SHORT).show();
                RulesUpdater.checkUpdate(this, true, () -> refreshHealth());
            }
            else if (id == 5) {
                showMissedSmsDialog();
            }
            else if (id == 2) startActivity(new Intent(this, DonateActivity.class));
            return true;
        });
        pm.show();
    }

    /** v2.8.0：弹窗展示疑似漏抓错题本，支持一键脱敏复制上报 */
    private void showMissedSmsDialog() {
        List<MissedSmsStore.Sample> list = MissedSmsStore.getAll(this);
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("📋 疑似漏抓错题本")
                    .setMessage("当前错题本为空 ✓\n\n当系统收到含有快递特征词但未能提取出取件码的短信时，会自动记录在此处，供您一键脱敏上报排查。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("本地共捕获 ").append(list.size()).append(" 条疑似漏抓短信：\n\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i + 1).append(". ").append(MissedSmsStore.sanitize(list.get(i).body)).append("\n\n");
        }

        new AlertDialog.Builder(this)
                .setTitle("📋 疑似漏抓错题本 (" + list.size() + " 条)")
                .setMessage(sb.toString().trim())
                .setPositiveButton("📋 复制全部上报文本", (d, w) -> {
                    String report = MissedSmsStore.formatReport(list);
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("missed_sms_report", report));
                        Toast.makeText(this, "已复制脱敏上报文本 ✓\n可直接粘贴至 QQ 群或 GitHub Issues", Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("🗑️ 清空", (d, w) -> {
                    MissedSmsStore.clear(this);
                    Toast.makeText(this, "错题本已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ==================== v2.7.0：写入目标（多 ROM 后端） ====================

    /** 副标题/选择器当前后端的人话名（随 NotesBackend 自动/手动结果切换） */
    private String backendLabel() {
        String b = NotesBackend.detect(this);
        if (NotesBackend.BACKEND_XIAOMI.equals(b)) return "小米笔记待办";
        if (NotesBackend.BACKEND_COLOROS_NOTE.equals(b)) return "ColorOS 便签置顶笔记";
        return "ColorOS 日历待办";
    }

    /** 写入目标选择器：自动 / 三个具体后端（ColorOS 机型上"自动"默认走日历待办） */
    private void showBackendPicker(TextView label) {
        String cur = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_BACKEND, "");
        final String[] values = {
                "", NotesBackend.BACKEND_XIAOMI, NotesBackend.BACKEND_COLOROS_TODO, NotesBackend.BACKEND_COLOROS_NOTE
        };
        CharSequence[] items = {
                "自动识别（推荐）",
                "小米笔记 待办（HyperOS/MIUI）",
                "ColorOS 日历 待办（ColorOS 16 默认）",
                "ColorOS 便签 置顶笔记（备用）"
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(cur)) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择写入目标")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    applyBackend(values[which]);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 应用后端选择：写偏好 + 失效缓存 + 刷新副标题/选择器文案 + 复查体检（换库后探针要重跑） */
    private void applyBackend(String value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_BACKEND, value).apply();
        NotesBackend.invalidateCache();
        String name;
        if (NotesBackend.BACKEND_XIAOMI.equals(value)) name = "小米笔记待办";
        else if (NotesBackend.BACKEND_COLOROS_TODO.equals(value)) name = "ColorOS 日历待办";
        else if (NotesBackend.BACKEND_COLOROS_NOTE.equals(value)) name = "ColorOS 便签置顶笔记";
        else name = "自动识别";
        Toast.makeText(this, "写入目标已切换：\n" + name
                + "\n（下次收到取件短信即写入新目标；可点「🧪 一键测试」立即验证）", Toast.LENGTH_LONG).show();
        // 重建页面成本高（模板编辑态等局部状态），这里直接刷新副标题与选择器文案即可
        if (subTitleView != null) subTitleView.setText("v2.7.0 · LSPosed 模块 · 自动提取取件码写入 " + backendLabel());
        if (backendLabelView != null) backendLabelView.setText("🎯 写入目标：" + backendLabel());
        if (refreshSdwUi != null) refreshSdwUi.run();
        refreshHealth();
    }

    /**
     * v2.7.0：切换「冻结免疫直写」开关。
     * 模块 App 偏好存 SharedPreferences；系统进程（Hook 所在）读不到它，因此同时用 su 把
     * "1"/"0" 写入 /data/local/tmp/pickup_sqlite/enable_sys_direct_write 标志文件同步状态
     * （SystemDirectWriter 启动时 root 读该文件决定是否启用）。切换后需重启手机让系统进程重载。
     */
    private void toggleSysDirectWrite(TextView btn) {
        boolean now = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SYS_DIRECT, false);
        boolean next = !now;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SYS_DIRECT, next).apply();
        // 异步同步标志文件（需 su；失败也只影响系统进程侧开关，App 内状态已更新）
        final boolean target = next;
        new Thread(() -> {
            String flag = Repair.TARGET_DIR + "/enable_sys_direct_write";
            try {
                Diagnostics.suExecPublic("mkdir -p " + Repair.TARGET_DIR + "; "
                        + "echo " + (target ? "1" : "0") + " > " + flag + "; "
                        + "chmod 644 " + flag, 15);
            } catch (Throwable ignored) { }
            runOnUiThread(() -> {
                btn.setText(target ? "已开启 · 点我关闭" : "默认关闭 · 点我开启");
                btn.setBackgroundColor(Color.parseColor(target ? "#0FA968" : "#9AA4B2"));
                if (target) {
                    Toast.makeText(this, "已开启冻结免疫直写 ✓\n"
                            + "还需两步：\n1. Magisk/KernelSU 给「com.android.providers.telephony」"
                            + "（短信存储）授权 root\n2. 重启手机生效", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "已关闭冻结免疫直写（重启手机后系统进程侧同步生效）",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** v2.9.0：读通知钩子标志文件（system_server 侧唯一开关来源，默认关） */
    private boolean isNotiHookFlagOn() {
        try {
            java.io.File f = new java.io.File(Repair.TARGET_DIR + "/enable_noti_hook");
            if (!f.exists()) return false;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            return line != null && line.trim().equals("1");
        } catch (Throwable t) {
            return false;
        }
    }

    /** v2.9.0：写通知钩子标志文件（su 同步给 system_server 侧） */
    private void toggleNotiHookFlag(boolean on) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("noti_hook", on).apply();
        new Thread(() -> {
            String flag = Repair.TARGET_DIR + "/enable_noti_hook";
            try {
                Diagnostics.suExecPublic("mkdir -p " + Repair.TARGET_DIR + "; "
                        + "echo " + (on ? "1" : "0") + " > " + flag + "; "
                        + "chmod 644 " + flag, 15);
            } catch (Throwable ignored) { }
        }).start();
    }

    /**
     * v2.7.1：根据系统类型或当前写入目标动态刷新「冻结免疫直写」的显隐与默认值。
     * - 小米系统 / 小米笔记待办：隐藏且默认关闭，不需要此功能。
     * - ColorOS 系统 / 日历/便签目标：显示，默认开启（可手动关）。
     */
    private void updateSdwDisplay(LinearLayout container, TextView btn) {
        String currentBackend = NotesBackend.detect(this);
        boolean isColorOs = NotesBackend.BACKEND_COLOROS_TODO.equals(currentBackend)
                || NotesBackend.BACKEND_COLOROS_NOTE.equals(currentBackend);

        if (!isColorOs) {
            // 小米环境：直接隐藏栏目，并确保开关关闭
            container.setVisibility(View.GONE);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SYS_DIRECT, false).apply();
            return;
        }

        // ColorOS 环境：显示栏目，首次未配置时默认开启
        container.setVisibility(View.VISIBLE);
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean sdwOn;
        if (!sp.contains(KEY_SYS_DIRECT)) {
            // 首次进入 ColorOS 环境，默认开启并写入同步文件
            sdwOn = true;
            sp.edit().putBoolean(KEY_SYS_DIRECT, true).apply();
            new Thread(() -> {
                String flag = Repair.TARGET_DIR + "/enable_sys_direct_write";
                try {
                    Diagnostics.suExecPublic("mkdir -p " + Repair.TARGET_DIR + "; "
                            + "echo 1 > " + flag + "; chmod 644 " + flag, 15);
                } catch (Throwable ignored) { }
            }).start();
        } else {
            sdwOn = sp.getBoolean(KEY_SYS_DIRECT, true);
        }

        btn.setText(sdwOn ? "已开启 · 点我关闭" : "已关闭 · 点我开启");
        btn.setBackgroundColor(Color.parseColor(sdwOn ? "#0FA968" : "#9AA4B2"));
    }

    /**
     * 排查问题（v2.5.0）：按「未注入 → root 未授权 → 作用域缺失 → 通知权限 →
     * sqlite3 组件缺失（自动修复）→ 笔记库异常 → 一切 OK」的决策树逐层定位。
     */
    private void runTroubleshoot() {
        Toast.makeText(this, "正在排查…（约 2-5 秒）", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Diagnostics.Check> checks;
            try {
                checks = Diagnostics.healthCheck(this);
            } catch (Throwable t) {
                showTroubleshootDialog("排查异常：" + t.getMessage(), null, null);
                return;
            }
            Diagnostics.Check inject = findCheck(checks, "LSPosed 注入");
            Diagnostics.Check root = findCheck(checks, "root 授权");
            Diagnostics.Check scope = findCheck(checks, "LSPosed 作用域");
            Diagnostics.Check notif = findCheck(checks, "通知权限");
            Diagnostics.Check sqlite = findCheck(checks, "sqlite3 部署");
            Diagnostics.Check notes = findCheck(checks, "待办库访问");

            if (inject == null || !inject.ok) {
                String backendHint = NotesBackend.BACKEND_XIAOMI.equals(NotesBackend.detect(this))
                        ? "（小米侧含 com.miui.notes；写入目标 App 无需勾选）"
                        : "（勾 3 项即可；写入目标 App 无需勾选）";
                showTroubleshootDialog(
                        "【第 1 步：让模块生效】\n\n"
                        + "1. 打开 LSPosed 管理器 → 模块 → 取件码助手\n"
                        + "2. 打开「启用模块」开关\n"
                        + "3. 勾选作用域：⚠️ 必须包含「Android 系统」，"
                        + "再加 电话、短信" + backendHint + "\n"
                        + "4. 重启手机（必须）\n"
                        + "5. 重启后打开本 App，再点一次「🔍 排查问题」", null, null);
                return;
            }
            if (root == null || !root.ok) {
                showTroubleshootDialog(
                        "【第 2 步：把 root 授权给本模块】\n\n"
                        + "注入已成功 ✓，但 root 授权还没通过（体检红色叉的原因）。\n\n"
                        + "1. 打开 Magisk → 超级用户 → 找到「取件码助手」→ 打开开关\n"
                        + "   （列表里没有的话：点一次本页任意按钮触发授权弹窗，"
                        + "弹窗选【允许】并勾选\"不再询问\"）\n"
                        + "2. 授权后关掉本弹窗，页面会自动重新核查 → 再点「排查问题」继续",
                        "打开 Magisk", "com.topjohnwu.magisk");
                return;
            }
            if (scope != null && !scope.ok) {
                showTroubleshootDialog(
                        "【第 3 步：补齐作用域】\n\n" + nz(scope.detail)
                        + "\n\n⚠️ 特别提醒：「Android 系统（android）」在列表底部，"
                        + "【不带\"推荐应用\"角标】也必须勾选（带角标的 5 个推荐应用不含它）；"
                        + "别勾成中部的「系统框架（system）」——勾了无效！\n"
                        + "补勾后务必【重启手机】", null, null);
                return;
            }
            if (notif != null && !notif.ok) {
                showTroubleshootDialog(
                        "【第 4 步：打开通知权限】\n\n" + nz(notif.detail)
                        + "\n\n（不处理不影响写待办，但收不到取件码弹窗）", null, null);
                return;
            }
            if (sqlite != null && !sqlite.ok
                    && sqlite.detail != null && sqlite.detail.contains("一键部署")) {
                runOnUiThread(() -> Toast.makeText(this,
                        "检测到 sqlite3 未部署 → 自动安装中（如弹出授权请允许）…",
                        Toast.LENGTH_SHORT).show());
                new Thread(() -> {
                    String res;
                    try {
                        res = Repair.deploySqlite3(this);
                    } catch (Throwable t) {
                        res = "部署异常：" + t.getMessage();
                    }
                    final String r2 = res;
                    runOnUiThread(() -> {
                        Toast.makeText(this, r2, Toast.LENGTH_LONG).show();
                        refreshHealth();
                    });
                }).start();
                return;
            }
            if (notes != null && !notes.ok) {
                String target = NotesBackend.targetAppName(this);
                showTroubleshootDialog(
                        "【第 5 步：待办库访问异常（" + target + "）】\n\n" + nz(notes.detail)
                        + "\n\n先点「🧪 一键测试」触发一次自动修复；仍失败 → "
                        + "导出诊断报告 + 加 QQ 群 901543676 反馈给作者", null, null);
                return;
            }
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("✅ 排查完成")
                    .setMessage("一切 OK！六项检查全部通过。\n\n"
                            + "当前规则集：v" + PickupExtractor.getActiveRulesVersion() + "\n\n"
                            + "如果还是收不到取件码：\n"
                            + "1. 点「🧪 一键测试」验证写入链路\n"
                            + "2. 查看右上角「⋮ -> 疑似漏抓错题本」一键上报未识别短信\n"
                            + "3. 仍异常 → 导出诊断报告 + QQ 群 901543676（备注「取件码助手」）")
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("📤 导出诊断报告", (d, w) -> exportDiag())
                    .setNegativeButton("💬 反馈给作者", (d, w) -> showFeedbackChannels())
                    .show());
        }).start();
    }

    /** 排查结果弹窗：带「导出诊断报告」与（可选）「打开指定 App」动作，关闭时自动复查 */
    private void showTroubleshootDialog(String msg, String actionLabel, String actionPkg) {
        runOnUiThread(() -> {
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle("🔍 排查结果")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .setNegativeButton("📤 导出诊断报告", (d, w) -> exportDiag());
            if (actionPkg != null && actionLabel != null) {
                b.setNeutralButton(actionLabel, (d, w) -> {
                    try {
                        Intent li = getPackageManager().getLaunchIntentForPackage(actionPkg);
                        if (li != null) startActivity(li);
                        else openUrl(REPO_URL);
                    } catch (Throwable t) {
                        openUrl(REPO_URL);
                    }
                });
            } else {
                b.setNeutralButton("🐛 反馈给作者", (d, w) -> showFeedbackChannels());
            }
            AlertDialog dlg = b.create();
            dlg.setOnDismissListener(d -> refreshHealth());
            dlg.show();
        });
    }

    /** 复制 QQ 群号到剪贴板（v2.5.3） */
    private void copyQqGroup() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("qq_group", "901543676"));
            Toast.makeText(this, "群号已复制：901543676\n进群请备注「取件码助手」", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败，群号：901543676", Toast.LENGTH_SHORT).show();
        }
    }

    /** 反馈渠道选择（v2.5.3）：QQ 群（复制群号）/ GitHub Issues / 酷安帖子 */
    private void showFeedbackChannels() {
        new AlertDialog.Builder(this)
                .setTitle("选择反馈渠道")
                .setItems(new CharSequence[]{
                        "💬 QQ 群：901543676（复制群号）",
                        "🐛 GitHub Issues",
                        "📱 酷安发布帖"
                }, (d, which) -> {
                    if (which == 0) {
                        copyQqGroup();
                    } else if (which == 1) {
                        openUrl(ISSUES_URL);
                    } else {
                        openUrl(COOLAPK_URL);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 生成并导出诊断报告（自动脱敏），提示反馈渠道 */
    private void exportDiag() {
        Toast.makeText(this, "正在生成诊断报告…（含日志提取，约 5-10 秒）", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String report = Diagnostics.collectReport(this);
                String where = Diagnostics.exportAndShare(this, report);
                runOnUiThread(() -> Toast.makeText(this,
                        "报告已生成：" + where + "（已自动脱敏）\n"
                                + "反馈渠道：QQ 群 901543676（备注「取件码助手」）/ "
                                + "GitHub Issues / 酷安帖子留言",
                        Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                        "报告生成失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "未找到可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private Diagnostics.Check findCheck(List<Diagnostics.Check> checks, String titlePrefix) {
        for (Diagnostics.Check c : checks) {
            if (c != null && c.title != null && c.title.startsWith(titlePrefix)) return c;
        }
        return null;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** 一键测试：随机取件码（永不撞去重）→ 走完整链路 */
    private void runSelfTest() {
        try {
            String code = "99-9-" + String.format("%04d", new Random().nextInt(10000));
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("sender", "test");
            extras.putString("body", "【菜鸟驿站】测试包裹：取件码为" + code
                    + "，请到XX小区快递驿站取件（测试条目，可删除）");
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
                        + "\n（详查：点「🔍 排查问题」→ 向导里可导出诊断报告）", Toast.LENGTH_LONG).show();
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
            boolean allOk = true;
            try {
                StringBuilder sb = new StringBuilder("🩺 部署体检\n");
                for (Diagnostics.Check c : Diagnostics.healthCheck(this)) {
                    sb.append(c.ok ? "✅ " : "❌ ").append(c.title)
                      .append("：").append(c.detail).append("\n");
                    if (!c.ok) allOk = false;
                    if ("sqlite3 部署".equals(c.title) && !c.ok
                            && c.detail != null && c.detail.contains("一键部署")) {
                        needDeploy = true;
                    }
                }
                // v2.5.3：不再追加"模板模式"行——模板与体检已解耦（切换模板不再触发体检），
                // 模式高亮由模板按钮组自己表达
                text = sb.toString();
            } catch (Throwable t) {
                allOk = false;
                text = "体检异常：" + t.getMessage();
            }
            final String t2 = text;
            final boolean show = needDeploy;
            final boolean ok = allOk;
            runOnUiThread(() -> {
                lastHealthText = t2;
                // v2.5.3：全绿自动折叠成一行；有 ❌ 强制全量展示；点按可切换
                if (ok && healthExpanded == null) healthExpanded = false;
                if (!ok) healthExpanded = true;
                if (healthExpanded) {
                    statusCard.setText(t2);
                    statusCard.setMaxLines(Integer.MAX_VALUE);
                } else {
                    statusCard.setText("✅ 一切正常 · 六项体检全部通过（规则集: v" + PickupExtractor.getActiveRulesVersion() + " · 点开看详情）");
                    statusCard.setMaxLines(2);
                }
                deployBtn.setVisibility(show ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    /** v2.5.3：点按切换折叠/展开 */
    private void applyHealthCollapse() {
        if (healthExpanded == null || lastHealthText == null) return;
        if (healthExpanded) {
            statusCard.setText(lastHealthText);
            statusCard.setMaxLines(Integer.MAX_VALUE);
        } else {
            statusCard.setText("✅ 一切正常 · 六项体检全部通过（规则集: v" + PickupExtractor.getActiveRulesVersion() + " · 点开看详情）");
            statusCard.setMaxLines(2);
        }
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
        refreshHealth(); // v2.6.0：直接调用（updateStatus 壳已删）
        Updater.maybeCheck(this, false);
    }

    /** 通知点击：复制取件码 + 打开当前后端对应的待办 App（v2.7.0：跳转包名动态化） */
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
            Intent notes = getPackageManager().getLaunchIntentForPackage(NotesBackend.targetPkg(this));
            if (notes != null) startActivity(notes);
        } catch (Throwable ignored) { }
        intent.removeExtra("copy");
    }

    /** 通知"已取件"按钮：勾选对应待办（v2.7.0：SQL 随后端分派） */
    private void handleDone(Intent intent) {
        if (intent == null) return;
        String code = intent.getStringExtra("done");
        if (code == null || code.isEmpty()) return;
        String sql = NotesBackend.markDoneSql(this, code);
        int rc = TodoWriter.runSql(this, sql);
        Toast.makeText(this, rc == 0 ? "已标记已取件：" + code : "标记失败（rc=" + rc + "）",
                Toast.LENGTH_LONG).show();
        intent.removeExtra("done");
        if (rc == 0) {
            try {
                Intent notes = getPackageManager().getLaunchIntentForPackage(NotesBackend.targetPkg(this));
                if (notes != null) startActivity(notes);
            } catch (Throwable ignored) { }
            finish();
        }
    }

    /** 渲染模板效果预览（v2.5.3）：占位符替换为样例值 */
    private static String renderTemplate(String tpl, String code, String source, String place, String time) {
        if (tpl == null || tpl.isEmpty()) return "（空模板）";
        return tpl.replace("{code}", code)
                .replace("{source}", source)
                .replace("{place}", place)
                .replace("{time}", time);
    }

    /** 退出模板编辑态：隐藏输入框/按钮行，恢复预览卡与编辑按钮（v2.5.3） */
    private void exitTplEdit(EditText tplEdit, LinearLayout tplBtnRow,
                             TextView tplEditBtn, TextView tplPreview, Runnable render) {
        tplEdit.setVisibility(View.GONE);
        tplBtnRow.setVisibility(View.GONE);
        tplPreview.setVisibility(View.VISIBLE);
        if (currentMode == TodoWriter.MODE_CUSTOM) tplEditBtn.setVisibility(View.VISIBLE);
        if (render != null) render.run();
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
            if (mode == currentMode) return; // 已是当前模式，无操作（也不弹 Toast 打扰）
            currentMode = mode;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("todo_mode", mode).apply();
            if (restyleModes != null) restyleModes.run(); // 只重着色高亮，不 recreate
            if (refreshTplPreview != null) refreshTplPreview.run(); // 刷新效果预览
            // 若模板编辑框还开着，切换模式时收起编辑态（未保存的内容丢弃，防误操作）
            if (tplEditor != null && tplEditor.getVisibility() == View.VISIBLE) {
                tplEditor.setVisibility(View.GONE);
                if (tplEditorBtnRow != null) tplEditorBtnRow.setVisibility(View.GONE);
                if (tplPreviewCard != null) tplPreviewCard.setVisibility(View.VISIBLE);
            }
            Toast.makeText(this, "待办模板已切换为「" + label + "」", Toast.LENGTH_SHORT).show();
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
