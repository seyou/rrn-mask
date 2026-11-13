package rrnmask.doc;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import rrnmask.ocr.TextLine;

/** Text already embedded in a PDF page (vector text or a scanner's invisible OCR layer), one line per word. */
final class PdfTextLayer extends PDFTextStripper {
    private final float scale;
    private final List<TextLine> lines = new ArrayList<>();

    private PdfTextLayer(float scale) {
        this.scale = scale;
        setSortByPosition(true);
    }

    /** @param scale page-image pixels per PDF point (dpi / 72) */
    static List<TextLine> extract(PDDocument doc, int pageNumber, float scale) throws IOException {
        PdfTextLayer layer = new PdfTextLayer(scale);
        layer.setStartPage(pageNumber);
        layer.setEndPage(pageNumber);
        layer.getText(doc);
        return layer.lines;
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
        StringBuilder sb = new StringBuilder();
        List<float[]> extents = new ArrayList<>();
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (TextPosition tp : positions) {
            String u = tp.getUnicode();
            if (u == null || u.isEmpty()) {
                continue;
            }
            float x0 = tp.getXDirAdj() * scale;
            float x1 = (tp.getXDirAdj() + tp.getWidthDirAdj()) * scale;
            float size = (tp.getFontSizeInPt() > 0 ? tp.getFontSizeInPt() : tp.getHeightDir() * 2) * scale;
            float baseline = tp.getYDirAdj() * scale;
            top = Math.min(top, baseline - size);
            bottom = Math.max(bottom, baseline + 0.25f * size);
            for (int i = 0; i < u.length(); i++) {
                sb.append(u.charAt(i));
                extents.add(new float[] {x0, x1});
            }
        }
        if (sb.isEmpty()) {
            return;
        }
        int n = sb.length();
        float[] left = new float[n];
        float[] right = new float[n];
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            left[i] = extents.get(i)[0];
            right[i] = extents.get(i)[1];
            minX = Math.min(minX, left[i]);
            maxX = Math.max(maxX, right[i]);
        }
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(top);
        Rectangle box = new Rectangle(x, y, (int) Math.ceil(maxX) - x, (int) Math.ceil(bottom) - y);
        lines.add(new TextLine(box, sb.toString(), 1f, left, right, TextLine.Source.PDF_TEXT));
    }
}
