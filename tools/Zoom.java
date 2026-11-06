import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 放大截图局部, 用来看清 20dp 图标这类细节。用法: Zoom in.png out.png x y w h scale */
public class Zoom {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]);
        int w = Integer.parseInt(a[4]), h = Integer.parseInt(a[5]);
        int s = Integer.parseInt(a[6]);
        BufferedImage sub = src.getSubimage(x, y, w, h);
        BufferedImage out = new BufferedImage(w * s, h * s, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(sub, 0, 0, w * s, h * s, null);
        g.dispose();
        ImageIO.write(out, "png", new File(a[1]));
        System.out.println("ok " + w * s + "x" + h * s);
    }
}
