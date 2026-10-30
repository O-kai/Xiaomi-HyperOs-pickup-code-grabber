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
    private UiKit.Palette p;

    /** 导出格式：true = JSON，false = CSV */
    private boolean exportAsJson = true;

    /** 数据变化后回调（刷新列表 / 首页待取件） */
    private Runnable onChanged;

    public RecordManager(Activity act) {
        this.act = act;
        this.p = UiKit.palette(act);
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
        final int days = PickupRepository.retentionDays(act);
        final int total = PickupRepository.get(act).countAllSync();

        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);

        addRow(wrap, R.drawable.ic_notebook, "记录保留期",
                "当前：" + Retention.label(days) + " · 只清理已取件的记录",
                v -> showRetentionPicker());
        wrap.addView(UiKit.divider(act, p));
        addRow(wrap, R.drawable.ic_download, "导出记录",
                "导出 JSON 或 CSV，共 " + total + " 条",
                v -> showExportPicker());
        wrap.addView(UiKit.divider(act, p));
        addRow(wrap, R.drawable.ic_upload, "导入记录",
                "从文件导入 JSON 或 CSV（已有同码记录会跳过）",
                v -> startImport());
        wrap.addView(UiKit.divider(act, p));
        addRow(wrap, R.drawable.ic_refresh, "立即清理过期记录",
                days <= 0 ? "当前为永久保留，无可清理内容" : "删除超过 " + days + " 天的已取件记录",
                v -> purgeNow());
        wrap.addView(UiKit.divider(act, p));
        addRow(wrap, R.drawable.ic_ban, "清空全部记录",
                "删除本机保存的全部 " + total + " 条记录",
                v -> confirmClearAll());

        UiKit.dialog(act, p, R.drawable.ic_notebook, "管理本地记录",
                "本地库是取件码的事实源。导出的文件只含码、来源、地点与时间，不含短信正文。",
                wrap, 0);
    }

    private void addRow(LinearLayout parent, int iconRes, String title, String sub,
                        View.OnClickListener cb) {
        LinearLayout r = UiKit.row(act, p, iconRes, title, sub, UiKit.chevron(act, p));
        r.setOnClickListener(cb);
        parent.addView(r);
    }

    // ================================================================
    //  保留期
    // ================================================================

    public void showRetentionPicker() {
        refreshPalette();
        int[] opts = Retention.options();
        CharSequence[] items = new CharSequence[opts.length];
        int checked = 0;
        int cur = PickupRepository.retentionDays(act);
        for (int i = 0; i < opts.length; i++) {
            items[i] = Retention.label(opts[i]);
            if (opts[i] == cur) checked = i;
        }
        UiKit.chooser(act, p, R.drawable.ic_notebook, "记录保留期", items, checked, w -> {
            PickupRepository.setRetentionDays(act, opts[w]);
            Toast.makeText(act, "保留期已设为 " + Retention.label(opts[w]), Toast.LENGTH_SHORT).show();
            notifyChanged();
        });
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
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{MIME_JSON, MIME_CSV, "text/plain", "application/octet-stream"});
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
                StringBuilder sb = new StringBuilder("导入完成：新�� " + res.inserted + " 条");
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
    //  清理
    // ================================================================

    public void purgeNow() {
        int days = PickupRepository.retentionDays(act);
        if (days <= 0) {
            Toast.makeText(act, "当前为永久保留，没有需要清理的记录", Toast.LENGTH_SHORT).show();
            return;
        }
        PickupRepository.get(act).purgeExpiredAsync(days, n -> {
            Toast.makeText(act, n > 0
                    ? "已清理 " + n + " 条超过 " + days + " 天的记录"
                    : "没有需要清理的记录", Toast.LENGTH_LONG).show();
            notifyChanged();
        });
    }

    public void confirmClearAll() {
        int total = PickupRepository.get(act).countAllSync();
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
