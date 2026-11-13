package rrnmask.ocr;

import java.awt.Rectangle;

/**
 * One run of text on a page image, with the horizontal extent of every character in page-image pixels.
 * {@code charLeft[i]..charRight[i]} is where {@code text.charAt(i)} sits; the finder masks by these extents.
 */
public record TextLine(Rectangle box, String text, float score, float[] charLeft, float[] charRight, Source source) {

    public enum Source { OCR, PDF_TEXT }

    public TextLine {
        if (charLeft.length != text.length() || charRight.length != text.length()) {
            throw new IllegalArgumentException("char extents must match text length");
        }
    }
}
