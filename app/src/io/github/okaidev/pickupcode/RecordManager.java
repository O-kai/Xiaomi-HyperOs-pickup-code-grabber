package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 本地取件记录的「管理」能力（4.0 收口）。
 *
 * <p>为什么要单独抽成一个类：这套功能有两个入口——进阶模式的「设置 → 本地记录」
 * 和简洁模式能进的「取件记录」页。两边都指向同一份实现，
 * 否则改一处漏一处，就会出现「设置里说 90 天、列表页还是老行为」这种漂移。
 *
 * <p>覆盖的能力：保留期设置、导出 JSON/CSV、导入、清空过期记录、清空全部。
 * 导出/导入走系统文件选择器（SAF），不需要任何存储权限。
 */
public class RecordManager {

    /** 导出请求码 */
    public static final int REQ_EXPORT = 4101;
    /** 导入请求码 */
    public static final int REQ_IMPORT = 4102;

    private static final String MIME_JSON = "application/json";
    private static final String MIME_CSV = "text/csv";

    private final Activity act;
    /** 当前配色缓存；用到时才取（构造期取会 NPE，见构造器注释） */
    private UiKit.Palette p;

    /**
     * 导出格式：true = JSON，false = CSV
     */
    private boolean exportAsJson = true;

    /** 数据变化后回调（刷新列表 / 首页待取件） */
    private Runnable onChanged;

    public RecordManager(Activity act) {
        this.act = act;
        // 注意：这里<b>不能</b>去取 palette 或读 SharedPreferences——
        // 本对象是在 Activity 的字段初始化期创建的，那时
        // Activity.getApplicationContext() 还是 null，一碰就 NPE，
        // 表现为「点开 App 直接闪退」。配色等用到时再取。
    }

    /** 当前配色（按需刷新，主题切换后自动跟上） */
    private UiKit.Palette palette() {
        UiKit.Palette cur = UiKit.palette(act);
        p = cur;
        return cur;
    }

    public void setOnChanged(Runnable r) {
        this.onChanged = r;
    }

    private void refreshPalette() {
        p = UiKit.palette(act);
    }

    // ================================================================
    //  管理面板
    // ================================================================

    /** 「管理记录」面板：保留期 / 导出 / 导入 / 清理 */
    public void showManageDialog() {
        refreshPalette();

        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);

        // v4.0.1：保留期这一行需要能在改完之后就地刷新副标题
        // （用户反馈「切换保留期时，外面的『当前 XX 天』不会直接修改」）。
        // 用数组包一层：lambda 里要引用它自己所在的行，
        // 直接用局部变量会编译不过（变量尚未初始化）。
        final LinearLayout[] retentionRow = new LinearLayout[1];
        retentionRow[0] = addRow(wrap, R.drawable.ic_notebook, "记录保留期",
                retentionSub(),
                v -> showRetentionPicker(retentionRow[0]));
        wrap.addView(UiKit.divider(act, p));
        final LinearLayout exportRow = addRow(wrap, R.drawable.ic_download, "导出记录",
                "导出 JSON 或 CSV", v -> showExportPicker());
        wrap.addView(UiKit.divider(act, p));
        addRow(wrap, R.drawable.ic_upload, "导入记录",
                "从文件导入 JSON 或 CSV（已有同码记录会跳过）",
                v -> startImport());
        wrap.addView(UiKit.divider(act, p));
        // v4.0.3：从设置页搬过来。
        // 此前「导入历史取件记录」只挂在设置页里，而管理面板才是记录相关功能的
        // 唯一入口——同一个功能的两个入口会让用户不知道该去哪找。
        // 现在统一归口到这里，设置页不再重复出现。
        addRow(wrap, R.drawable.ic_book, "导入历史取件记录",
                "从小米笔记把老的取件待办读回本地库",
                v -> importHistoryNow());
        wrap.addView(UiKit.divider(act, p));
        // v4.0.2：「立即清理过期记录」→「立即清理所有已取件记录」。
        // 用户理由：过期清理本来就该自动发生（保留期到了就删），
        // 手动按钮再带「过期」这个条件就是多余的一层。
        final LinearLayout purgeRow = addRow(wrap, R.drawable.ic_refresh, "立即清理所有已取件记录",
                purgeSub(), v -> purgeNow());
        wrap.addView(UiKit.divider(act, p));
        final LinearLayout clearRow = addRow(wrap, R.drawable.ic_ban, "清空全部记录",
                "删除本机保存的全部记录", v -> confirmClearAll());

        UiKit.dialog(act, p, R.drawable.ic_notebook, "管理本地记录",
                "本地库是取件码的事实源。导出的文件只含码、来源、地点与时间，不含短信正文。\n\n"
                        + "超过保留期的已取件记录会自动清理，不需要手动操作。",
                wrap, 0);

        // 条数必须异步取：Room 不允许主线程查询，同步取会被拒绝然后吞掉，
        // 结果就是「共 0 条」——这里曾经真的显示过 0。
        PickupRepository.get(act).countAllAsync(n -> {
            if (act.isFinishing() || act.isDestroyed()) return;
            UiKit.setRowSub(exportRow, "导出 JSON 或 CSV，共 " + n + " 条");
            UiKit.setRowSub(clearRow, "删除本机保存的全部 " + n + " 条记录");
        });
    }

    /** 保留期行的副标题（唯一来源：设置页与管理弹窗共用） */
    public String retentionSubText() {
        return retentionSub();
    }

    /** 保留期行的副标题 */
    private String retentionSub() {
        // v4.0.2：把语义写清楚。用户原话：
        // 「设置页面：写清楚记录时间。比如如果设置只记录 90 天，
        //   那么 90 天之后到了立马就应该把它删掉。」
        // 所以这里要说三件事：保留多久、到期自动清、待取件不删。
        int d = days();
        if (d <= 0) {
            return "永久保留 · 已取件的记录也不会自动删除";
        }
        return "已取件记录保留 " + d + " 天 · 到期自动清理 · 待取件永不删除";
    }

    /** 「立即清理所有已取件记录」行的副标题 */
    private String purgeSub() {
        return "一次性删除全部已取件记录（待取件保留）";
    }

    /** 当前保留期天数（供副标题用） */
    private int days() {
        return PickupRepository.retentionDays(act);
    }

    private LinearLayout addRow(LinearLayout parent, int iconRes, String title, String sub,
                                View.OnClickListener cb) {
        UiKit.Palette pal = palette();
        LinearLayout r = UiKit.row(act, pal, iconRes, title, sub, UiKit.chevron(act, pal));
        r.setOnClickListener(cb);
        parent.addView(r);
        return r;
    }

    // ================================================================
    //  保留期
    // ================================================================

    /**
     * 保留期选择器。
     *
     * @param row 需要就地刷新的那一行（管理面板里的「记录保留期」行）。
     *             传 null 也能用，只是不会刷新外层文案。
     */
    public void showRetentionPicker(LinearLayout row) {
        refreshPalette();
        int[] opts = Retention.options();
        CharSequence[] items = new CharSequence[opts.length];
        int checked = 0;
        int cur = PickupRepository.retentionDays(act);
        for (int i = 0; i < opts.length; i++) {
            // v4.0.2：选项带上完整说明——用户要能一眼看出这个保留期管哪类记录
            items[i] = Retention.labelWithHint(opts[i]);
            if (opts[i] == cur) checked = i;
        }
        UiKit.chooser(act, p, R.drawable.ic_notebook, "记录保留期", items, checked, w -> {
            PickupRepository.setRetentionDays(act, opts[w]);
            Toast.makeText(act, "保留期已设为 " + Retention.label(opts[w]), Toast.LENGTH_SHORT).show();
            // v4.0.1：就地刷新外层文案。
            // 用户反馈「在设置里切换保留期时，外面的『当前 XX 天』并不会直接修改」——
            // 弹窗是一次性构建的，改完值不会自己变，必须显式更新。
            if (row != null) {
                UiKit.setRowSub(row, retentionSub());
            }
            notifyChanged();
        });
    }

    /** 兼容旧调用点：不刷新外层文案。 */
    public void showRetentionPicker() {
        showRetentionPicker(null);
    }

    // ================================================================
    //  导出
    // ================================================================

    public void showExportPicker() {
        refreshPalette();
        CharSequence[] items = new CharSequence[]{
                "JSON（推荐，可再导入）",
                "CSV（Excel 可直接打开）"
        };
        UiKit.chooser(act, p, R.drawable.ic_download, "导出为哪种格式", items,
                exportAsJson ? 0 : 1, w -> {
                    exportAsJson = (w == 0);
                    startExport();
                });
    }

    private void startExport() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(exportAsJson ? MIME_JSON : MIME_CSV);
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA)
                    .format(new java.util.Date());
            i.putExtra(Intent.EXTRA_TITLE, "取件记录-" + stamp + (exportAsJson ? ".json" : ".csv"));
            act.startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            Toast.makeText(act, "无法打开文件选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeExport(Uri uri) {
        PickupRepository.get(act).exportRowsAsync(rows -> {
            try {
                String text = exportAsJson
                        ? PickupExport.toJson(rows, System.currentTimeMillis())
                        : PickupExport.toCsv(rows);
                OutputStream os = act.getContentResolver().openOutputStream(uri);
                if (os == null) throw new IllegalStateException("无法写入所选文件");
                try {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } finally {
                    try { os.close(); } catch (Throwable ignored) { }
                }
                Toast.makeText(act, "已导出 " + rows.size() + " 条记录", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(act, "导出失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ================================================================
    //  导入
    // ================================================================

    public void startImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // 只用 */*：再叠加 EXTRA_MIME_TYPES 会让部分系统改用第三方文件管理器，
            // 与导出时的系统选择器不是同一个界面，用户会以为坏了。
            i.setType("*/*");
            act.startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            Toast.makeText(act, "无法打开文件选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void readImport(Uri uri) {
        try {
            String text = readAll(act.getContentResolver().openInputStream(uri));
            PickupExport.ParseResult parsed = PickupExport.parseAuto(text);
            if (!parsed.ok()) {
                Toast.makeText(act, parsed.error, Toast.LENGTH_LONG).show();
                return;
            }
            if (parsed.rows.isEmpty()) {
                Toast.makeText(act, "文件里没有可导入的记录", Toast.LENGTH_LONG).show();
                return;
            }
            PickupRepository.get(act).importRowsAsync(parsed.rows, res -> {
                StringBuilder sb = new StringBuilder("导入完成：新增 " + res.inserted + " 条");
                if (res.duplicated > 0) sb.append("，跳过重复 ").append(res.duplicated).append(" 条");
                if (parsed.skipped > 0) sb.append("，跳过坏行 ").append(parsed.skipped).append(" 条");
                Toast.makeText(act, sb.toString(), Toast.LENGTH_LONG).show();
                notifyChanged();
            });
        } catch (Throwable t) {
            Toast.makeText(act, "导入失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ================================================================
    //  导入历史（从小米笔记）
    // ================================================================

    /**
     * 从小米笔记把老的取件待办读回本地库。
     *
     * <p>v4.0.3：从设置页搬进管理面板。此前它只挂在设置页里，
     * 而记录相关功能的唯一入口应该是「取件记录 → 管理」——
     * 同一件事两个入口，用户会不知道该去哪找。
     */
    public void importHistoryNow() {
        Toast.makeText(act, "正在从小米笔记导入历史…（约 5-15 秒）", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            NotesImporter.Result r = NotesImporter.importHistory(act, 300);
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (r.message != null && !r.message.isEmpty()) {
                    Toast.makeText(act, r.message, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(act,
                            "扫描 " + r.scanned + " 条待办，导入 " + r.imported
                                    + " 条取件记录\n（跳过 " + r.skipped + " 条非取件待办）",
                            Toast.LENGTH_LONG).show();
                }
                notifyChanged();
            });
        }).start();
    }

    // ================================================================
    //  清理
    // ================================================================

    /**
     * 「立即清理所有已取件记录」。
     *
     * <p>v4.0.2：从「清理超过保留期的记录」改成「清理全部已取件记录」。
     * 用户理由：过期清理本来就自动发生（保留期到了就删），
     * 手动按钮再带「过期」条件就是多余的一层。
     *
     * <p>待取件永远保留——那是要去取的东西。
     */
    public void purgeNow() {
        PickupRepository.get(act).deleteAllPickedAsync(n -> {
            Toast.makeText(act, n > 0
                    ? "已清理 " + n + " 条已取件记录"
                    : "没有已取件记录需要清理", Toast.LENGTH_LONG).show();
            notifyChanged();
        });
    }

    public void confirmClearAll() {
        refreshPalette();
        // 条数异步取（Room 不允许主线程查询，同步取会静默返回 0）
        PickupRepository.get(act).countAllAsync(total -> {
            if (act.isFinishing() || act.isDestroyed()) return;
            UiKit.dialog(act, p, R.drawable.ic_ban, "清空全部记录",
                    "将删除本机保存的全部 " + total + " 条记录，包括还没取的件。\n\n"
                            + "如果只是想清理旧记录，请改用「立即清理过期记录」——它只删已取件的。",
                    null, 0,
                    UiKit.primary("确认清空", () ->
                            PickupRepository.get(act).clearAllAsync(n -> {
                                Toast.makeText(act, "已清空 " + n + " 条记录", Toast.LENGTH_SHORT).show();
                                notifyChanged();
                            })),
                    UiKit.ghost("取消", null));
        });
    }

    // ================================================================
    //  生命周期回调
    // ================================================================

    /** 放在 Activity.onActivityResult 里；返回 true 表示已处理 */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) writeExport(uri);
        else readImport(uri);
        return true;
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) throw new IllegalStateException("无法读取所选文件");
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
