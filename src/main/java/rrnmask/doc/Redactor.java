package rrnmask.doc;

import ai.onnxruntime.OrtException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import rrnmask.find.RrnFinder;
import rrnmask.find.RrnFinder.Finding;
import rrnmask.ocr.OcrEngine;
import rrnmask.ocr.TextLine;

/**
 * Image or PDF bytes in, one masked raster per page out. Everything stays in memory: PDFBox runs on a
 * memory-only stream cache and nothing is written to disk.
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

    private final Options options;
    private final RrnFinder finder = new RrnFinder();

    public Redactor(Options options) {
        this.options = options;
    }

    public List<Page> redact(byte[] bytes) throws IOException, OrtException {
        return isPdf(bytes) ? redactPdf(bytes) : redactImages(bytes);
    }

    private List<Page> redactImages(byte[] bytes) throws IOException, OrtException {
        long t0 = System.nanoTime();
        List<BufferedImage> images = PageImages.read(bytes);
        long decode = millisSince(t0) / Math.max(1, images.size());
        List<Page> pages = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            pages.add(process(i + 1, images.get(i), List.of(), decode, 0));
        }
        return pages;
    }

    private List<Page> redactPdf(byte[] bytes) throws IOException, OrtException {
        List<Page> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache())) {
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
                pages.add(process(i + 1, image, textLayer, render, pdfText));
            }
        }
        return pages;
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
        List<Finding> findings = new ArrayList<>(finder.find(ocrLines));
        findings.addAll(finder.find(textLayer));
        findings = merge(findings);
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

    /** Collapses overlapping boxes (same number found by OCR and by the text layer) into their union. */
    static List<Finding> merge(List<Finding> in) {
        List<Finding> out = new ArrayList<>();
        outer:
        for (Finding f : in) {
            for (int i = 0; i < out.size(); i++) {
                Finding g = out.get(i);
                Rectangle overlap = g.mask().intersection(f.mask());
                if (!overlap.isEmpty() && area(overlap) >= 0.3 * Math.min(area(g.mask()), area(f.mask()))) {
                    out.set(i, new Finding(g.mask().union(f.mask()), g.kind(), g.digits()));
                    continue outer;
                }
            }
            out.add(f);
        }
        return out;
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
