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
 * Common OCR letter/digit confusions are folded to digits, and mask glyphs ({@code * ● ■ □ # X ×}) to
 * {@code *}, before matching. Numbers the document already masks ("1******") are left alone; a number whose
 * front was masked but back left readable ("******-1234567"), or whose back is only partly masked
 * ("900101-12*****"), is masked.
 */
public final class RrnFinder {

    public enum Kind { PATTERN, COLUMN }

    /**
     * @param digits     the folded digit string the decision was made on; it is PII, keep it out of logs
     * @param abutsFront the back part directly follows the front's last digit in the same text run, no separator:
     *                   the mask's left edge sits on a glyph boundary that an image-based refinement can sharpen
     */
    public record Finding(Rectangle mask, Kind kind, String digits, boolean abutsFront) {
        public Finding withMask(Rectangle m) {
            return new Finding(m, kind, digits, abutsFront);
        }
    }

    static final int COLUMN_MIN_DIGITS = 10;
    /**
     * Front: six digits; five digits and a hyphen (OCR dropped one); or six digits/mask characters and a hyphen.
     * Back: seven digits/mask characters, judged by {@link #exposedBack}. Mask characters are folded to {@code *}.
     */
    private static final Pattern RRN =
            Pattern.compile("(?<![0-9*])(\\d{6}\\s*-?|\\d{5}\\s*-|[0-9*]{6}\\s*-)\\s*([0-9*]{7})(?![0-9*])");
    /**
     * Back partly masked by the document's author, whatever number of stars OCR reports: it miscounts runs of
     * asterisks ("890618-14****" for "14*****", "10*" for "10*****").
     */
    private static final Pattern PARTIAL_BACK =
            Pattern.compile("(?<![0-9*])(\\d{6}\\s*-)\\s*([1-8]\\d{1,5}\\*+)(?![0-9*])");
    /**
     * Front masked by the author, any star count, back still readable: "*****_2061087", "9001**-1234567", or a
     * lone "*" read from "******" followed by the back in its own box. The star run must not itself follow
     * "digit-": in "900101-1******1500000" it is a conventionally masked back run into a share count.
     */
    private static final Pattern MASKED_FRONT = Pattern.compile(
            "(?<![0-9*])(?<!\\d\\s?-\\s?)([0-9]{0,5}\\*[0-9*]{0,7})\\s*-?\\s*([1-8]\\d{6})(?![0-9*])");
    /**
     * A hyphen and seven digits with no front at all: the detector often finds no text in a run of asterisks, so
     * "******-1211851" comes back as "-1211851". The hyphen must not follow a digit (phone, date ranges).
     */
    private static final Pattern LONE_BACK =
            Pattern.compile("(?<![0-9*])(?<![0-9*]\\s)(-)\\s*([1-8]\\d{6})(?![0-9*])");
    private static final List<Pattern> PATTERNS = List.of(RRN, PARTIAL_BACK, MASKED_FRONT, LONE_BACK);
    /** A cell whose only digits are seven in a row, first 1–8: the back of a number whose front is masked. */
    private static final Pattern BACK_ONLY_CELL = Pattern.compile("[^0-9]*([1-8]\\d{6})[^0-9]*");
    private static final Pattern HEADER = Pattern.compile("주민|외국인\\s*등록|법인\\s*등록|등록\\s*번호|실명\\s*번호");
    private static final Pattern HEADER_EXCLUDE = Pattern.compile("사업자");
    /** Max horizontal gap between joined lines, in line heights. */
    private static final double JOIN_GAP = 2.5;
    /** Joined lines may overlap this much (detector boxes are padded), in line heights. */
    private static final double JOIN_OVERLAP = 1.0;
    /** A cell ending in six digits (or masked front), e.g. "960616", "******" or a name merged in: "강영민960616". */
    private static final Pattern FRONT_END = Pattern.compile("(?<![0-9*])([0-9*]{6})\\s*-?\\s*$");
    /**
     * Seven digits/mask characters at the start of the next cell. Up to three leading characters are tolerated:
     * when two detector boxes overlap, the recognizer repeats the shared characters ("5-2666026" after "571126").
     */
    private static final Pattern BACK_FIRST = Pattern.compile("^.{0,3}?([1-8]\\d{1,5}\\*+|[0-9*]{7})(?![0-9*])");
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
            if (!m.find() || !exposedBack(m.group(1), true)) {
                continue;
            }
            Rectangle mask = charRect(next, m.start(1), m.end(1) - 1);
            if (!covered(mask, out)) {
                out.add(new Finding(mask, Kind.PATTERN, fm.group(1) + m.group(1), false));
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
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (exposedBack(m.group(2), m.group(1).indexOf('-') >= 0)) {
                    emit(m, text, refs, chain, out);
                }
            }
        }
    }

    /** Masks group 2 (the back) of a match; group 1 is the front, possibly just a separator. */
    private static void emit(Matcher m, CharSequence text, List<int[]> refs, List<TextLine> chain,
                             List<Finding> out) {
        Rectangle mask = null;
        for (int k = m.start(2); k < m.end(2); k++) {
            int[] ref = refs.get(k);
            Rectangle r = charRect(chain.get(ref[0]), ref[1], ref[1]);
            mask = mask == null ? r : mask.union(r);
        }
        if (covered(mask, out)) {
            return;  // an earlier pattern already took this number
        }
        int lastFront = m.end(1) - 1;
        while (lastFront >= m.start(1) && !isFrontChar(text.charAt(lastFront))) {
            lastFront--;
        }
        int[] back = refs.get(m.start(2));
        boolean abuts = false;
        if (lastFront >= m.start(1)) {
            int[] front = refs.get(lastFront);
            abuts = front[0] == back[0] && back[1] - front[1] == 1;
            if (front[0] == back[0]) {
                mask = overSeparator(mask, chain.get(back[0]), front[1], back[1]);
            }
        }
        out.add(new Finding(mask, Kind.PATTERN, m.group(1).replaceAll("[^0-9*]", "") + m.group(2), abuts));
    }

    /**
     * Whether a back part still shows something worth masking.
     * <ul>
     *   <li>All digits: a registration number when there are seven and the first (gender/century) digit is 1–8.</li>
     *   <li>Partly masked by the document's author ("12*****", or "14****" as OCR may count it): two or more
     *       digits still visible, and a hyphen before it so arbitrary digit/star runs do not qualify.</li>
     *   <li>Conventionally masked ("1******", "*******"): nothing beyond the gender digit shows; left alone.</li>
     * </ul>
     */
    static boolean exposedBack(String back, boolean separated) {
        int digits = countDigits(back);
        if (digits == back.length()) {
            return digits == 7 && back.charAt(0) >= '1' && back.charAt(0) <= '8';
        }
        return digits >= 2 && separated;
    }

    private static boolean isFrontChar(char c) {
        return isDigit(c) || c == '*';
    }

    /**
     * When a separator ({@code -}, spaces) sits between the front's last digit and the back part, moves the
     * mask's left edge to the middle of it. The separator is a safe margin: the first back digit gets fully
     * covered even when its recognized position runs late, and the 6th digit is not touched.
     */
    private static Rectangle overSeparator(Rectangle mask, TextLine line, int lastFront, int firstBack) {
        if (lastFront < 0 || firstBack - lastFront < 2) {
            return mask;
        }
        int left = (int) Math.floor((line.charRight()[lastFront] + line.charLeft()[firstBack]) / 2);
        return left >= mask.x ? mask : new Rectangle(left, mask.y, mask.x + mask.width - left, mask.height);
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
                Matcher backOnly = BACK_ONLY_CELL.matcher(norm);
                if (backOnly.matches()) {
                    // Front masked and not even detected ("******" often yields no text box): the back stands alone.
                    Rectangle mask = charRect(cell, backOnly.start(1), backOnly.end(1) - 1);
                    out.add(new Finding(mask, Kind.COLUMN, backOnly.group(1), false));
                    continue;
                }
                if (digits < COLUMN_MIN_DIGITS) {
                    continue;
                }
                // Prefer the separator: a dropped front digit would otherwise leave the first back digit visible.
                int from = hyphen >= 0 && hyphen <= sixth + 1 ? hyphen + 1 : sixth + 1;
                while (!isDigit(norm.charAt(from))) {
                    from++;
                }
                int lastFront = from - 1;
                while (lastFront >= 0 && !isDigit(norm.charAt(lastFront))) {
                    lastFront--;
                }
                Rectangle mask = overSeparator(charRect(cell, from, last), cell, lastFront, from);
                out.add(new Finding(mask, Kind.COLUMN, norm.replaceAll("\\D", ""), from - lastFront == 1));
            }
        }
    }

    /**
     * Cells under {@code header}: centred from half a header width left of it to a full width right of it (numbers
     * are wider than a short Hangul header, and a back whose masked front went undetected sits further right),
     * stacked downward. The run stops
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
        double hi = hb.getMaxX() + hb.width;
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

    /**
     * Mask for characters {@code first..last}. The left edge is the boundary with the preceding character (for OCR
     * the midpoint between CTC character centres) with no padding: that character is usually the 6th front
     * digit, which must stay readable. The right edge is padded because recognition tends to clip the last
     * glyph of a box.
     */
    private static Rectangle charRect(TextLine line, int first, int last) {
        float avg = (float) line.box().width / Math.max(1, line.text().length());
        float rightPad = (line.source() == TextLine.Source.OCR ? 0.5f : 0.15f) * avg;
        int x0 = (int) Math.floor(line.charLeft()[first]);
        int x1 = (int) Math.ceil(line.charRight()[last] + rightPad);
        return new Rectangle(x0, line.box().y, x1 - x0, line.box().height);
    }

    /**
     * Whether an existing mask already accounts for {@code box}: their overlap is at least half of the smaller.
     * Mere intersection is not enough: on skewed scans the axis-aligned boxes of neighbouring rows overlap, and a
     * mask from the row above would otherwise suppress this row's number.
     */
    private static boolean covered(Rectangle box, List<Finding> findings) {
        double boxArea = (double) box.width * box.height;
        for (Finding f : findings) {
            Rectangle overlap = f.mask().intersection(box);
            if (!overlap.isEmpty() && (double) overlap.width * overlap.height
                    >= 0.5 * Math.min(boxArea, (double) f.mask().width * f.mask().height)) {
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
            case '*', '＊', '∗', '⁎', '٭', '✱', '✲', '✳', '✶', '✻', '●', '■', '□', '#', 'x', 'X', '×', '※', '•' -> '*';
            default -> c;
        };
    }
}
