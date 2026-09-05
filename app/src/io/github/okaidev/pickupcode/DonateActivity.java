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

        ImageView qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setAdjustViewBounds(true);
        try {
            InputStream is = getAssets().open("donate_qr.png");
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            if (bmp != null) {
                qr.setImageBitmap(bmp);
            } else {
                root.addView(errorLabel("图片解码失败（安装包可能不完整）"));
            }
        } catch (Throwable t) {
            root.addView(errorLabel("图片加载失败：" + t.getMessage()));
        }
        root.addView(qr);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // 页面下方的大按钮：保存到本地
        TextView save = new TextView(this);
        save.setText("💾 保存图片到本地（Download 目录）");
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

    private TextView errorLabel(String msg) {
        TextView err = new TextView(this);
        err.setText("⚠️ " + msg);
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        err.setTextColor(Color.parseColor("#CC3300"));
        err.setPadding(0, dp(8), 0, dp(8));
        return err;
    }

    /** 保存合成图到 Download（API 29+ 走 MediaStore，无需存储权限） */
    private void saveQrToDownload() {
        try {
            InputStream is = getAssets().open("donate_qr.png");
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            if (bmp == null) {
                toast("图片解码失败");
                return;
            }
            String name = "取件码助手_打赏码.png";
            String where;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    toast("保存失败：系统拒绝了写入请求");
                    return;
                }
                OutputStream os = getContentResolver().openOutputStream(uri);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                where = "Download/" + name;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                where = f.getAbsolutePath();
            }
            toast("已保存：" + where + "\n（打赏随意，心意最重要 ☕）");
        } catch (Throwable t) {
            toast("保存失败：" + t.getMessage());
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
