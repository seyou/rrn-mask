package rrnmask.ocr;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

public final class Images {
    private Images() {}

    /**
     * Encodes to bytes without touching disk. {@code ImageIO.write(img, fmt, OutputStream)} would buffer through
     * a temp file when ImageIO's cache is on (the default); the memory cache stream avoids that.
     *
     * @param format  ImageIO format name, e.g. {@code "png"} or {@code "jpeg"}
     * @param quality 0..1 compression quality for lossy formats; ignored for PNG
     */
    public static byte[] encode(BufferedImage img, String format, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed() && !format.equalsIgnoreCase("png")) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    public static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    public static BufferedImage resize(BufferedImage src, int width, int height) {
        BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return dst;
    }

    /** Planar BGR float tensor ({@code [3][h][w]}), Paddle's channel order: {@code (v/255 - mean[c]) / std[c]}. */
    static void writeBgrPlanes(BufferedImage img, float[] dst, int offset, int rowStride, int planeStride,
                               float[] mean, float[] std) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            int row = offset + y * rowStride;
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                float b = (p & 0xff) / 255f;
                float g = ((p >> 8) & 0xff) / 255f;
                float r = ((p >> 16) & 0xff) / 255f;
                dst[row + x] = (b - mean[0]) / std[0];
                dst[row + planeStride + x] = (g - mean[1]) / std[1];
                dst[row + 2 * planeStride + x] = (r - mean[2]) / std[2];
            }
        }
    }
}
