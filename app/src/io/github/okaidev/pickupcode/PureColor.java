package io.github.okaidev.pickupcode;

/**
 * 纯 Java 的颜色换算（不依赖 android.graphics.Color）。
 *
 * <p>为什么要单独抽出来：深色配色是用 HSL 推导的（见 {@link UiKit.Palette#toDark()}），
 * 而单元测试跑在 JVM 上，android.jar 里的 {@code Color} 只是空壳（调用即抛 Stub!），
 * 颜色推导这类最该被测的逻辑反而测不了。
 *
 * <p>这里用位运算自己拼 ARGB，语义与 {@code Color.rgb()} 一致。
 */
public final class PureColor {

    private PureColor() {
    }

    /** HSL -> ARGB。h 为角度（0-360），s/l 为 0-1 */
    public static int hslToArgb(float h, float s, float l) {
        h = ((h % 360f) + 360f) % 360f;
        s = clamp01(s);
        l = clamp01(l);
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float x = c * (1 - Math.abs((h / 60f) % 2f - 1f));
        float m = l - c / 2f;
        float r, g, b;
        if (h < 60f) {
            r = c; g = x; b = 0;
        } else if (h < 120f) {
            r = x; g = c; b = 0;
        } else if (h < 180f) {
            r = 0; g = c; b = x;
        } else if (h < 240f) {
            r = 0; g = x; b = c;
        } else if (h < 300f) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        return argb(Math.round((r + m) * 255f), Math.round((g + m) * 255f),
                Math.round((b + m) * 255f));
    }

    /** ARGB -> {h, s, l} */
    public static float[] argbToHsl(int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float h, s, l = (max + min) / 2f;
        float d = max - min;
        if (d == 0f) {
            h = 0f;
            s = 0f;
        } else {
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r) {
                h = ((g - b) / d + (g < b ? 6f : 0f)) * 60f;
            } else if (max == g) {
                h = ((b - r) / d + 2f) * 60f;
            } else {
                h = ((r - g) / d + 4f) * 60f;
            }
        }
        return new float[]{h, s, l};
    }

    /** 相对亮度（0-1），用于判断文字与背景的对比度 */
    public static double relativeLuminance(int color) {
        double r = srgb(((color >> 16) & 0xFF) / 255.0);
        double g = srgb(((color >> 8) & 0xFF) / 255.0);
        double b = srgb((color & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG 对比度（1-21） */
    public static double contrastRatio(int a, int b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double srgb(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
