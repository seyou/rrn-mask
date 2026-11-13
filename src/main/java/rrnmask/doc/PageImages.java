package rrnmask.doc;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import rrnmask.ocr.Images;

/** Decodes raster uploads (PNG, JPEG, BMP, GIF, multi-page TIFF) into upright RGB pages. */
final class PageImages {
    private PageImages() {}

    static List<BufferedImage> read(byte[] bytes) throws IOException {
        List<BufferedImage> pages = new ArrayList<>();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("unsupported image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int n = reader.getNumImages(true);
                for (int i = 0; i < n; i++) {
                    pages.add(Images.toRgb(reader.read(i)));
                }
            } finally {
                reader.dispose();
            }
        }
        if (pages.size() == 1) {
            pages.set(0, orient(pages.getFirst(), ExifOrientation.read(bytes)));
        }
        return pages;
    }

    /** Applies EXIF orientation 2–8 so the page is stored the way a viewer shows it. */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            default -> new AffineTransform(0, -1, 1, 0, 0, w);
        };
        boolean swap = orientation >= 5;
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, t, null);
        g.dispose();
        return dst;
    }
}
