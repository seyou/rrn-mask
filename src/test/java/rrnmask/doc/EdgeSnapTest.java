package rrnmask.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class EdgeSnapTest {

    /** White strip with two 16px "glyphs" at 0..16 and 20..36: the gap between them is 16..20. */
    private static BufferedImage twoGlyphs() {
        BufferedImage img = new BufferedImage(100, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 30);
        g.setColor(Color.BLACK);
        g.fillRect(0, 5, 16, 20);
        g.fillRect(20, 5, 16, 20);
        g.dispose();
        return img;
    }

    @Test
    void edgeInsideThePrecedingGlyphMovesIntoTheGap() {
        Rectangle snapped = EdgeSnap.snapLeft(twoGlyphs(), new Rectangle(12, 0, 60, 30), 18);
        assertEquals(17, snapped.x);
        assertEquals(72, (int) snapped.getMaxX(), "right edge stays");
    }

    @Test
    void edgeInsideTheBackGlyphMovesBackToTheGap() {
        assertEquals(17, EdgeSnap.snapLeft(twoGlyphs(), new Rectangle(23, 0, 60, 30), 18).x);
    }

    @Test
    void blankAreaLeavesTheMaskAlone() {
        BufferedImage blank = new BufferedImage(100, 30, BufferedImage.TYPE_INT_RGB);
        Rectangle mask = new Rectangle(40, 0, 50, 30);
        assertEquals(mask, EdgeSnap.snapLeft(blank, mask, 18));
    }
}
