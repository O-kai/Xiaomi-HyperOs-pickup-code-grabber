package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户自定义规则管理界面（v3.0.0「高级功能」）
 *
 * 设计原则（对齐 UserRules 类的安全承诺）：
 *  - 权限边界：这里只能配置「从短信里提取什么」，不存在任何数据库/写入配置入口；
 *  - 强制自测：保存与启用前必须自测通过（含 50ms 熔断），失败给出逐条明确原因；
 *  - 风险告知：界面顶部常驻说明，明确告知危险正则会导致短信处理卡顿，责任自负；
 *  - 参考案例：内置官方规则示例 + 常见错误示例，降低正则门槛。
 */
public class UserRulesActivity extends Activity {

    private List<UserRules.Rule> rules;
    private TextView statusLine;
    private UiKit.Palette P;              // 当前配色（与主界面一致）
    private LinearLayout listBox;         // 规则列表容器（刷新时只重绘这里）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PickupExtractor.init(this);
        P = UiKit.palette(this);
        rules = UserRules.loadAll(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(24));
        root.setBackgroundColor(P.bg);

        TextView title = new TextView(this);
        title.setText("用户自定义规则");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(UiKit.INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        // 使用前必读（金色提示卡 + 警示图标，与主界面提示条同款）
        TextView notice = new TextView(this);
        notice.setText("使用前必读\n"
                + "· 本功能只能定义「怎么从短信里提取取件码/来源/地点」，"
                + "无法修改任何数据库写入行为（安全边界，不可配置）\n"
                + "· 规则带【强制自测】：必须粘贴真实短信样本并填写期望取件码，自测通过后才能启用\n"
                + "· 单条样本执行超过 " + UserRules.SAMPLE_TIME_LIMIT_MS
                + "ms 将被熔断拒绝（防止危险正则卡住短信处理）\n"
                + "· 禁止嵌套量词等可能引发指数级回溯的写法（保存时会自动扫描拦截）\n"
                + "· 规则由你自己编写与使用，匹配结果自负；不确定请先用「测试」验证");
        notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        notice.setTextColor(P.heroSubInk);
        notice.setLineSpacing(dp(3), 1.0f);
        notice.setPadding(dp(13), dp(11), dp(13), dp(11));
        notice.setBackground(UiKit.bg(P.accentTint, this, 12));
        android.graphics.drawable.Drawable warnIcon = getDrawable(R.drawable.ic_alert);
        if (warnIcon != null) {
            warnIcon = warnIcon.mutate();
            warnIcon.setTint(P.accentDeep);
            warnIcon.setBounds(0, 0, dp(15), dp(15));
            notice.setCompoundDrawables(warnIcon, null, null, null);
            notice.setCompoundDrawablePadding(dp(6));
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(0, dp(12), 0, 0);
        root.addView(notice, nlp);

        // 总开关（与主界面同款开关）
        LinearLayout swCard = UiKit.card(this, P);
        UiKit.Switch sw = new UiKit.Switch(this, P, UserRules.isEnabled(this));
        sw.setOnToggle(on -> {
            UserRules.setEnabled(this, on);
            Toast.makeText(this, on
                    ? "已启用 " + countEnabled() + " 条用户规则（优先级高于官方规则）"
                    : "已停用全部用户规则，回到官方规则", Toast.LENGTH_LONG).show();
            refreshStatus();
        });
        swCard.addView(UiKit.row(this, P, R.drawable.ic_rules, "启用我的规则",
                "开启后优先于官方规则匹配", sw));
        root.addView(swCard, UiKit.cardLp(this, 12));

        statusLine = new TextView(this);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusLine.setTextColor(UiKit.INK3);
        statusLine.setPadding(dp(6), dp(12), dp(6), dp(4));
        root.addView(statusLine);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);
        renderList(listBox);

        // 操作按钮（带矢量图标，配色与主界面一致）
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arLp.setMargins(0, dp(14), 0, 0);
        actRow.setLayoutParams(arLp);
        actRow.addView(actBtn("新建规则", R.drawable.ic_plus, P.accent, true, () -> showEditor(null)));
        actRow.addView(actBtn("导出分享", R.drawable.ic_upload, P.accentTint, false, this::doExport));
        actRow.addView(actBtn("导入", R.drawable.ic_download, P.accentTint, false, this::doImport));
        root.addView(actRow);

        TextView help = new TextView(this);
        help.setText("不会写正则？点「新建规则」后用「参考案例」照着改。\n"
                + "导出可把规则分享到 QQ 群；想贡献给官方请把导出文本发到 GitHub Issue"
                + "（记得自己先脱敏短信里的姓名/手机号/住址）");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        help.setTextColor(UiKit.INK3);
        help.setLineSpacing(dp(3), 1.0f);
        help.setPadding(dp(6), dp(12), dp(6), 0);
        root.addView(help);

        TextView docBtn = new TextView(this);
        docBtn.setText("查看完整参考手册（GitHub）");
        docBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        docBtn.setTextColor(P.accentInk);
        docBtn.setGravity(Gravity.CENTER);
        docBtn.setPadding(dp(8), dp(12), dp(8), dp(12));
        docBtn.setBackground(UiKit.bg(0x00000000, this, 13, P.accent, 1.4f));
        android.graphics.drawable.Drawable bookIcon = getDrawable(R.drawable.ic_book);
        if (bookIcon != null) {
            bookIcon = bookIcon.mutate();
            bookIcon.setTint(P.accentInk);
            bookIcon.setBounds(0, 0, dp(16), dp(16));
            docBtn.setCompoundDrawables(bookIcon, null, null, null);
            docBtn.setCompoundDrawablePadding(dp(6));
        }
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(12), 0, 0);
        docBtn.setLayoutParams(dlp);
        docBtn.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("url",
                        "https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/blob/main/docs/user-rules-guide.md"));
                Toast.makeText(this, "手册链接已复制，粘贴到浏览器打开", Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(docBtn);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        refreshStatus();
    }

    /** 带矢量图标的操作按钮（三等分一行）：实心 / 描边两种样式，图标与文字同色 */
    private TextView actBtn(String label, int iconRes, int bgColor, boolean solid, Runnable run) {
        TextView b = new TextView(this);
        int textColor = solid ? 0xFFFFFFFF : P.accentInk;
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        b.setBackground(solid
                ? UiKit.bg(bgColor, this, 13)
                : UiKit.bg(bgColor, this, 13, P.accent, 1.4f));
        android.graphics.drawable.Drawable d = getDrawable(iconRes);
        if (d != null) {
            d = d.mutate();
            d.setTint(textColor);
            d.setBounds(0, 0, dp(15), dp(15));
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(5));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> run.run());
        return b;
    }

    /** 匹配模式短名（卡片展示用） */
    private String shortMode(String m) {
        if (UserRules.MODE_EXACT.equals(m)) return "精确";
        if (UserRules.MODE_PREFIX.equals(m)) return "前缀";
        if (UserRules.MODE_CONTAINS.equals(m)) return "包含";
        return "不限";
    }

    private int countEnabled() {
        int n = 0;
        for (UserRules.Rule r : rules) if (r.enabled) n++;
        return n;
    }

    private void refreshStatus() {
        int on = countEnabled();
        statusLine.setText("共 " + rules.size() + " 条规则，已启用 " + on + " 条（上限 "
                + UserRules.MAX_RULES + " 条）｜当前官方规则集 v"
                + PickupExtractor.getActiveRulesVersion()
                + "｜用户规则优先级：高于官方规则");
    }

    private void renderList(LinearLayout box) {
        box.removeAllViews();
        if (rules.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无自定义规则。\n若某条取件短信没被识别，可点下方「新建规则」为它单独写一条。");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            empty.setTextColor(UiKit.INK3);
            empty.setLineSpacing(dp(3), 1.0f);
            empty.setPadding(dp(6), dp(12), dp(6), dp(12));
            box.addView(empty);
            return;
        }
        for (final UserRules.Rule r : rules) {
            LinearLayout card = UiKit.card(this, P);
            card.setPadding(dp(14), dp(13), dp(14), dp(10));

            // 标题行：名称 + 状态徽标
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(UserRules.safeName(r));
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
            name.setTextColor(UiKit.INK);
            head.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            head.addView(r.enabled
                    ? UiKit.badgeOk(this, P, "已启用")
                    : UiKit.badgeMute(this, P, "已停用"));
            card.addView(head);

            TextView detail = new TextView(this);
            String scope = (r.senderMode == null || r.senderMode.isEmpty()
                    || UserRules.MODE_ANY.equals(r.senderMode))
                    ? "全局（不限发送方）"
                    : shortMode(r.senderMode) + "：" + r.senderValue;
            detail.setText("发送方：" + scope
                    + "\n正则：" + r.codeRegex
                    + "\n自测样本：" + r.testCases.size()
                    + " 条｜来源：" + (r.source == null || r.source.isEmpty() ? "自动识别" : r.source)
                    + "｜地点：" + (r.place == null || r.place.isEmpty() ? "自动识别" : r.place));
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            detail.setTextColor(UiKit.INK3);
            detail.setLineSpacing(dp(2), 1.0f);
            LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dvp.setMargins(0, dp(6), 0, 0);
            card.addView(detail, dvp);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, dp(10), 0, 0);
            row.setLayoutParams(rlp);
            row.addView(actionGhost("测试", P.accentInk, v -> showTestResult(r)));
            row.addView(actionGhost("编辑", P.accentInk, v -> showEditor(r)));
            row.addView(actionGhost(r.enabled ? "停用" : "启用", UiKit.INK2, v -> {
                UserRules.VerifyResult vr = UserRules.verify(r);
                if (!r.enabled && !vr.ok) {
                    UiKit.dialog(this, P, R.drawable.ic_alert, "启用被拒绝",
                            "自测未通过，不能启用：\n\n" + vr.message, null,
                            UiKit.primary("去编辑", () -> showEditor(r)),
                            UiKit.ghost("知道了", null));
                    return;
                }
                r.enabled = !r.enabled;
                persistAndRefresh();
            }));
            row.addView(actionGhost("删除", UiKit.DANGER, v -> confirmDelete(r)));
            card.addView(row);
            box.addView(card, UiKit.cardLp(this, 8));
        }
    }

    /** 幽灵按钮：浅底 + 描边 + 指定文字色（卡片内操作用） */
    private TextView actionGhost(String label, int textColor, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setTextColor(textColor);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setBackground(UiKit.bg(P.accentTint, this, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private void persistAndRefresh() {
        String err = UserRules.saveAll(this, rules);
        if (err != null) {
            Toast.makeText(this, "保存失败：" + err, Toast.LENGTH_LONG).show();
            return;
        }
        // 立即刷新内存中的规则缓存
        PickupExtractor.loadUserRules(this, UserRules.loadAll(this));
        renderList(listBox);   // v4.0.1：此前传的是 statusLine.getParent()（= 整个 root），保存后标题/开关/说明会被一并清掉
        refreshStatus();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(final UserRules.Rule r) {
        UiKit.dialog(this, P, R.drawable.ic_ban, "删除规则",
                "确定删除「" + UserRules.safeName(r) + "」？删除后可以重新导入或被分享的规则恢复。", null,
                UiKit.primary("删除", () -> {
                    for (int i = 0; i < rules.size(); i++) {
                        if (rules.get(i) == r) { rules.remove(i); break; }
                    }
                    persistAndRefresh();
                }),
                UiKit.ghost("取消", null));
    }

    /** 测试：跑自测样本 + 完整链路 dryRun，逐条展示结果 */
    private void showTestResult(final UserRules.Rule r) {
        UserRules.VerifyResult vr = UserRules.verify(r);
        StringBuilder sb = new StringBuilder();
        sb.append("【自测结论】").append(vr.ok ? "全部通过" : "未通过").append("\n\n");
        sb.append(vr.message);
        if (vr.ok) {
            sb.append("\n\n实时匹配预览：");
            for (UserRules.TestCase t : r.testCases) {
                sb.append("\n· 输入：").append(t.sms)
                        .append("\n  → 码：").append(UserRules.matchCode(r, t.sms))
                        .append("（期望 ").append(t.expectCode).append("）");
            }
            if (r.testCases != null && !r.testCases.isEmpty()) {
                String probeSender = (r.senderMode == null || r.senderMode.isEmpty()
                        || UserRules.MODE_ANY.equals(r.senderMode))
                        ? null : r.senderValue;
                List<UserRules.Rule> one = new ArrayList<>();
                one.add(r);
                sb.append("\n\n完整链路试跑（含发送方匹配）：\n")
                        .append(UserRules.dryRun(one, probeSender, r.testCases.get(0).sms));
            }
        }
        UiKit.dialog(this, P, R.drawable.ic_flask, "规则自测", sb.toString(), null,
                UiKit.primary("知道了", null));
    }

    /** 规则编辑器 */
    private void showEditor(final UserRules.Rule existing) {
        final UserRules.Rule r = (existing == null) ? new UserRules.Rule() : existing;
        final boolean isNew = (existing == null);
        if (isNew) {
            r.id = UserRules.nextId(this);
            r.name = "";
            r.codeRegex = "";
            r.senderContains = "";
            r.source = "";
            r.place = "";
            r.testCases = new ArrayList<>();
            r.enabled = false; // 新建规则默认不启用，需自测通过后再手动启用
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), 0, dp(6), 0);

        final EditText etName = field(box, "① 规则名称（必填）",
                r.name, "例：我们小区菜鸟驿站");
        // ---- 发送方匹配（分层设计：全局规则 vs 号码专属）----
        final String[] mode = {r.senderMode == null || r.senderMode.isEmpty()
                ? UserRules.MODE_ANY : r.senderMode};
        // 预创建输入框与提示（ANY 模式隐藏），避免 lambda 前向引用
        final TextView senderHint = new TextView(this);
        senderHint.setText(UserRules.modeHint(mode[0]));
        senderHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        senderHint.setTextColor(Color.parseColor("#2E7D32"));
        senderHint.setPadding(dp(4), 0, dp(4), 0);

        final EditText senderEt = new EditText(this);
        senderEt.setHint("发送方号码或文字");
        senderEt.setText(r.senderValue == null ? "" : r.senderValue);
        senderEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        senderEt.setVisibility(UserRules.MODE_ANY.equals(mode[0]) ? View.GONE : View.VISIBLE);

        final TextView modeBtn = new TextView(this);
        modeBtn.setText("② 发送方范围：" + UserRules.modeLabel(mode[0]));
        modeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        modeBtn.setTextColor(Color.parseColor("#1A1A1A"));
        modeBtn.setPadding(dp(8), dp(10), dp(8), dp(6));
        modeBtn.setBackgroundColor(Color.parseColor("#F0F4FF"));
        final String[] modes = {UserRules.MODE_ANY, UserRules.MODE_EXACT,
                UserRules.MODE_PREFIX, UserRules.MODE_CONTAINS};
        modeBtn.setOnClickListener(v -> {
            int cur = 0;
            for (int mi = 0; mi < modes.length; mi++) if (modes[mi].equals(mode[0])) cur = mi;
            UiKit.chooser(this, P, R.drawable.ic_call, "选择发送方匹配方式",
                    new CharSequence[]{
                            "不限来源（全局规则）",
                            "精确匹配（固定号码）",
                            "前缀匹配（虚拟号，后几位随机）",
                            "包含匹配（号码含文字）"
                    }, cur, w -> {
                        mode[0] = modes[w];
                        modeBtn.setText("② 发送方范围：" + UserRules.modeLabel(mode[0]));
                        senderHint.setText(UserRules.modeHint(mode[0]));
                        senderEt.setVisibility(UserRules.MODE_ANY.equals(mode[0])
                                ? View.GONE : View.VISIBLE);
                    });
        });
        box.addView(modeBtn);
        box.addView(senderHint);
        box.addView(senderEt);

        TextView prioTip = new TextView(this);
        prioTip.setText("号码专属规则会自动优先于全局规则生效（同一条短信命中多条时，"
                + "精确 > 前缀 > 包含 > 全局）。");
        prioTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        prioTip.setTextColor(Color.parseColor("#888888"));
        prioTip.setPadding(dp(4), dp(4), dp(4), 0);
        box.addView(prioTip);

        final EditText etCode = field(box, "③ 取件码正则（必填）",
                r.codeRegex, "例：取件码\\s*([A-Za-z0-9-]+)  ← 小括号里的就是取件码");
        final EditText etCodeGroup = field(box, "④ 取第几个捕获组（必填）",
                String.valueOf(r.codeGroup), "1=第一个小括号；0=整个匹配");
        final EditText etSource = field(box, "⑤ 来源（可选，留空=自动识别）",
                r.source, "固定文本或 $1 引用正则捕获组");
        final EditText etPlace = field(box, "⑥ 地点（可选，留空=自动识别）",
                r.place, "固定文本或 $1 引用");
        final EditText etPlaceRegex = field(box, "⑦ 地点正则（可选，一般不用）",
                r.placeRegex, "地点格式特殊时才用，例：至(.+?取件)");

        // ---- ⑧ 地点取第几个捕获组（Rule.placeGroup）----
        // 此前该字段只能靠「导入别人的规则」才会出现非默认值，编辑页没有入口 → 用户改不了。
        // 只在 ⑦ 非空时显示：地点正则都没填，捕获组无从谈起（UserRules 里也只在
        // placeRegex 非空时才会读 placeGroup）。
        TextView placeGroupLabel = new TextView(this);
        placeGroupLabel.setText("⑧ 地点取第几个捕获组");
        placeGroupLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        placeGroupLabel.setTextColor(Color.parseColor("#1A1A1A"));
        placeGroupLabel.setPadding(0, dp(8), 0, 0);
        box.addView(placeGroupLabel);

        final EditText etPlaceGroup = new EditText(this);
        etPlaceGroup.setText(String.valueOf(Math.max(0, r.placeGroup)));   // 编辑已有规则时回填
        etPlaceGroup.setHint("1=第一个小括号（默认）；0=整个匹配");
        etPlaceGroup.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        etPlaceGroup.setSingleLine(true);
        etPlaceGroup.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(etPlaceGroup);

        final int pgVisOn = (r.placeRegex != null && !r.placeRegex.trim().isEmpty())
                ? View.VISIBLE : View.GONE;
        placeGroupLabel.setVisibility(pgVisOn);
        etPlaceGroup.setVisibility(pgVisOn);
        etPlaceRegex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                boolean on = s != null && s.toString().trim().length() > 0;
                int vis = on ? View.VISIBLE : View.GONE;
                placeGroupLabel.setVisibility(vis);
                etPlaceGroup.setVisibility(vis);
            }
        });

        // 测试样本区（v3.0.1 简化为单条必填）
        // 之前支持无限添加却没有删除入口，且自测失败后整页消失导致要重填 —— 一并简化。
        final LinearLayout tcBox = new LinearLayout(this);
        tcBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(tcBox);

        TextView tcTitle = new TextView(this);
        tcTitle.setText("测试样本（必填，必须自测通过才能保存）");
        tcTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tcTitle.setTextColor(Color.parseColor("#1A1A1A"));
        tcTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tcTitle.setPadding(0, dp(12), 0, dp(4));
        tcBox.addView(tcTitle);

        final EditText tcSms = new EditText(this);
        tcSms.setHint("粘贴一条真实短信原文");
        tcSms.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tcSms.setMinLines(2);
        tcSms.setMaxLines(5);
        tcSms.setGravity(Gravity.TOP | Gravity.START);
        tcSms.setPadding(dp(10), dp(8), dp(10), dp(8));
        tcSms.setBackground(roundRect("#FFFFFF"));
        tcBox.addView(tcSms);

        final EditText tcExpect = new EditText(this);
        tcExpect.setHint("这条短信应该提取到的取件码（多个码用 ; 分隔）");
        tcExpect.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tcExpect.setSingleLine(true);
        tcExpect.setPadding(dp(10), dp(8), dp(10), dp(8));
        tcExpect.setBackground(roundRect("#FFFFFF"));
        LinearLayout.LayoutParams tcExpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tcExpLp.setMargins(0, dp(6), 0, 0);
        tcExpect.setLayoutParams(tcExpLp);
        tcBox.addView(tcExpect);

        // 兼容已有规则：把第一条样本回填（多余的历史样本不再展示，但保存时仍保留）
        if (r.testCases != null && !r.testCases.isEmpty()) {
            tcSms.setText(r.testCases.get(0).sms);
            tcExpect.setText(r.testCases.get(0).expectCode);
        }
        final UserRules.TestCase firstKept = (r.testCases != null && !r.testCases.isEmpty())
                ? r.testCases.get(0) : null;

        // 关键：整个编辑区必须放在 ScrollView 里。
        // 否则测试样本加到第 3 条以后，下面的内容会被挤出对话框可视区（显示不全/点不到）。
        ScrollView boxScroll = new ScrollView(this);
        boxScroll.setVerticalScrollBarEnabled(false);
        boxScroll.setHorizontalScrollBarEnabled(false);
        boxScroll.setPadding(dp(4), 0, dp(4), 0);
        boxScroll.addView(box);

        // v3.0.1：不用 setPositiveButton（它点击后会自动 dismiss，导致自测失败时
        // 整页消失、用户辛苦填的内容全丢）。改为 show() 后接管按钮，
        // 校验不通过时保持编辑页打开，用户可原地修改后再次自测。
        final android.app.Dialog[] holder = new android.app.Dialog[1];
        holder[0] = UiKit.dialog(this, P,
                isNew ? R.drawable.ic_plus : R.drawable.ic_template,
                isNew ? "新建规则" : "编辑规则", null, box, 58,
                UiKit.keepOpen("自测并保存", () -> {
                r.name = etName.getText().toString().trim();
                r.senderMode = mode[0];
                r.senderValue = UserRules.MODE_ANY.equals(mode[0])
                        ? "" : senderEt.getText().toString().trim();
                r.senderContains = "";   // 新结构，不再使用旧字段
                r.codeRegex = etCode.getText().toString().trim();
                r.codeGroup = parseInt(etCodeGroup.getText().toString(), 1);
                r.source = etSource.getText().toString().trim();
                r.place = etPlace.getText().toString().trim();
                r.placeRegex = etPlaceRegex.getText().toString().trim();
                // ⑧ 地点捕获组：空 = 1（默认）；非法值必须明确提示，不能静默吞掉
                // （静默回落 1 会让用户以为自己改了 2，实际存的是 1，比报错更难排查）
                String pgRaw = etPlaceGroup.getText().toString().trim();
                if (pgRaw.isEmpty()) {
                    r.placeGroup = 1;
                } else {
                    int pg;
                    try {
                        pg = Integer.parseInt(pgRaw);
                    } catch (Throwable t) {
                        Toast.makeText(this, "⑧「地点取第几个捕获组」必须是数字（0 或 1…）",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (pg < 0) {
                        Toast.makeText(this, "⑧「地点取第几个捕获组」不能是负数",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    r.placeGroup = pg;
                }
                // v3.0.1：单条测试样本（必填）
                String sSms = tcSms.getText().toString().trim();
                String sExp = tcExpect.getText().toString().trim();
                r.testCases = new ArrayList<>();
                if (!sSms.isEmpty()) {
                    r.testCases.add(new UserRules.TestCase(sSms, sExp));
                } else if (firstKept != null) {
                    r.testCases.add(firstKept);   // 未改动时保留历史样本
                }

                String quick = UserRules.quickValidate(r);
                if (quick != null) {
                    UiKit.dialog(this, P, R.drawable.ic_alert, "规则不合规",
                            quick + "\n\n请按提示原地修改后再点「自测并保存」。", null,
                            UiKit.primary("继续修改", null));
                    return;
                }
                UserRules.VerifyResult vr = UserRules.verify(r);
                if (!vr.ok) {
                    // 关键：编辑页保持打开，用户可继续修改正则/期望值
                    UiKit.dialog(this, P, R.drawable.ic_alert, "自测未通过",
                            vr.message
                                    + "\n\n请在上方按提示修正正则或期望值，然后再次点「自测并保存」。\n"
                                    + "没思路可点「参考案例」。", null,
                            UiKit.primary("继续修改", null),
                            UiKit.ghost("参考案例", this::showExamples));
                    return;
                }
                r.enabled = true;
                if (isNew) rules.add(r);
                String err = UserRules.saveAll(this, rules);
                if (err != null) {
                    Toast.makeText(this, "保存失败：" + err, Toast.LENGTH_LONG).show();
                    return;
                }
                // 保存成功即自动开启总开关，避免"已保存但功能未启用"的状态不一致
                if (!UserRules.isEnabled(this)) {
                    UserRules.setEnabled(this, true);
                }
                PickupExtractor.loadUserRules(this, UserRules.loadAll(this));
                holder[0].dismiss();
                Toast.makeText(this, "规则已保存并启用 ✓\n" + vr.message, Toast.LENGTH_LONG).show();
                recreate();
                }),
                UiKit.ghost("参考案例", this::showExamples),
                UiKit.ghost("取消", null));
    }

    private EditText field(LinearLayout box, String label, String value, String hint) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setTextColor(Color.parseColor("#1A1A1A"));
        l.setPadding(0, dp(8), 0, 0);
        box.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        e.setSingleLine(false);
        box.addView(e);
        return e;
    }

    /** 参考案例（用官方真实语料 + 分层号码场景，降低门槛） */
    private void showExamples() {
        String msg = "【第 1 层：全局规则】不限发送方，官方规则没覆盖到的都用它兜底\n"
                + "短信：【XX小区自提点】物品编号 XZ-77612 已放 A 栋 3 楼取件口\n"
                + "发送方范围：不限来源\n"
                + "取件码正则：物品编号\\s*([A-Z]{1,3}-[0-9]{3,8})\n"
                + "捕获组：1\n\n"
                + "【第 2 层：号码专属规则】推荐，精准度最高\n"
                + "· 驿站固定号码 → 精确匹配：1069888877\n"
                + "· 虚拟号后几位随机 → 前缀匹配：1069（可同时覆盖该服务商所有号）\n"
                + "· 号码里带文字 → 包含匹配：菜鸟驿站\n"
                + "· 固定快递员个人号 → 精确匹配：13800138000\n"
                + "（号码专属规则会自动优先于全局规则）\n\n"
                + "【正则常用写法】\n"
                + "· 关键词型：取货码([A-Za-z0-9-]+)　　 → 捕获组 1\n"
                + "· 凭码型：凭([A-Za-z0-9-]+)到　　　　 → 捕获组 1\n"
                + "· 带引号：凭[\"“]([0-9]+)[\"”]　　　 → 捕获组 1\n"
                + "· 英文标识：code[:：]\\s*([A-Z0-9-]+)　→ 捕获组 1\n"
                + "（捕获组 = 第几个小括号 ()；0 = 整个匹配）\n\n"
                + "【容易出问题的写法（保存时会被拦截或自测失败）】\n"
                + "· (.*)+ 或 .*.*　嵌套量词 → 指数级回溯，会卡住短信处理（已被自动拦截）\n"
                + "· 只写 [0-9]+ 而短信里有多个数字 → 会抓到单号/金额/时间\n"
                + "  ✓ 正确做法：用上下文词把数字框住，如 取件码([0-9-]+)\n\n"
                + "【调试技巧】\n"
                + "· 「测试样本」必填：粘贴一条真实短信 + 写下期望的取件码\n"
                + "  （短信里多个码时，用 ; 分隔，如 16-4-9626;15-3-2194）\n"
                + "· 自测不通过时编辑页会保留，直接改正则再点一次「自测并保存」\n"
                + "· 「捕获组」= 第几个小括号 () 包住的内容\n"
                + "  例：取货码([A-Za-z0-9-]+) → 取件码就是第 1 组";
        UiKit.dialog(this, P, R.drawable.ic_book, "参考案例", msg, null, 62,
                UiKit.primary("知道了", null));
    }

    private void doExport() {
        if (rules.isEmpty()) {
            Toast.makeText(this, "还没有规则可导出", Toast.LENGTH_SHORT).show();
            return;
        }
        final String text = UserRules.exportForShare(rules);
        UiKit.dialog(this, P, R.drawable.ic_upload, "导出规则",
                "已生成 " + rules.size() + " 条规则的分享文本。\n\n"
                        + "· 想分享给朋友：复制后发 QQ 群，对方在「导入」粘贴即可\n"
                        + "· 想贡献给官方：复制后发到 GitHub Issue（标签 user-rule）\n"
                        + "· 发送前请自行确认短信样本里没有姓名/手机号/住址等隐私", null,
                UiKit.primary("复制", () -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("user_rules", text));
                    Toast.makeText(this, "已复制，可直接粘贴分享", Toast.LENGTH_LONG).show();
                }),
                UiKit.ghost("关闭", null));
    }

    private void doImport() {
        final EditText et = new EditText(this);
        et.setHint("粘贴他人分享的规则文本");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        et.setTextColor(UiKit.INK2);
        et.setMinLines(6);
        et.setBackground(UiKit.bg(P.cardSoft, this, 12, P.line, 1f));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        UiKit.dialog(this, P, R.drawable.ic_download, "导入规则", null, et, 42,
                UiKit.keepOpen("导入并自测", () -> {
                    List<UserRules.Rule> imported = UserRules.importFromShare(et.getText().toString());
                    if (imported.isEmpty()) {
                        Toast.makeText(this, "没有解析到规则，请检查格式", Toast.LENGTH_LONG).show();
                        return;
                    }
                    StringBuilder bad = new StringBuilder();
                    List<UserRules.Rule> ok = new ArrayList<>();
                    for (UserRules.Rule r : imported) {
                        UserRules.VerifyResult vr = UserRules.verify(r);
                        if (vr.ok) ok.add(r);
                        else bad.append("· 「").append(UserRules.safeName(r)).append("」：")
                                .append(vr.message.split("\n")[0]).append("\n");
                    }
                    if (!ok.isEmpty()) {
                        for (UserRules.Rule r : ok) rules.add(r);
                        String err = UserRules.saveAll(this, rules);
                        // 导入成功同样自动开启总开关，保持与「新建规则」一致的行为
                        if (err == null && !UserRules.isEnabled(this)) {
                            UserRules.setEnabled(this, true);
                        }
                        PickupExtractor.loadUserRules(this, UserRules.loadAll(this));
                        recreate();
                        Toast.makeText(this, "成功导入 " + ok.size() + " 条规则并已启用"
                                + (err == null ? " ✓" : "（部分保存失败：" + err + "）"), Toast.LENGTH_LONG).show();
                    }
                    if (bad.length() > 0) {
                        UiKit.dialog(this, P, R.drawable.ic_alert, "部分规则未通过自测",
                                "以下规则被拒绝导入：\n\n" + bad
                                        + "\n原因通常是分享文本不完整或对方规则本身有问题。", null,
                                UiKit.primary("知道了", null));
                    }
                }),
                UiKit.ghost("取消", null));
    }

    private TextView action(String label, String bg, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(Color.parseColor(bg));
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    /** 圆角背景（输入框 / 卡片用） */
    private android.graphics.drawable.Drawable roundRect(String color) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setColor(Color.parseColor(color));
        gd.setCornerRadius(dp(10));
        return gd;
    }

    /** v4.0.0：接受 int 颜色（用于跟随配色的强调色等） */
    private android.graphics.drawable.Drawable roundRect(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(dp(14));
        return gd;
    }

    /** v4.0.0：接受 int 颜色的动作按钮（跟随配色） */
    private TextView action(String label, int bg, View.OnClickListener l) {
        TextView b = action(label, "#000000", l);
        b.setBackground(roundRect(bg));
        return b;
    }

    private int dp(int v) {


        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }
}
