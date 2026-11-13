package rrnmask.find;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rrnmask.ocr.TextLine;

/**
 * Finds the back seven digits of resident (and foreigner/corporate) registration numbers in text lines
 * from any source and returns rectangles to mask.
 *
 * <p>Three passes:
 * <ol>
 *   <li>{@link Kind#PATTERN}: {@code 6 digits [-] 7 digits} whose 7th digit is 1–8, on runs of lines joined
 *       left-to-right within one row (a number split across two OCR boxes still matches).
 *       No checksum: numbers issued after 2020-10 do not satisfy it.</li>
 *   <li>{@link Kind#PATTERN}, split columns: a cell ending in six digits followed on the same row by a cell
 *       starting with seven digits (registers with separate front/back columns, gap up to
 *       {@value #SPLIT_GAP} line heights).</li>
 *   <li>{@link Kind#COLUMN}: under a column header that names a registration number, any cell with at least
 *       {@value #COLUMN_MIN_DIGITS} digits not already masked is masked after its 6th digit. Recovers numbers
 *       the OCR garbled (dropped or extra digits).</li>
 * </ol>
 * Common OCR letter/digit confusions are folded to digits before matching.
 */
public final class RrnFinder {

    public enum Kind { PATTERN, COLUMN }

    /** {@code digits} is the folded digit string the decision was made on; it is PII, keep it out of logs. */
    public record Finding(Rectangle mask, Kind kind, String digits) {}

    static final int COLUMN_MIN_DIGITS = 10;
    /** Front part: six digits, or five plus a hyphen (OCR dropped a front digit; the back still needs masking). */
    private static final Pattern RRN = Pattern.compile("(?<!\\d)(\\d{6}\\s*-?|\\d{5}\\s*-)\\s*([1-8]\\d{6})(?!\\d)");
    private static final Pattern HEADER = Pattern.compile("주민|외국인\\s*등록|법인\\s*등록|등록\\s*번호|실명\\s*번호");
    private static final Pattern HEADER_EXCLUDE = Pattern.compile("사업자");
    /** Max horizontal gap between joined lines, in line heights. */
    private static final double JOIN_GAP = 2.5;
    /** Joined lines may overlap this much (detector boxes are padded), in line heights. */
    private static final double JOIN_OVERLAP = 1.0;
    /** A cell ending in six digits, e.g. "960616" or a name merged into it: "강영민960616". */
    private static final Pattern FRONT_END = Pattern.compile("(?<!\\d)(\\d{6})\\s*-?\\s*$");
    /**
     * Seven digits at the start of the next cell. Up to three leading characters are tolerated: when two
     * detector boxes overlap, the recognizer repeats the shared characters ("5-2666026" after "571126").
     */
    private static final Pattern BACK_FIRST = Pattern.compile("^.{0,3}?([1-8]\\d{6})(?!\\d)");
    static final double SPLIT_GAP = 8;
    /** Largest gap, in line heights, between the header and the first cell of its column. */
    private static final double COLUMN_BREAK = 2.5;

    public List<Finding> find(List<TextLine> lines) {
        List<Finding> out = new ArrayList<>();
        for (List<TextLine> chain : chains(lines)) {
            findPattern(chain, out);
        }
        findSplit(lines, out);
        findColumn(lines, out);
        return out;
    }

    private static void findSplit(List<TextLine> lines, List<Finding> out) {
        for (TextLine front : lines) {
            Matcher fm = FRONT_END.matcher(fold(front.text()));
            if (!fm.find()) {
                continue;
            }
            TextLine next = null;
            double bestGap = Double.MAX_VALUE;
            for (TextLine line : lines) {
                double gap = gapRight(front.box(), line.box());
                if (line != front && gap <= SPLIT_GAP * Math.max(front.box().height, line.box().height)
                        && gap < bestGap) {
                    next = line;
                    bestGap = gap;
                }
            }
            if (next == null) {
                continue;
            }
            Matcher m = BACK_FIRST.matcher(fold(next.text()));
            if (!m.find()) {
                continue;
            }
            Rectangle mask = charRect(next, m.start(1), m.end(1) - 1);
            if (!covered(mask, out)) {
                out.add(new Finding(mask, Kind.PATTERN, fm.group(1) + m.group(1)));
            }
        }
    }

    private static void findPattern(List<TextLine> chain, List<Finding> out) {
        StringBuilder text = new StringBuilder();
        List<int[]> refs = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                text.append(' ');
                refs.add(null);
            }
            String t = chain.get(i).text();
            for (int c = 0; c < t.length(); c++) {
                text.append(fold(t.charAt(c)));
                refs.add(new int[] {i, c});
            }
        }
        Matcher m = RRN.matcher(text);
        while (m.find()) {
            Rectangle mask = null;
            for (int k = m.start(2); k < m.end(2); k++) {
                int[] ref = refs.get(k);
                Rectangle r = charRect(chain.get(ref[0]), ref[1], ref[1]);
                mask = mask == null ? r : mask.union(r);
            }
            out.add(new Finding(mask, Kind.PATTERN, m.group(1).replaceAll("\\D", "") + m.group(2)));
        }
    }

    private static void findColumn(List<TextLine> lines, List<Finding> out) {
        for (TextLine header : lines) {
            String h = header.text();
            if (!HEADER.matcher(h).find() || HEADER_EXCLUDE.matcher(h).find() || countDigits(fold(h)) >= 6) {
                continue;
            }
            for (TextLine cell : column(header, lines)) {
                if (covered(cell.box(), out)) {
                    continue;
                }
                String norm = fold(cell.text());
                int digits = 0;
                int sixth = -1;
                int hyphen = -1;
                int last = -1;
                for (int i = 0; i < norm.length(); i++) {
                    char c = norm.charAt(i);
                    if (isDigit(c)) {
                        digits++;
                        if (digits == 6) {
                            sixth = i;
                        }
                        last = i;
                    } else if (c == '-' && hyphen < 0 && digits >= 4) {
                        hyphen = i;
                    }
                }
                if (digits < COLUMN_MIN_DIGITS) {
                    continue;
                }
                // Prefer the separator: a dropped front digit would otherwise leave the first back digit visible.
                int from = hyphen >= 0 && hyphen <= sixth + 1 ? hyphen + 1 : sixth + 1;
                while (!isDigit(norm.charAt(from))) {
                    from++;
                }
                out.add(new Finding(charRect(cell, from, last), Kind.COLUMN, norm.replaceAll("\\D", "")));
            }
        }
    }

    /**
     * Cells under {@code header}: centred within half a header width of it, stacked downward. The run stops
     * at the end of the table, so footer lines (phone numbers, dates) are not taken for cells:
     * <ul>
     *   <li>a vertical gap more than 2.5× the tightest row gap seen so far (plus 0.3 line heights of slack);
     *       the first cell may sit up to {@value #COLUMN_BREAK} line heights below the header;</li>
     *   <li>a line starting more than one header width left of the header or ending more than three past it
     *       (text at the page margin). Numbers are wider than a short Hangul header, hence the asymmetry.</li>
     * </ul>
     */
    private static List<TextLine> column(TextLine header, List<TextLine> lines) {
        Rectangle hb = header.box();
        double lo = hb.x - hb.width * 0.5;
        double hi = hb.getMaxX() + hb.width * 0.5;
        List<TextLine> below = new ArrayList<>();
        for (TextLine l : lines) {
            Rectangle b = l.box();
            if (l != header && b.getCenterX() >= lo && b.getCenterX() <= hi && b.getCenterY() > hb.getMaxY()) {
                below.add(l);
            }
        }
        below.sort(Comparator.comparingInt((TextLine l) -> l.box().y));
        List<TextLine> cells = new ArrayList<>();
        double bottom = hb.getMaxY();
        double rowGap = -1;
        for (TextLine l : below) {
            Rectangle b = l.box();
            double h = Math.max(hb.height, b.height);
            double gap = Math.max(0, b.y - bottom);
            double limit = rowGap < 0 ? COLUMN_BREAK * h : 2.5 * Math.max(rowGap, 0.2 * h) + 0.3 * h;
            if (gap > limit || b.x < hb.x - hb.width || b.getMaxX() > hb.getMaxX() + 3.0 * hb.width) {
                break;
            }
            if (b.y >= bottom) {
                rowGap = rowGap < 0 ? gap : Math.min(rowGap, gap);
            }
            cells.add(l);
            bottom = Math.max(bottom, b.getMaxY());
        }
        return cells;
    }

    /** Lines joined left-to-right: each line extends the chain whose tail it follows most closely on the same row. */
    static List<List<TextLine>> chains(List<TextLine> lines) {
        List<TextLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingInt((TextLine l) -> l.box().x));
        List<List<TextLine>> chains = new ArrayList<>();
        for (TextLine line : sorted) {
            List<TextLine> best = null;
            double bestGap = Double.MAX_VALUE;
            for (List<TextLine> chain : chains) {
                Rectangle tail = chain.getLast().box();
                double gap = gapRight(tail, line.box());
                if (gap <= JOIN_GAP * Math.max(tail.height, line.box().height) && gap < bestGap) {
                    best = chain;
                    bestGap = gap;
                }
            }
            if (best == null) {
                best = new ArrayList<>();
                chains.add(best);
            }
            best.add(line);
        }
        return chains;
    }

    /**
     * Horizontal gap from {@code a} to {@code b} when {@code b} follows {@code a} on the same row
     * (half-height vertical overlap, small horizontal overlap allowed); otherwise infinity.
     */
    private static double gapRight(Rectangle a, Rectangle b) {
        double h = Math.max(a.height, b.height);
        double gap = b.x - a.getMaxX();
        double overlapY = Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.y, b.y);
        if (b.x <= a.x || gap < -JOIN_OVERLAP * h || overlapY < 0.5 * Math.min(a.height, b.height)) {
            return Double.POSITIVE_INFINITY;
        }
        return gap;
    }

    private static Rectangle charRect(TextLine line, int first, int last) {
        float avg = (float) line.box().width / Math.max(1, line.text().length());
        float pad = (line.source() == TextLine.Source.OCR ? 0.5f : 0.15f) * avg;
        int x0 = (int) Math.floor(line.charLeft()[first] - pad);
        int x1 = (int) Math.ceil(line.charRight()[last] + pad);
        return new Rectangle(x0, line.box().y, x1 - x0, line.box().height);
    }

    private static boolean covered(Rectangle box, List<Finding> findings) {
        for (Finding f : findings) {
            if (f.mask().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    private static int countDigits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isDigit(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static String fold(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            b.append(fold(s.charAt(i)));
        }
        return b.toString();
    }

    /** One-to-one so indices into the folded text still address the original characters. */
    static char fold(char c) {
        if (c >= '０' && c <= '９') {
            return (char) ('0' + (c - '０'));
        }
        return switch (c) {
            case 'O', 'o', 'D', 'Q', '〇' -> '0';
            case 'I', 'l', 'i', '|', '!' -> '1';
            case 'Z', 'z' -> '2';
            case 'S', 's' -> '5';
            case 'G', 'b' -> '6';
            case 'B' -> '8';
            case '–', '—', '‐', '‑', '−', '－', '~', '_', '.', '·', 'ㅡ', '一', '=' -> '-';
            default -> c;
        };
    }
}
