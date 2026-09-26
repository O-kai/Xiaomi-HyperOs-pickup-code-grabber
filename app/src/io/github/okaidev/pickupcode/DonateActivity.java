package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 打赏页（v2.5.0 新增）：
 *  - 展示 assets 内置的收款码合成图（支付宝在上、微信在下，构建期拼接为一张 PNG）
 *  - 页面下方大按钮「保存图片到本地」：API 29+ 写入 Download/（MediaStore，无需存储权限），
 *    旧版本走公共目录直接写（失败时给出说明）
 *  - 打赏纯自愿，不影响任何功能
 */
public class DonateActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(8));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("💰 打赏作者");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("如果这个模块帮你省去了到处找取件码的麻烦，可以考虑请作者喝杯奶茶～\n"
                + "支付宝 / 微信扫码皆可，金额随意，心意最重要。\n"
                + "打赏纯属自愿，不影响任何功能。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setTextColor(Color.parseColor("#666666"));
        desc.setLineSpacing(dp(3), 1.0f);
        desc.setPadding(0, dp(10), 0, dp(12));
        root.addView(desc);

        // 二维码区：左右并排（微信 | 支付宝），一眼看清两个收款方式
        // 相比原先上下堆叠的大图，并排后无需滚动即可扫到
        LinearLayout qrRow = new LinearLayout(this);
        qrRow.setOrientation(LinearLayout.HORIZONTAL);
        qrRow.setPadding(0, dp(4), 0, 0);
        qrRow.addView(qrCard("微信", "donate_wechat.png"), qCardLp());
        qrRow.addView(qrCard("支付宝", "donate_alipay.jpg"), qCardLp());
        root.addView(qrRow);

        TextView hint = new TextView(this);
        hint.setText("长按二维码可保存到相册，或点下方按钮保存到 Download 目录");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(Color.parseColor("#999999"));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // 页面下方的大按钮：保存到本地
        TextView save = new TextView(this);
        save.setText("💾 保存两个收款码到本地（Download 目录）");
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        save.setTextColor(Color.WHITE);
        save.setBackgroundColor(Color.parseColor("#0FA968"));
        save.setPadding(dp(16), dp(14), dp(16), dp(14));
        save.setGravity(Gravity.CENTER);
        save.setOnClickListener(v -> saveQrToDownload());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.setMargins(dp(16), dp(8), dp(16), dp(16));
        save.setLayoutParams(saveLp);
        page.addView(save);

        setContentView(page);
    }

    /** 单个收款码卡片：标签 + 等比二维码（左右各占一半宽度） */
    private LinearLayout qrCard(String label, String asset) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(6), dp(12), dp(6), dp(12));
        card.setBackground(roundRect("#F5F6FA"));

        TextView tag = new TextView(this);
        tag.setText(label);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tag.setTextColor(Color.parseColor("#1A1A1A"));
        tag.setTypeface(null, android.graphics.Typeface.BOLD);
        tag.setPadding(0, 0, 0, dp(8));
        card.addView(tag);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 按卡片宽度等比缩放，避免大图撑破布局 / 小图糊掉
        int w = Math.max(dp(120), (getResources().getDisplayMetrics().widthPixels - dp(96)) / 2);
        img.setLayoutParams(new LinearLayout.LayoutParams(w, w));
        try {
            InputStream is = getAssets().open(asset);
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            if (bmp != null) {
                img.setImageBitmap(bmp);
            } else {
                img.setBackgroundColor(Color.parseColor("#E0E0E0"));
            }
        } catch (Throwable t) {
            img.setBackgroundColor(Color.parseColor("#E0E0E0"));
        }
        card.addView(img);
        return card;
    }

    /** 两个卡片等宽、各留间距 */
    private LinearLayout.LayoutParams qCardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    /** 圆角背景（用于卡片容器） */
    private android.graphics.drawable.Drawable roundRect(String color) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setColor(Color.parseColor(color));
        gd.setCornerRadius(dp(14));
        return gd;
    }

    private TextView errorLabel(String msg) {
        TextView err = new TextView(this);
        err.setText("⚠️ " + msg);
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        err.setTextColor(Color.parseColor("#CC3300"));
        err.setPadding(0, dp(8), 0, dp(8));
        return err;
    }

    /** 保存两张收款码到 Download（API 29+ 走 MediaStore，无需存储权限） */
    private void saveQrToDownload() {
        int ok = saveOne("donate_wechat.png", "取件码助手_微信收款码.png");
        int ok2 = saveOne("donate_alipay.jpg", "取件码助手_支付宝收款码.jpg");
        if (ok > 0 && ok2 > 0) {
            toast("已保存到 Download 目录\n（打赏随意，心意最重要 ☕）");
        } else if (ok + ok2 > 0) {
            toast("已保存 1 张到 Download 目录");
        } else {
            toast("保存失败，请检查存储权限");
        }
    }

    /** 保存单张资源图到 Download，返回 1 成功 / 0 失败 */
    private int saveOne(String asset, String name) {
        try {
            InputStream is = getAssets().open(asset);
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            if (bmp == null) return 0;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return 0;
                OutputStream os = getContentResolver().openOutputStream(uri);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
            }
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
