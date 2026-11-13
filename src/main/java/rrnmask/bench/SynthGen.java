package rrnmask.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import rrnmask.bench.Truth.PageTruth;
import rrnmask.bench.Truth.Target;
import rrnmask.ocr.Images;

/**
 * Fake shareholder registers with known number positions. Templates vary columns, header wording, number
 * formatting (hyphen, spaced, none, already masked, front/back in separate columns), fonts, sizes and grid
 * lines, and include distractors (business number, phone, dates, share counts). Five renderings per seed:
 * clean PNG, scan-like JPEG (skew, blur, noise, compression), hard JPEG (more skew, half resolution, uneven
 * light, heavy compression), vector PDF with a text layer, and an image-only PDF of the scan.
 * All numbers are random; none belong to anyone.
 */
public final class SynthGen {
    private static final float PAGE_W = PDRectangle.A4.getWidth();
    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final int IMAGE_DPI = 200;
    /** System Korean fonts; usable for images even when PDFBox cannot embed them. */
    private static final List<String> SYSTEM_FONTS = List.of(
            "/System/Library/Fonts/Supplemental/AppleGothic.ttf",
            "/System/Library/Fonts/Supplemental/AppleMyungjo.ttf",
            "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
            "/usr/share/fonts/truetype/nanum/NanumMyeongjo.ttf");
    private static final String[] SURNAMES = {"김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서",
            "신", "권", "황", "안", "송", "류", "홍"};
    private static final String[] GIVEN = {"민", "서", "지", "현", "우", "준", "영", "수", "하", "윤", "도", "은", "재",
            "연", "진", "호", "경", "혜", "태", "성"};
    private static final String[] COMPANIES = {"(주)한빛테크", "주식회사 누리소프트", "(주)새벽바이오", "주식회사 다온물산",
            "(주)온결파트너스", "주식회사 바른에너지"};
    private static final String[] CORP_HOLDERS = {"(주)가람홀딩스", "주식회사 미래투자", "(주)솔빛벤처스", "한결인베스트먼트(주)",
            "주식회사 도담캐피탈"};
    private static final String[] ADDRESSES = {"서울특별시 강남구 테헤란로 152", "서울특별시 마포구 월드컵북로 21",
            "경기도 성남시 분당구 판교역로 235", "부산광역시 해운대구 센텀중앙로 79", "인천광역시 연수구 송도과학로 32",
            "대전광역시 유성구 대학로 99", "서울특별시 종로구 세종대로 209", "경기도 수원시 영통구 광교로 156"};
    private static final String[] RRN_LABELS = {"주민등록번호", "주민(법인)등록번호", "주민번호", "실명번호", "등록번호"};
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    /** What a span of a run's text is for scoring. KEEP is the 6th front digit, which must stay readable. */
    private enum Role { TARGET, OPTIONAL, KEEP }

    /** {@code start..end} of the run's text, scored as {@code role}. */
    private record Span(int start, int end, Role role) {}

    private record Run(String text, float x, float baseline, float size, List<Span> spans) {
        Run(String text, float x, float baseline, float size) {
            this(text, x, baseline, size, List.of());
        }
    }

    private record Rule(float x0, float y0, float x1, float y1) {}

    private record Layout(List<Run> runs, List<Rule> rules) {}

    /** Region as fractions of the page. */
    private record Region(double x, double y, double w, double h, Role role) {}

    private SynthGen() {}

    public static void run(String[] argv) throws Exception {
        Args a = new Args(argv, Set.of());
        Path out = Path.of(a.require("out"));
        int count = a.getInt("count", 10);
        Random rnd = new Random(Long.parseLong(a.get("seed", "1")));
        List<String> fontPaths = a.all("font").isEmpty() ? defaultFonts(Path.of(a.get("fonts", "fonts"))) : a.all("font");
        if (fontPaths.isEmpty()) {
            throw new IllegalArgumentException("no Korean TTF found; run scripts/fetch-fonts.sh or pass --font");
        }
        List<Font> fonts = new ArrayList<>();
        List<Integer> pdfFonts = new ArrayList<>();
        for (int i = 0; i < fontPaths.size(); i++) {
            fonts.add(loadFont(fontPaths.get(i)));
            if (pdfEmbeddable(new File(fontPaths.get(i)))) {
                pdfFonts.add(i);
            } else {
                System.err.println("PDFBox cannot embed " + fontPaths.get(i) + "; using it for images only");
            }
        }
        if (pdfFonts.isEmpty()) {
            throw new IllegalArgumentException("no font PDFBox can embed; pass --font /path/to/font.ttf");
        }
        Files.createDirectories(out);
        ObjectMapper json = new ObjectMapper();
        for (int i = 1; i <= count; i++) {
            for (String kind : List.of("png-clean", "jpg-scan", "jpg-hard", "pdf-text", "pdf-scan")) {
                int f = kind.equals("pdf-text") ? pdfFonts.get(rnd.nextInt(pdfFonts.size())) : rnd.nextInt(fonts.size());
                Layout layout = layout(rnd, fonts.get(f));
                List<Region> regions = new ArrayList<>();
                byte[] bytes;
                String ext;
                switch (kind) {
                    case "png-clean" -> {
                        bytes = png(renderImage(layout, fonts.get(f), regions));
                        ext = "png";
                    }
                    case "jpg-scan" -> {
                        Scan scan = SCAN;
                        bytes = jpeg(degrade(renderImage(layout, fonts.get(f), regions), regions, rnd, scan),
                                scan.jpegQuality());
                        ext = "jpg";
                    }
                    case "jpg-hard" -> {
                        Scan scan = HARD;
                        bytes = jpeg(degrade(renderImage(layout, fonts.get(f), regions), regions, rnd, scan),
                                scan.jpegQuality());
                        ext = "jpg";
                    }
                    case "pdf-text" -> {
                        bytes = renderPdf(layout, new File(fontPaths.get(f)), regions);
                        ext = "pdf";
                    }
                    default -> {
                        byte[] scan = jpeg(degrade(renderImage(layout, fonts.get(f), regions), regions, rnd, SCAN),
                                SCAN.jpegQuality());
                        bytes = imagePdf(scan);
                        ext = "pdf";
                    }
                }
                Path file = out.resolve(String.format(Locale.ROOT, "%s-%03d.%s", kind, i, ext));
                Files.write(file, bytes);
                List<Target> targets = new ArrayList<>();
                List<Target> optional = new ArrayList<>();
                List<Target> keep = new ArrayList<>();
                for (Region r : regions) {
                    List<Target> list = switch (r.role()) {
                        case TARGET -> targets;
                        case OPTIONAL -> optional;
                        case KEEP -> keep;
                    };
                    list.add(new Target(null, new double[] {r.x(), r.y(), r.w(), r.h()}));
                }
                json.writeValue(file.resolveSibling(file.getFileName() + ".truth.json").toFile(),
                        new Truth(kind, List.of(new PageTruth(1, targets, optional, keep))));
            }
        }
        System.out.println("wrote " + count * 5 + " documents to " + out);
    }

    private static List<String> defaultFonts(Path dir) throws IOException {
        List<String> paths = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                files.map(Path::toString).filter(p -> p.toLowerCase(Locale.ROOT).endsWith(".ttf")).sorted()
                        .forEach(paths::add);
            }
        }
        SYSTEM_FONTS.stream().filter(p -> new File(p).exists()).forEach(paths::add);
        return paths;
    }

    private static Font loadFont(String path) throws IOException, FontFormatException {
        return Font.createFont(Font.TRUETYPE_FONT, new File(path));
    }

    private static boolean pdfEmbeddable(File font) {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font.load(doc, font);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static float width(Font font, String s, float size) {
        return (float) font.deriveFont(size).getStringBounds(s, FRC).getWidth();
    }

    private static Layout layout(Random r, Font font) {
        List<Run> runs = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        float margin = 48;
        float y = 72;
        String title = r.nextBoolean() ? "주 주 명 부" : "주주명부";
        float ts = 18 + r.nextInt(5);
        runs.add(new Run(title, (PAGE_W - width(font, title, ts)) / 2, y, ts));
        float s = 8.5f + r.nextFloat() * 2.5f;
        y += ts * 1.8f;
        String company = pick(r, COMPANIES);
        runs.add(new Run("회사명: " + company, margin, y, s));
        String date = String.format(Locale.ROOT, "기준일: 2026년 %d월 %d일", 1 + r.nextInt(12), 1 + r.nextInt(28));
        runs.add(new Run(date, PAGE_W - margin - width(font, date, s), y, s));
        y += s * 1.8f;
        runs.add(new Run("사업자등록번호: " + digits(r, 3) + "-" + digits(r, 2) + "-" + digits(r, 5), margin, y, s));
        String corpLabel = "법인등록번호: ";
        String[] own = corpNumber(r);
        String corpLine = corpLabel + own[0] + "-" + own[1];
        runs.add(new Run(corpLine, PAGE_W - margin - width(font, corpLine, s), y, s, List.of(
                new Span(corpLabel.length() + 5, corpLabel.length() + 6, Role.KEEP),
                new Span(corpLabel.length() + 7, corpLine.length(), Role.OPTIONAL))));
        y += s * 2.6f;

        int variant = r.nextInt(3);
        String rrnLabel = pick(r, RRN_LABELS);
        String[] headers = switch (variant) {
            case 0 -> new String[] {"번호", "주주명", rrnLabel, "주소", "주식수", "지분율"};
            case 1 -> new String[] {"No", "성명", rrnLabel, "주식종류", "주식수", "취득일", "비고"};
            default -> new String[] {"번호", "주주명", "주민번호 앞자리", "뒷자리", "주소", "소유주식수"};
        };
        String sep = switch (r.nextInt(10)) {
            case 0 -> " - ";
            case 1 -> "";
            default -> "-";
        };
        float rowH = s * 2.1f;
        int rows = Math.min(3 + r.nextInt(20), (int) ((PAGE_H - y - 140) / rowH) - 1);
        List<Run[]> cells = new ArrayList<>();  // text + target range, positions filled in later
        for (int i = 0; i < rows; i++) {
            boolean corp = r.nextFloat() < 0.2f;
            String name = corp ? pick(r, CORP_HOLDERS) : personName(r);
            String[] num = corp ? corpNumber(r) : residentNumber(r);
            boolean masked = !corp && r.nextFloat() < 0.1f;
            String back = masked ? num[1].charAt(0) + "******" : num[1];
            String shares = String.format(Locale.ROOT, "%,d", 100 + r.nextInt(2_000_000));
            Run[] row = switch (variant) {
                case 0 -> new Run[] {cell(Integer.toString(i + 1)), cell(name),
                        numberCell(num[0] + sep + back, 6, num[0].length() + sep.length(), masked, corp),
                        cell(pick(r, ADDRESSES)), cell(shares),
                        cell(String.format(Locale.ROOT, "%.2f%%", r.nextFloat() * 40))};
                case 1 -> new Run[] {cell(Integer.toString(i + 1)), cell(name),
                        numberCell(num[0] + sep + back, 6, num[0].length() + sep.length(), masked, corp),
                        cell(r.nextFloat() < 0.8f ? "보통주" : "우선주"), cell(shares),
                        cell(String.format(Locale.ROOT, "20%02d-%02d-%02d", r.nextInt(26), 1 + r.nextInt(12),
                                1 + r.nextInt(28))),
                        cell(r.nextFloat() < 0.15f ? "대표이사" : "")};
                default -> new Run[] {cell(Integer.toString(i + 1)), cell(name),
                        new Run(num[0], 0, 0, 0, List.of(new Span(5, 6, Role.KEEP))),
                        numberCell(back, 0, 0, masked, corp), cell(pick(r, ADDRESSES)), cell(shares)};
            };
            cells.add(row);
        }

        float pad = 6;
        float[] widths = columnWidths(font, headers, cells, s, pad);
        float total = sum(widths);
        float avail = PAGE_W - 2 * margin;
        if (total > avail) {
            s *= avail / total;
            rowH = s * 2.1f;
            pad *= avail / total;
            widths = columnWidths(font, headers, cells, s, pad);
            total = sum(widths);
        }
        float left = margin + (avail - total) / 2;
        float[] colX = new float[widths.length + 1];
        colX[0] = left;
        for (int c = 0; c < widths.length; c++) {
            colX[c + 1] = colX[c] + widths[c];
        }
        float top = y;
        for (int c = 0; c < headers.length; c++) {
            runs.add(new Run(headers[c], colX[c] + pad, top + rowH * 0.68f, s));
        }
        for (int i = 0; i < cells.size(); i++) {
            float rowTop = top + rowH * (i + 1);
            Run[] row = cells.get(i);
            for (int c = 0; c < row.length; c++) {
                Run cell = row[c];
                if (!cell.text().isEmpty()) {
                    runs.add(new Run(cell.text(), colX[c] + pad, rowTop + rowH * 0.68f, s, cell.spans()));
                }
            }
        }
        float bottom = top + rowH * (cells.size() + 1);
        if (r.nextFloat() < 0.6f) {
            for (int i = 0; i <= cells.size() + 1; i++) {
                rules.add(new Rule(colX[0], top + rowH * i, colX[widths.length], top + rowH * i));
            }
            for (float x : colX) {
                rules.add(new Rule(x, top, x, bottom));
            }
        } else {
            rules.add(new Rule(colX[0], top, colX[widths.length], top));
            rules.add(new Rule(colX[0], top + rowH, colX[widths.length], top + rowH));
            rules.add(new Rule(colX[0], bottom, colX[widths.length], bottom));
        }

        y = bottom + s * 3;
        runs.add(new Run("위 주주명부는 사실과 다름없음을 확인합니다.", margin, y, s));
        y += s * 2;
        runs.add(new Run(String.format(Locale.ROOT, "2026년 %d월 %d일", 1 + r.nextInt(12), 1 + r.nextInt(28)),
                margin, y, s));
        y += s * 2;
        runs.add(new Run(company + " 대표이사 " + personName(r) + " (인)", margin, y, s));
        y += s * 2;
        runs.add(new Run("문의: 02-" + digits(r, 4) + "-" + digits(r, 4), margin, y, s));
        return new Layout(runs, rules);
    }

    private static Run cell(String text) {
        return new Run(text, 0, 0, 0);
    }

    /**
     * Back seven digits are a target (optional for corporate numbers); an already masked number is neither.
     * The front digit just before {@code frontEnd} must stay visible ({@code frontEnd} 0: front in another cell).
     */
    private static Run numberCell(String text, int frontEnd, int backStart, boolean masked, boolean corp) {
        List<Span> spans = new ArrayList<>();
        if (frontEnd > 0) {
            spans.add(new Span(frontEnd - 1, frontEnd, Role.KEEP));
        }
        if (!masked) {
            spans.add(new Span(backStart, backStart + 7, corp ? Role.OPTIONAL : Role.TARGET));
        }
        return new Run(text, 0, 0, 0, spans);
    }

    private static float[] columnWidths(Font font, String[] headers, List<Run[]> cells, float s, float pad) {
        float[] w = new float[headers.length];
        for (int c = 0; c < headers.length; c++) {
            w[c] = width(font, headers[c], s);
            for (Run[] row : cells) {
                w[c] = Math.max(w[c], width(font, row[c].text(), s));
            }
            w[c] += 2 * pad;
        }
        return w;
    }

    private static float sum(float[] v) {
        float t = 0;
        for (float x : v) {
            t += x;
        }
        return t;
    }

    private static BufferedImage renderImage(Layout layout, Font font, List<Region> regions) {
        float sc = IMAGE_DPI / 72f;
        int w = Math.round(PAGE_W * sc);
        int h = Math.round(PAGE_H * sc);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(new Color(40, 40, 40));
        g.setStroke(new BasicStroke(0.8f * sc));
        for (Rule rule : layout.rules()) {
            g.draw(new Line2D.Float(rule.x0() * sc, rule.y0() * sc, rule.x1() * sc, rule.y1() * sc));
        }
        g.setColor(Color.BLACK);
        for (Run run : layout.runs()) {
            Font f = font.deriveFont(run.size() * sc);
            g.setFont(f);
            g.drawString(run.text(), run.x() * sc, run.baseline() * sc);
            FontRenderContext frc = g.getFontRenderContext();
            for (Span span : run.spans()) {
                double x0 = run.x() * sc + f.getStringBounds(run.text().substring(0, span.start()), frc).getWidth();
                double x1 = run.x() * sc + f.getStringBounds(run.text().substring(0, span.end()), frc).getWidth();
                double top = (run.baseline() - 0.72 * run.size()) * sc;
                double bottom = (run.baseline() + 0.02 * run.size()) * sc;
                regions.add(new Region(x0 / w, top / h, (x1 - x0) / w, (bottom - top) / h, span.role()));
            }
        }
        g.dispose();
        return img;
    }

    /**
     * @param maxAngle max skew in degrees
     * @param lowRes   fraction of resolution kept (downsampled then upsampled back, like a fax or a far photo)
     * @param noise    grey-level noise sigma
     * @param shade    darkening toward one corner (uneven phone-photo lighting), 0..1
     */
    private record Scan(double maxAngle, double lowRes, double noise, double shade, float jpegQuality) {}

    private static final Scan SCAN = new Scan(0.8, 1.0, 10, 0, 0.5f);
    private static final Scan HARD = new Scan(2.0, 0.55, 18, 0.3, 0.3f);

    /** Scan look: skew, blur, resolution loss, shading, noise. Regions become the bounds of their skewed corners. */
    private static BufferedImage degrade(BufferedImage src, List<Region> regions, Random r, Scan scan) {
        int w = src.getWidth();
        int h = src.getHeight();
        double angle = Math.toRadians((r.nextDouble() * 2 - 1) * scan.maxAngle());
        AffineTransform rot = AffineTransform.getRotateInstance(angle, w / 2.0, h / 2.0);
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, rot, null);
        g.dispose();
        for (int i = 0; i < regions.size(); i++) {
            Region reg = regions.get(i);
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] c : new double[][] {{reg.x(), reg.y()}, {reg.x() + reg.w(), reg.y()},
                    {reg.x(), reg.y() + reg.h()}, {reg.x() + reg.w(), reg.y() + reg.h()}}) {
                Point2D p = rot.transform(new Point2D.Double(c[0] * w, c[1] * h), null);
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
            }
            regions.set(i, new Region(minX / w, minY / h, (maxX - minX) / w, (maxY - minY) / h, reg.role()));
        }
        if (scan.lowRes() < 1) {
            BufferedImage small = Images.resize(dst, (int) (w * scan.lowRes()), (int) (h * scan.lowRes()));
            dst = Images.resize(small, w, h);
        }
        float k = 1f / 9;
        BufferedImage blurred = new ConvolveOp(new Kernel(3, 3, new float[] {k, k, k, k, k, k, k, k, k}),
                ConvolveOp.EDGE_NO_OP, null).filter(dst, null);
        int[] px = blurred.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            double light = 1 - scan.shade() * ((double) (i % w) / w + (double) (i / w) / h) / 2;
            int n = (int) Math.round(r.nextGaussian() * scan.noise());
            int v = Math.max(0, Math.min(255, (int) ((px[i] & 0xff) * light) + n));
            px[i] = (v << 16) | (v << 8) | v;
        }
        blurred.setRGB(0, 0, w, h, px, 0, w);
        return blurred;
    }

    private static byte[] renderPdf(Layout layout, File fontFile, List<Region> regions) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, fontFile);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.8f);
                cs.setStrokingColor(new Color(40, 40, 40));
                for (Rule rule : layout.rules()) {
                    cs.moveTo(rule.x0(), PAGE_H - rule.y0());
                    cs.lineTo(rule.x1(), PAGE_H - rule.y1());
                    cs.stroke();
                }
                for (Run run : layout.runs()) {
                    cs.beginText();
                    cs.setFont(font, run.size());
                    cs.newLineAtOffset(run.x(), PAGE_H - run.baseline());
                    cs.showText(run.text());
                    cs.endText();
                    for (Span span : run.spans()) {
                        double x0 = run.x() + font.getStringWidth(run.text().substring(0, span.start())) / 1000 * run.size();
                        double x1 = run.x() + font.getStringWidth(run.text().substring(0, span.end())) / 1000 * run.size();
                        double top = run.baseline() - 0.72 * run.size();
                        double bottom = run.baseline() + 0.02 * run.size();
                        regions.add(new Region(x0 / PAGE_W, top / PAGE_H, (x1 - x0) / PAGE_W, (bottom - top) / PAGE_H,
                                span.role()));
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] imagePdf(byte[] jpeg) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject img = JPEGFactory.createFromByteArray(doc, jpeg);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 0, 0, PAGE_W, PAGE_H);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] png(BufferedImage img) throws IOException {
        return Images.encode(img, "png", 1f);
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        return Images.encode(img, "jpeg", quality);
    }

    private static String personName(Random r) {
        return pick(r, SURNAMES) + pick(r, GIVEN) + pick(r, GIVEN);
    }

    /** Birth date + gender digit (1–4 citizens, 5–8 foreigners) + six random digits. */
    private static String[] residentNumber(Random r) {
        int year = 1950 + r.nextInt(56);
        String front = String.format(Locale.ROOT, "%02d%02d%02d", year % 100, 1 + r.nextInt(12), 1 + r.nextInt(28));
        int gender = (year >= 2000 ? 3 : 1) + r.nextInt(2) + (r.nextFloat() < 0.1f ? 4 : 0);
        return new String[] {front, gender + digits(r, 6)};
    }

    /** Registry office (4) + kind (2) - serial (6) + check (1). The back part may start with 0. */
    private static String[] corpNumber(Random r) {
        String[] offices = {"1101", "1311", "1301", "1801", "2841"};
        String[] kinds = {"11", "13", "71"};
        return new String[] {pick(r, offices) + pick(r, kinds), digits(r, 7)};
    }

    private static String digits(Random r, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('0' + r.nextInt(10)));
        }
        return sb.toString();
    }

    private static String pick(Random r, String[] values) {
        return values[r.nextInt(values.length)];
    }
}
