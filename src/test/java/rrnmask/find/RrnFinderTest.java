package rrnmask.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.List;
import org.junit.jupiter.api.Test;
import rrnmask.find.RrnFinder.Finding;
import rrnmask.find.RrnFinder.Kind;
import rrnmask.ocr.TextLine;

class RrnFinderTest {
    private static final int CHAR_W = 10;
    private static final int LINE_H = 20;

    private final RrnFinder finder = new RrnFinder();

    /** OCR line with every character {@value #CHAR_W}px wide starting at {@code x}. */
    private static TextLine line(int x, int y, String text) {
        int n = text.length();
        float[] left = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++) {
            left[i] = x + i * CHAR_W;
            right[i] = x + (i + 1) * CHAR_W;
        }
        return new TextLine(new Rectangle(x, y, n * CHAR_W, LINE_H), text, 1f, left, right, TextLine.Source.OCR);
    }

    @Test
    void masksOnlyTheBackSevenDigits() {
        // "홍길동 " occupies 0..40, front digits 40..100, '-' 100..110, back digits 110..180.
        List<Finding> found = finder.find(List.of(line(0, 0, "홍길동 900101-1234567")));
        assertEquals(1, found.size());
        Rectangle m = found.getFirst().mask();
        assertTrue(m.x >= 100 && m.x <= 110, "starts between the 6th digit and the back part: " + m);
        assertTrue(m.getMaxX() >= 180, "covers the last digit: " + m);
        assertEquals("9001011234567", found.getFirst().digits());
    }

    @Test
    void joinsANumberSplitAcrossTwoBoxesOnOneRow() {
        TextLine front = line(0, 0, "900101");
        TextLine back = line(80, 2, "1234567");
        List<Finding> found = finder.find(List.of(back, front));
        assertEquals(1, found.size());
        Rectangle m = found.getFirst().mask();
        assertTrue(m.x >= 60 && m.contains(back.box()), "covers the back box only: " + m);
    }

    @Test
    void joinsFrontAndBackColumnsSeparatedByCellPadding() {
        // 앞자리 | 뒷자리 columns: gap of 6 line heights, too wide for ordinary joining.
        TextLine front = line(0, 0, "900101");
        TextLine back = line(180, 0, "1234567");
        TextLine address = line(320, 0, "서울특별시 강남구 테헤란로 152");
        List<Finding> found = finder.find(List.of(front, back, address));
        assertEquals(1, found.size());
        assertTrue(found.getFirst().mask().contains(back.box().getCenterX(), back.box().getCenterY()));
        assertTrue(found.getFirst().mask().x >= 170, "front stays visible: " + found.getFirst().mask());
    }

    @Test
    void splitColumnsNeedSevenDigitsInTheNearestNextCell() {
        // A front-only cell whose neighbour is a name, with a 7-digit number further right.
        List<TextLine> lines = List.of(line(0, 0, "900101"), line(80, 0, "홍길동"), line(160, 0, "1234567"));
        assertTrue(finder.find(lines).isEmpty());
    }

    @Test
    void doesNotJoinAcrossRows() {
        assertTrue(finder.find(List.of(line(0, 0, "900101"), line(80, 100, "1234567"))).isEmpty());
    }

    @Test
    void foldsOcrLetterDigitConfusions() {
        List<Finding> found = finder.find(List.of(line(0, 0, "9OO1O1—l234S67")));
        assertEquals(1, found.size());
        assertEquals("9001011234567", found.getFirst().digits());
    }

    @Test
    void acceptsUnseparatedAndSpacedForms() {
        assertEquals(1, finder.find(List.of(line(0, 0, "9001012234567"))).size());
        assertEquals(1, finder.find(List.of(line(0, 0, "900101 - 2234567"))).size());
    }

    @Test
    void ignoresOtherNumbersAndAlreadyMaskedOnes() {
        List<TextLine> lines = List.of(
                line(0, 0, "010-1234-5678"),
                line(0, 40, "사업자등록번호 123-45-67890"),
                line(0, 80, "2026-11-13"),
                line(0, 120, "1,234,567주"),
                line(0, 160, "900101-1******"),
                line(0, 200, "900101-9234567"),
                line(0, 240, "19900101-1234567"));
        assertTrue(finder.find(lines).isEmpty());
    }

    @Test
    void columnFallbackMasksGarbledNumbersUnderARegistrationHeader() {
        TextLine header = line(100, 0, "주민등록번호");
        TextLine cell = line(100, 40, "9001011234");  // OCR dropped three digits
        List<Finding> found = finder.find(List.of(header, cell));
        assertEquals(1, found.size());
        Finding f = found.getFirst();
        assertEquals(Kind.COLUMN, f.kind());
        assertTrue(f.mask().x >= 150 && f.mask().x <= 160, "starts after the 6th digit: " + f.mask());
        assertTrue(f.mask().getMaxX() >= 200, "runs to the last digit: " + f.mask());
    }

    @Test
    void columnFallbackSkipsLabelsThatCarryTheirOwnValue() {
        TextLine label = line(100, 0, "법인등록번호: 110111-0123456");
        TextLine below = line(100, 40, "123-45-67890");
        assertTrue(finder.find(List.of(label, below)).isEmpty());
    }

    @Test
    void columnFallbackDoesNotDoubleMaskPatternHits() {
        TextLine header = line(100, 0, "주민(법인)등록번호");
        TextLine cell = line(100, 40, "900101-1234567");
        List<Finding> found = finder.find(List.of(header, cell));
        assertEquals(1, found.size());
        assertEquals(Kind.PATTERN, found.getFirst().kind());
    }

    @Test
    void columnStopsAtTheEndOfTheTable() {
        TextLine header = line(100, 0, "실명번호");
        TextLine cell = line(100, 40, "900101-12345");  // garbled: needs the column pass
        TextLine footer = line(80, 400, "문의:02-6779-8976");  // same x, far below the last row
        List<Finding> found = finder.find(List.of(header, cell, footer));
        assertEquals(1, found.size());
        assertTrue(found.getFirst().mask().intersects(cell.box()));
    }

    @Test
    void masksTheBackWhenOcrDroppedAFrontDigit() {
        List<Finding> found = finder.find(List.of(line(0, 0, "60117- 1610026")));
        assertEquals(1, found.size());
        assertTrue(found.getFirst().mask().x >= 50 && found.getFirst().mask().x <= 70, "" + found.getFirst().mask());
    }

    @Test
    void splitColumnsTolerateRepeatedCharsFromOverlappingBoxes() {
        // Overlapping detector boxes: the recognizer read the shared "5-" again at the start of the back box.
        TextLine front = line(0, 0, "571126");
        TextLine back = line(55, 0, "5-2666026");
        List<Finding> found = finder.find(List.of(front, back));
        assertEquals(1, found.size());
        assertTrue(found.getFirst().mask().x >= 65, "repeated prefix stays visible: " + found.getFirst().mask());
        assertEquals("5711262666026", found.getFirst().digits());
    }

    @Test
    void splitColumnsAcceptANameMergedIntoTheFrontCell() {
        TextLine front = line(0, 0, "강영민960616");
        TextLine back = line(200, 0, "6226010");
        List<Finding> found = finder.find(List.of(front, back));
        assertEquals(1, found.size());
        assertTrue(found.getFirst().mask().intersects(back.box()));
    }
}
