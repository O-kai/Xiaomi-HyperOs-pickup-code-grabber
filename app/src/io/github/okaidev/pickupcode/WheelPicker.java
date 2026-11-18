package io.github.okaidev.pickupcode;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;

/**
 * 竖向滚轮选择器（v4.2.1）。
 *
 * <p><b>为什么不用 {@code TimePickerDialog}</b>：那是 Android 4.x 时代的转盘，
 * 和这个 App 的圆角卡片 + 胶囊按钮完全两个年代。这里自己画一个：
 * 中间一条高亮带，选中项最大最实，上下按距离渐隐渐缩，松手自动吸附。
 *
 * <p>刻意做小：只有「等距竖向列表 + 吸附 + 点击跳到某项」，不做惯性滑动之外的
 * 花样 —— 手势状态越多越难调，滚动选择器稳定比炫技重要。
 */
class WheelPicker extends View {

    interface OnPick {
        void onPick(int value);
    }

    private final String[] items;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bandRect = new RectF();
    private final float itemH;
    private final float baseTextSize;

    private int colorOn = 0xFF1F3450;
    private int colorOff = 0xFF8E9AA8;

    /** 当前滚动位置，单位「项」，0 = 第 0 项正好在中间 */
    private float offset;
    private int selected;
    private float downY;
    private float downOffset;
    private ValueAnimator anim;
    private OnPick onPick;

    WheelPicker(Context ctx, int from, int to) {
        super(ctx);
        int n = Math.max(1, to - from + 1);
        items = new String[n];
        for (int i = 0; i < n; i++) {
            items[i] = String.format(Locale.CHINA, "%02d", from + i);
        }
        itemH = UiKit.dp(ctx, 40);
        baseTextSize = UiKit.dp(ctx, 21);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bandPaint.setColor(0x14000000);
    }

    // ==================== 对外 ====================

    void setColors(int on, int off) {
        colorOn = on;
        colorOff = off;
        invalidate();
    }

    void setOnPick(OnPick cb) {
        onPick = cb;
    }

    /** 中间高亮带的颜色（深色主题要给浅色，否则黑底上看不见） */
    void setBandColor(int color) {
        bandPaint.setColor(color);
        invalidate();
    }

    int getValue() {
        return selected;
    }

    /** 直接就位（不播动画），用于打开面板时回填当前时间 */
    void setValue(int value) {
        int v = Math.max(0, Math.min(items.length - 1, value));
        selected = v;
        offset = v;
        invalidate();
    }

    // ==================== 绘制 ====================

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = resolveSize((int) UiKit.dp(getContext(), 88), widthSpec);
        int h = (int) (itemH * 5);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float centerY = getHeight() / 2f;

        // 中间高亮带（选中项的背景胶囊）
        bandRect.set(0, centerY - itemH / 2f, w, centerY + itemH / 2f);
        c.drawRoundRect(bandRect, itemH / 2f, itemH / 2f, bandPaint);

        for (int i = 0; i < items.length; i++) {
            float dy = (i - offset) * itemH;
            if (Math.abs(dy) > centerY + itemH) continue;   // 画不到，跳过

            float t = Math.min(1f, Math.abs(dy) / (itemH * 2.2f));  // 0=正中，1=最远
            float scale = 1f - 0.3f * t;
            textPaint.setTextSize(baseTextSize * scale);
            textPaint.setColor(blend(colorOff, colorOn, 1f - t));
            textPaint.setAlpha((int) (255 * (1f - 0.55f * t)));

            float baseline = centerY + dy + textPaint.getTextSize() * 0.36f;
            c.drawText(items[i], w / 2f, baseline, textPaint);
        }
    }

    // ==================== 手势 ====================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnim();
                downY = e.getY();
                downOffset = offset;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float moved = (downY - e.getY()) / itemH;
                offset = Math.max(0f, Math.min(items.length - 1f, downOffset + moved));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float delta = Math.abs(e.getY() - downY);
                int target;
                if (delta < UiKit.dp(getContext(), 6)) {
                    // 当成点击：直接滚到手指下面那一项
                    target = Math.round(offset + (e.getY() - getHeight() / 2f) / itemH);
                } else {
                    target = Math.round(offset);
                }
                target = Math.max(0, Math.min(items.length - 1, target));
                animateTo(target);
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void animateTo(int target) {
        cancelAnim();
        final int from = Math.round(offset);
        anim = ValueAnimator.ofFloat(offset, target);
        anim.setDuration(Math.abs(target - from) > 3 ? 240 : 160);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();

        boolean changed = selected != target;
        selected = target;
        if (changed && onPick != null) onPick.onPick(target);
    }

    private void cancelAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    private static int blend(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int a = (int) (android.graphics.Color.alpha(from)
                + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * k);
        int r = (int) (android.graphics.Color.red(from)
                + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * k);
        int g = (int) (android.graphics.Color.green(from)
                + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * k);
        int b = (int) (android.graphics.Color.blue(from)
                + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
