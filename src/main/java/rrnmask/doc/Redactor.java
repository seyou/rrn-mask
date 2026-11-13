package rrnmask.doc;

import ai.onnxruntime.OrtException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import rrnmask.find.RrnFinder;
import rrnmask.find.RrnFinder.Finding;
import rrnmask.ocr.OcrEngine;
import rrnmask.ocr.TextLine;

/**
 * Image or PDF in, one masked raster per page out, handed over page by page so only one page's rasters are
 * alive at a time. Everything stays in memory: ImageIO and PDFBox run on memory-only caches.
 *
 * <p>The input stream is read to the end but not fully streamed: PDF needs random access and multi-page TIFF
 * a frame index, so both are buffered in memory. Bound upload size before calling.
 *
 * <p>Output is always a raster with solid black boxes. Drawing over a PDF would leave the text layer readable.
 */
public final class Redactor {

    /**
     * @param dpi     PDF render resolution
     * @param ocr     run OCR (null: text-layer only)
     * @param pdfText also read the PDF's embedded text layer
     */
    public record Options(int dpi, OcrEngine ocr, boolean pdfText) {}

    public record Timings(long renderMillis, long detMillis, long recMillis, long pdfTextMillis, long findMillis) {}

    public record Page(int number, BufferedImage image, BufferedImage masked, List<TextLine> lines,
                       List<Finding> findings, Timings timings) {}

    /** Receives each page as soon as it is masked; the page's rasters are dropped after it returns. */
    public interface PageHandler {
        void accept(Page page) throws IOException;
    }

    /** Enough to see the PDF signature and a JPEG's EXIF segment (APP1 is at most 64 KiB). */
    private static final int HEAD = 128 * 1024;

    private final Options options;
    private final RrnFinder finder = new RrnFinder();

    public Redactor(Options options) {
        this.options = options;
    }

    /** Does not close {@code document}. */
    public void redact(InputStream document, PageHandler handler) throws IOException, OrtException {
        BufferedInputStream in = new BufferedInputStream(document, HEAD);
        in.mark(HEAD);
        byte[] head = in.readNBytes(HEAD);
        in.reset();
        if (head.length == 0) {
            throw new IOException("empty document");
        }
        if (isPdf(head)) {
            redactPdf(in, handler);
        } else {
            PageImages.read(in, ExifOrientation.read(head),
                    (number, image, decode) -> handler.accept(process(number, image, List.of(), decode, 0)));
        }
    }

    private void redactPdf(InputStream in, PageHandler handler) throws IOException, OrtException {
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(in), "", null, null,
                IOUtils.createMemoryOnlyStreamCache())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                long t0 = System.nanoTime();
                BufferedImage image = renderer.renderImageWithDPI(i, options.dpi(), ImageType.RGB);
                long render = millisSince(t0);
                List<TextLine> textLayer = List.of();
                long pdfText = 0;
                if (options.pdfText()) {
                    t0 = System.nanoTime();
                    textLayer = PdfTextLayer.extract(doc, i + 1, options.dpi() / 72f);
                    pdfText = millisSince(t0);
                }
                handler.accept(process(i + 1, image, textLayer, render, pdfText));
            }
        }
    }

    private Page process(int number, BufferedImage image, List<TextLine> textLayer, long render, long pdfText)
            throws OrtException {
        List<TextLine> ocrLines = List.of();
        long det = 0;
        long rec = 0;
        if (options.ocr() != null) {
            OcrEngine.Result r = options.ocr().read(image);
            ocrLines = r.lines();
            det = r.detMillis();
            rec = r.recMillis();
        }
        long t0 = System.nanoTime();
        // Sources are searched separately: an OCR box and a text-layer word on the same spot would
        // otherwise interleave in one row and break the pattern.
        List<Finding> ocrFindings = new ArrayList<>();
        for (Finding f : finder.find(ocrLines)) {
            // About seven digits plus the right padding: a rough pitch is enough to bound the search.
            // Only edges abutting the front digits are snapped; a separator already gives a safe margin.
            ocrFindings.add(f.abutsFront() ? f.withMask(EdgeSnap.snapLeft(image, f.mask(), f.mask().width / 8.0)) : f);
        }
        List<Finding> findings = merge(finder.find(textLayer), ocrFindings);
        long find = millisSince(t0);
        List<TextLine> lines = new ArrayList<>(ocrLines);
        lines.addAll(textLayer);
        return new Page(number, image, mask(image, findings), lines, findings,
                new Timings(render, det, rec, pdfText, find));
    }

    public static BufferedImage mask(BufferedImage image, List<Finding> findings) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.setColor(Color.BLACK);
        for (Finding f : findings) {
            g.fill(f.mask());
        }
        g.dispose();
        return out;
    }

    /**
     * Text-layer boxes come from the PDF's own glyph positions, so where both sources found the same number the
     * text-layer box wins; a union would carry the OCR box's looser edge onto the front digits.
     * Within each source, overlapping boxes collapse into their union.
     */
    static List<Finding> merge(List<Finding> preferred, List<Finding> others) {
        List<Finding> out = collapse(preferred);
        int kept = out.size();
        for (Finding f : collapse(others)) {
            boolean duplicate = false;
            for (int i = 0; i < kept && !duplicate; i++) {
                duplicate = overlaps(out.get(i).mask(), f.mask());
            }
            if (!duplicate) {
                out.add(f);
            }
        }
        return out;
    }

    private static List<Finding> collapse(List<Finding> in) {
        List<Finding> out = new ArrayList<>();
        outer:
        for (Finding f : in) {
            for (int i = 0; i < out.size(); i++) {
                Finding g = out.get(i);
                if (overlaps(g.mask(), f.mask())) {
                    out.set(i, g.withMask(g.mask().union(f.mask())));
                    continue outer;
                }
            }
            out.add(f);
        }
        return out;
    }

    private static boolean overlaps(Rectangle a, Rectangle b) {
        Rectangle overlap = a.intersection(b);
        return !overlap.isEmpty() && area(overlap) >= 0.3 * Math.min(area(a), area(b));
    }

    private static double area(Rectangle r) {
        return (double) r.width * r.height;
    }

    private static boolean isPdf(byte[] b) {
        return b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-';
    }

    private static long millisSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
