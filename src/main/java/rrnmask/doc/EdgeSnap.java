package rrnmask.doc;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Moves an OCR mask's left edge into the blank gap between the 6th front digit and the first back digit.
 *
 * <p>The OCR edge comes from CTC time steps and can land a quarter of a digit early, shaving the 6th digit when
 * the number has no separator. Printed digits are separated by a thin blank column, so within ±{@value #REACH}
 * of a digit pitch around the estimated edge the column profile of ink has a valley; the edge moves to the
 * middle of the valley nearest to it. Without a clear valley (no contrast) the mask is left as is.
 */
final class EdgeSnap {
    private static final double REACH = 0.4;
    /** Columns within this fraction of the window's ink range above its minimum count as blank. */
    private static final double VALLEY = 0.15;
    /** Below this ink range (summed darkness per column, per band row) there is nothing to snap to. */
    private static final double MIN_CONTRAST = 20;

    private EdgeSnap() {}

    /** @param pitch estimated width of one digit, in pixels */
    static Rectangle snapLeft(BufferedImage image, Rectangle mask, double pitch) {
        int lo = Math.max(0, (int) Math.floor(mask.x - REACH * pitch));
        int hi = Math.min(image.getWidth() - 1, (int) Math.ceil(mask.x + REACH * pitch));
        int y0 = Math.max(0, mask.y + mask.height / 6);
        int y1 = Math.min(image.getHeight(), mask.y + mask.height * 5 / 6);
        if (hi - lo < 2 || y1 - y0 < 2) {
            return mask;
        }
        int w = hi - lo + 1;
        int[] px = image.getRGB(lo, y0, w, y1 - y0, null, 0, w);
        long[] ink = new long[w];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int lum = (299 * ((p >> 16) & 0xff) + 587 * ((p >> 8) & 0xff) + 114 * (p & 0xff)) / 1000;
            ink[i % w] += 255 - lum;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long v : ink) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if ((double) (max - min) / (y1 - y0) < MIN_CONTRAST) {
            return mask;
        }
        long blank = min + (long) (VALLEY * (max - min));
        int bestCenter = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < w; ) {
            if (ink[i] > blank) {
                i++;
                continue;
            }
            int start = i;
            while (i < w && ink[i] <= blank) {
                i++;
            }
            int center = lo + (start + i - 1) / 2;
            double distance = Math.abs(center - mask.x);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestCenter = center;
            }
        }
        if (bestCenter < 0 || bestCenter == mask.x) {
            return mask;
        }
        return new Rectangle(bestCenter, mask.y, (int) mask.getMaxX() - bestCenter, mask.height);
    }
}
