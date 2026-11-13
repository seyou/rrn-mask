package rrnmask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Needs the ONNX models in {@code models/} (scripts/fetch-models.sh); skipped without them. */
class RrnMaskServiceTest {
    private static final Path MODELS = Path.of("models");
    private static final String FRONT = "900101-";
    private static final String NUMBER = "900101-1234567";

    private static RrnMaskService service;

    @BeforeAll
    static void open() throws IOException {
        assumeTrue(Files.isRegularFile(MODELS.resolve(Detector.V6_TINY.modelFile()))
                && Files.isRegularFile(MODELS.resolve(Detector.RECOGNIZER)), "models not fetched");
        service = new RrnMaskService(RrnMaskService.Options.builder(MODELS).build());
    }

    @AfterAll
    static void close() {
        if (service != null) {
            service.close();
        }
    }

    /** Records whether the service closed a stream it does not own. */
    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;

        TrackedInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /** A text PDF, one number per page. */
    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage p = new PDPage(PDRectangle.A4);
                doc.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Holder " + i + "  " + NUMBER);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void masksAnImageStreamAndReturnsEncodedBytes() throws IOException {
        BufferedImage img = new BufferedImage(1000, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1000, 200);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
        FontMetrics fm = g.getFontMetrics();
        int x = 100;
        int baseline = 120;
        g.drawString(NUMBER, x, baseline);
        int backStart = x + fm.stringWidth(FRONT);
        int backEnd = x + fm.stringWidth(NUMBER);
        int frontEnd = x + fm.stringWidth("900101");
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);

        TrackedInput in = new TrackedInput(png.toByteArray());
        RrnMaskService.Result result = service.redact(in);

        assertFalse(in.closed, "the caller owns the stream");
        assertEquals(Detector.V6_TINY, result.detector());
        assertEquals(1, result.pages().size());
        RrnMaskService.MaskedPage page = result.pages().getFirst();
        assertEquals("image/png", page.contentType());
        assertEquals(1, page.masks().size());
        RrnMaskService.Mask m = page.masks().getFirst();
        assertTrue(m.x() <= backStart && m.x() + m.width() >= backEnd, "covers the back digits: " + m);
        assertTrue(m.x() >= frontEnd - 1, "6th front digit stays visible: " + m);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(page.image()));
        assertEquals(1000, out.getWidth());
        int mid = (backStart + backEnd) / 2;
        assertEquals(0, out.getRGB(mid, baseline - fm.getAscent() / 3) & 0xffffff, "masked pixels are black");
    }

    @Test
    void streamsPdfPagesToTheSinkInOrder() throws IOException {
        List<Integer> seen = new ArrayList<>();
        RrnMaskService.Summary summary = service.redact(new ByteArrayInputStream(pdf(3)), page -> {
            seen.add(page.pageNumber());
            assertEquals(1, page.masks().size(), "page " + page.pageNumber());
            assertEquals(page.width(), ImageIO.read(new ByteArrayInputStream(page.image())).getWidth());
        });
        assertEquals(List.of(1, 2, 3), seen);
        assertEquals(new RrnMaskService.Summary(Detector.V6_TINY, 3, 3), summary);
    }

    @Test
    void sinkFailureStopsProcessing() throws IOException {
        List<Integer> seen = new ArrayList<>();
        IOException e = assertThrows(IOException.class, () -> service.redact(new ByteArrayInputStream(pdf(3)), page -> {
            seen.add(page.pageNumber());
            throw new IOException("storage down");
        }));
        assertEquals("storage down", e.getMessage());
        assertEquals(List.of(1), seen);
    }

    @Test
    void perCallDetectorAndJpegOutput() throws IOException {
        assumeTrue(Files.isRegularFile(MODELS.resolve(Detector.V5_MOBILE.modelFile())), "v5-mobile not fetched");
        try (RrnMaskService jpeg = new RrnMaskService(RrnMaskService.Options.builder(MODELS)
                .format(RrnMaskService.ImageFormat.JPEG).build())) {
            RrnMaskService.Result result = jpeg.redact(new ByteArrayInputStream(pdf(1)), Detector.V5_MOBILE);
            assertEquals(Detector.V5_MOBILE, result.detector());
            RrnMaskService.MaskedPage page = result.pages().getFirst();
            assertEquals("image/jpeg", page.contentType());
            assertEquals(1, page.masks().size());
            assertEquals(page.width(), ImageIO.read(new ByteArrayInputStream(page.image())).getWidth());
        }
    }

    @Test
    void rejectsInputThatIsNotADocument() {
        assertThrows(IOException.class, () -> service.redact(new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
        assertThrows(IOException.class, () -> service.redact(new ByteArrayInputStream(new byte[0])));
    }
}
