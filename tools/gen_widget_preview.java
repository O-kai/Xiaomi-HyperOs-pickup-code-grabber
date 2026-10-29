import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成桌面小组件的预览图（widget_preview.png）。
 *
 * 为什么需要：MIUI 桌面在选择器里会读取 AppWidgetProviderInfo.previewImage，
 * 没有预览图的小组件可能不会出现在「全部应用」列表里，requestPinAppWidget 也会被直接取消。
 *
 * 用法（仓库根目录）：
 *   java tools/gen_widget_preview.java app/res/drawable-nodpi/widget_preview.png
 *
 * 输出尺寸按 4x2 小组件的实际比例（250x180dp，2.4x 密度）绘制。
 */
public class gen_widget_preview {

    private static final int W = 600;
    private static final int H = 432;

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "widget_preview.png";
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 透明底 + 白色圆角卡片
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, W, H);
        g.setColor(Color.WHITE);
        g.fillRoundRect(2, 2, W - 4, H - 4, 44, 44);
        g.setColor(new Color(0xE6, 0xEC, 0xF3));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(2, 2, W - 5, H - 5, 44, 44);

        Font bold = new Font("Microsoft YaHei", Font.BOLD, 34);
        Font mono = new Font("Consolas", Font.BOLD, 46);
        Font small = new Font("Microsoft YaHei", Font.PLAIN, 22);
        Font tiny = new Font("Microsoft YaHei", Font.PLAIN, 20);

        g.setColor(new Color(0x1A, 0x1A, 0x1A));
        g.setFont(bold);
        g.drawString("待取件", 36, 66);

        g.setFont(small);
        g.setColor(new Color(0x8A, 0x8F, 0x98));
        drawRight(g, "2 个 · 查看全部 ›", W - 36, 64);

        // 分隔线
        g.setColor(new Color(0xF0, 0xF2, 0xF5));
        g.fillRect(36, 92, W - 72, 2);

        drawRow(g, mono, small, tiny, 150, "2-2-2220", "丰巢智能柜", true);
        g.setColor(new Color(0xF0, 0xF2, 0xF5));
        g.fillRect(36, 236, W - 72, 2);
        drawRow(g, mono, small, tiny, 300, "3-3-3330", "菜鸟驿站", true);

        g.setColor(new Color(0xF0, 0xF2, 0xF5));
        g.fillRect(36, 386, W - 72, 2);
        g.setFont(tiny);
        g.setColor(new Color(0x9A, 0xA0, 0xA8));
        g.drawString("点码复制 · 点「已取」标记完成", 36, 416);

        g.dispose();
        File f = new File(out);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        ImageIO.write(img, "png", f);
        System.out.println("written: " + f.getAbsolutePath() + " (" + f.length() + " bytes)");
    }

    private static void drawRow(Graphics2D g, Font mono, Font small, Font tiny,
                                int baseline, String code, String place, boolean pending) {
        g.setFont(mono);
        g.setColor(new Color(0x1A, 0x1A, 0x1A));
        g.drawString(code, 36, baseline);

        g.setFont(small);
        g.setColor(new Color(0x8A, 0x8F, 0x98));
        g.drawString(place, 36 + 300, baseline - 6);

        if (pending) {
            g.setFont(tiny);
            g.setColor(new Color(0x3D, 0x6F, 0xB5));
            drawRight(g, "已取", W - 36, baseline);
        }
    }

    private static void drawRight(Graphics2D g, String s, int right, int baseline) {
        int w = g.getFontMetrics().stringWidth(s);
        g.drawString(s, right - w, baseline);
    }
}
