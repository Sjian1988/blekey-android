import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成启动器图标 PNG。
 *
 * 工程没有 res 目录, API 26+ 走 adaptive icon(res/drawable 里的 vector),
 * 这里只产出 API 23~25 需要的位图兜底。
 *
 * 视觉: 青绿对角渐变底 + 白色钥匙。设计基准 108x108, 与 adaptive foreground 的
 * viewport 一致 —— 两边用同一套坐标, 换尺寸只改一个缩放系数。
 *
 * 用法(在 blekey 目录下):
 *   java tools/GenIcon.java
 */
public class GenIcon {

    /** 1x/1.5x/2x/3x/4x */
    private static final int[] SIZES = {48, 72, 96, 144, 192};
    private static final String[] DENS = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};

    /** 渐变两端: 左上偏深, 右下偏亮, 让图标有一点体积感 */
    private static final Color C0 = new Color(0x0B5F58);
    private static final Color C1 = new Color(0x14B8A6);

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < SIZES.length; i++) {
            int px = SIZES[i];
            File dir = new File("res/mipmap-" + DENS[i]);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("mkdir fail: " + dir);
            }
            ImageIO.write(render(px, false), "png", new File(dir, "ic_launcher.png"));
            ImageIO.write(render(px, true), "png", new File(dir, "ic_launcher_round.png"));
            System.out.println("  " + dir.getName() + "  " + px + "x" + px);
        }
        new File("build").mkdirs();
        ImageIO.write(render(512, false), "png", new File("build/icon_preview.png"));
        ImageIO.write(render(512, true), "png", new File("build/icon_preview_round.png"));
        // 桌面实际效果: adaptive icon 只显示 108 画布中间 66 单位那块, 再套系统遮罩
        ImageIO.write(mockAdaptive(384, false), "png", new File("build/icon_mock_square.png"));
        ImageIO.write(mockAdaptive(384, true), "png", new File("build/icon_mock_circle.png"));
        System.out.println("OK");
    }

    /**
     * 模拟启动器渲染 adaptive icon: 把 108 画布的中心 66x66 铺满图标, 再套圆角/圆形遮罩。
     * 用来确认图形没有被安全区切到、也不会显得太小。
     */
    private static BufferedImage mockAdaptive(int out, boolean circle) {
        BufferedImage src = render(108 * 4, false);
        BufferedImage img = new BufferedImage(out, out, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        Shape mask = circle
                ? new Ellipse2D.Float(0, 0, out, out)
                : new java.awt.geom.RoundRectangle2D.Float(0, 0, out, out, out * 0.42f, out * 0.42f);
        g.setClip(mask);

        // 源图是 108 单位的满铺画布, 目标只显示中心 66 单位 -> 整张图按 108/66 放大
        float w = 108f * (out / 66f);
        g.drawImage(src, Math.round((out - w) / 2f), Math.round((out - w) / 2f),
                Math.round(w), Math.round(w), null);
        g.dispose();
        return img;
    }

    private static BufferedImage render(int px, boolean circular) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        float s = px / 108f;

        // 底: 方形满铺(启动器会自己裁圆角), 圆形版给旧机型的圆图标位
        Shape bg = circular
                ? new Ellipse2D.Float(0, 0, px, px)
                : new Rectangle2D.Float(0, 0, px, px);
        g.setPaint(new GradientPaint(0, 0, C0, px, px, C1));
        g.fill(bg);

        // 左上角一层柔光, 纯平渐变会显得很"塑料"
        g.setPaint(new RadialGradientPaint(
                px * 0.28f, px * 0.18f, px * 0.85f,
                new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 34), new Color(255, 255, 255, 0)}));
        g.fill(bg);

        // 钥匙: 环 + 杆 + 两齿, 全部白色圆头描边。
        //
        // 尺寸按 adaptive icon 的"可见区"算, 不是按 108 整张画布 —— 启动器只显示中心
        // 66 单位, 图形占可见区 75% 左右是安全区间; 按 108 铺满会顶到遮罩边缘被切。
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(5.5f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        float cy = 54 * s;
        float cx = 40 * s;
        float r = 8.2f * s;
        g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        g.draw(new Line2D.Float(49 * s, cy, 76 * s, cy));
        g.setStroke(new BasicStroke(4.8f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(65 * s, cy, 65 * s, 65 * s));
        g.draw(new Line2D.Float(73 * s, cy, 73 * s, 62 * s));

        g.dispose();
        return img;
    }
}
