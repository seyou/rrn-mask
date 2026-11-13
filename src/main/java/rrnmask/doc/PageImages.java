package rrnmask.doc;

import ai.onnxruntime.OrtException;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import rrnmask.ocr.Images;

/**
 * Decodes raster uploads (PNG, JPEG, BMP, GIF, multi-page TIFF) into upright RGB pages, one at a time and in
 * memory only: {@code ImageIO.createImageInputStream(InputStream)} would spill the upload to a temp file while
 * ImageIO's cache is on (the default).
 */
final class PageImages {
    private PageImages() {}

    interface Frames {
        void accept(int number, BufferedImage page, long decodeMillis) throws IOException, OrtException;
    }

    /**
     * Hands each frame to {@code frames} as soon as it is decoded; the previous frame is no longer referenced.
     * Does not close {@code in}.
     *
     * @param orientation EXIF orientation, applied when the image has a single frame
     */
    static void read(InputStream in, int orientation, Frames frames) throws IOException, OrtException {
        try (ImageInputStream iis = new MemoryCacheImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("unsupported image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int n = reader.getNumImages(true);
                for (int i = 0; i < n; i++) {
                    long t0 = System.nanoTime();
                    BufferedImage page = Images.toRgb(reader.read(i));
                    if (n == 1) {
                        page = orient(page, orientation);
                    }
                    frames.accept(i + 1, page, (System.nanoTime() - t0) / 1_000_000);
                }
            } finally {
                reader.dispose();
            }
        }
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
