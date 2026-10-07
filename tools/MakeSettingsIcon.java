import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates res/drawable/settings_gear.png — a blue gear icon on white,
 * matching the style of the user-provided reference image.
 * Used as the launcher settings shortcut icon in the app grid.
 */
public class MakeSettingsIcon {
    public static void main(String[] a) throws Exception {
        int S = 128;
        BufferedImage im = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // White background with subtle rounded corners
        g.setColor(new Color(245, 245, 245));
        g.fillRoundRect(0, 0, S, S, 20, 20);

        // Blue gear body
        int cx = S / 2, cy = S / 2;
        int outerR = 44, innerR = 28, holeR = 14;
        int teeth = 8;

        // Draw gear teeth
        g.setColor(new Color(30, 100, 200));
        for (int i = 0; i < teeth; i++) {
            double angle = i * 2 * Math.PI / teeth;
            int tx = cx + (int) (Math.cos(angle) * (outerR - 4));
            int ty = cy + (int) (Math.sin(angle) * (outerR - 4));
            g.fillOval(tx - 10, ty - 10, 20, 20);
        }

        // Draw gear body (outer circle)
        g.fillOval(cx - outerR, cy - outerR, outerR * 2, outerR * 2);

        // Draw inner ring (lighter blue)
        g.setColor(new Color(80, 140, 220));
        g.fillOval(cx - innerR, cy - innerR, innerR * 2, innerR * 2);

        // Draw center hole (white)
        g.setColor(Color.WHITE);
        g.fillOval(cx - holeR, cy - holeR, holeR * 2, holeR * 2);

        // Subtle highlight on top
        g.setColor(new Color(255, 255, 255, 60));
        g.fillOval(cx - outerR + 4, cy - outerR + 4, outerR * 2 - 8, outerR - 4);

        g.dispose();

        File out = new File("res/drawable/settings_gear.png");
        if (!new File("res").exists() && new File("../res").exists()) {
            out = new File("../res/drawable/settings_gear.png");
        }
        out.getParentFile().mkdirs();
        ImageIO.write(im, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }
}
