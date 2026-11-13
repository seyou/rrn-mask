package rrnmask.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import rrnmask.Detector;
import rrnmask.doc.Redactor;
import rrnmask.doc.Redactor.Page;
import rrnmask.find.RrnFinder.Finding;
import rrnmask.ocr.OcrEngine;
import rrnmask.ocr.TextLine;

/** Runs the redactor over a set of documents, per detector model, and scores it against truth files. */
public final class Bench {
    private static final Set<String> EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Bench() {}

    /** Per detector × group totals. */
    private static final class Agg {
        int files, pages, evaluated, targets, covered, partial, missed, falsePositives, safePages, errors;
        int keeps, clipped;
        double keepCoverage;
        long render, det, rec, pdfText, find, wall;
    }

    public static void run(String[] argv) throws Exception {
        Args a = new Args(argv, Set.of("debug"));
        Path out = Path.of(a.require("out"));
        Path models = Path.of(a.get("models", "models"));
        int dpi = a.getInt("dpi", 200);
        int maxSide = a.getInt("det-max-side", 4000);
        int threads = a.getInt("threads", 0);
        Set<String> sources = Set.of(a.get("sources", "ocr,pdftext").split(","));
        boolean debug = a.has("debug");
        List<Path> inputs = collect(a.positional);
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("no input documents (" + String.join(", ", EXTENSIONS) + ")");
        }
        List<String> dets = sources.contains("ocr") ? List.of(a.get("det", Detector.V6_TINY.id()).split(","))
                : List.of("none");

        Map<String, Map<String, Agg>> summary = new LinkedHashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (String det : dets) {
            OcrEngine engine = null;
            if (!det.equals("none")) {
                Detector d = Detector.fromId(det);
                engine = new OcrEngine(models.resolve(d.modelFile()), models.resolve(Detector.RECOGNIZER), threads,
                        maxSide);
                warmUp(engine);
            }
            Redactor redactor = new Redactor(new Redactor.Options(dpi, engine, sources.contains("pdftext")));
            Map<String, Agg> groups = summary.computeIfAbsent(det, k -> new TreeMap<>());
            for (Path input : inputs) {
                Truth truth = loadTruth(input);
                String group = truth != null && truth.group() != null ? truth.group() : "no-truth-" + extension(input);
                Agg agg = groups.computeIfAbsent(group, k -> new Agg());
                agg.files++;
                Path dir = out.resolve(det).resolve(input.toString().replaceAll("[/\\\\:]", "_"));
                List<Page> pages;
                long t0 = System.nanoTime();
                try {
                    pages = redactor.redact(Files.readAllBytes(input));
                } catch (Exception e) {
                    agg.errors++;
                    System.err.println("error " + input + ": " + e);
                    records.add(Map.of("det", det, "file", input.toString(), "error", e.toString()));
                    continue;
                }
                agg.wall += (System.nanoTime() - t0) / 1_000_000;
                Files.createDirectories(dir);
                for (Page page : pages) {
                    Evaluator.PageEval eval = truth == null ? null : Evaluator.evaluate(
                            truth.page(page.number()), page.image().getWidth(), page.image().getHeight(),
                            page.findings());
                    accumulate(agg, page, eval);
                    records.add(record(det, input, group, page, eval));
                    String stem = String.format(Locale.ROOT, "page-%03d", page.number());
                    ImageIO.write(page.masked(), "png", dir.resolve(stem + ".png").toFile());
                    if (debug) {
                        ImageIO.write(debugImage(page, eval), "png", dir.resolve(stem + ".debug.png").toFile());
                        Files.writeString(dir.resolve(stem + ".txt"), dump(page.lines()));
                    }
                }
                System.err.printf(Locale.ROOT, "%-9s %s: %d page(s), %d mask(s)%n", det, input, pages.size(),
                        pages.stream().mapToInt(p -> p.findings().size()).sum());
            }
            if (engine != null) {
                engine.close();
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("config", Map.of("dpi", dpi, "detMaxSide", maxSide, "threads", threads,
                "sources", String.join(",", sources), "recognizer", Detector.RECOGNIZER));
        report.put("summary", summaryJson(summary));
        report.put("pages", records);
        Files.createDirectories(out);
        JSON.writeValue(out.resolve("report.json").toFile(), report);
        System.out.println(table(summary));
        System.out.println("report: " + out.resolve("report.json"));
    }

    private static List<Path> collect(List<String> args) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            Path p = Path.of(arg);
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.filter(Files::isRegularFile).filter(f -> EXTENSIONS.contains(extension(f))).sorted()
                            .forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }
        return files;
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Truth loadTruth(Path input) throws IOException {
        Path t = input.resolveSibling(input.getFileName() + ".truth.json");
        return Files.exists(t) ? JSON.readValue(t.toFile(), Truth.class) : null;
    }

    /** First ORT runs allocate and optimise; keep that out of the timings. */
    private static void warmUp(OcrEngine engine) throws Exception {
        BufferedImage img = new BufferedImage(640, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 640, 160);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 28));
        g.drawString("900101-1234567", 40, 90);
        g.dispose();
        engine.read(img);
        engine.read(img);
    }

    private static void accumulate(Agg agg, Page page, Evaluator.PageEval eval) {
        agg.pages++;
        Redactor.Timings t = page.timings();
        agg.render += t.renderMillis();
        agg.det += t.detMillis();
        agg.rec += t.recMillis();
        agg.pdfText += t.pdfTextMillis();
        agg.find += t.findMillis();
        if (eval != null) {
            agg.evaluated++;
            agg.targets += eval.targets();
            agg.covered += eval.covered();
            agg.partial += eval.partial();
            agg.missed += eval.missed();
            agg.falsePositives += eval.falsePositives();
            agg.keeps += eval.keeps();
            agg.clipped += eval.clipped();
            agg.keepCoverage += eval.keepCoverage();
            if (eval.safe()) {
                agg.safePages++;
            }
        }
    }

    /** Masks and timings only; recognized text and digits stay out of the report. */
    private static Map<String, Object> record(String det, Path input, String group, Page page,
                                              Evaluator.PageEval eval) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("det", det);
        r.put("file", input.toString());
        r.put("group", group);
        r.put("page", page.number());
        r.put("size", List.of(page.image().getWidth(), page.image().getHeight()));
        r.put("timings", page.timings());
        r.put("lines", page.lines().size());
        List<Map<String, Object>> masks = new ArrayList<>();
        for (Finding f : page.findings()) {
            Rectangle m = f.mask();
            masks.add(Map.of("kind", f.kind().name(), "box", List.of(m.x, m.y, m.width, m.height)));
        }
        r.put("masks", masks);
        if (eval != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("targets", eval.targets());
            e.put("covered", eval.covered());
            e.put("partial", eval.partial());
            e.put("missed", eval.missed());
            e.put("falsePositives", eval.falsePositives());
            e.put("misses", eval.misses().stream().map(b -> List.of(b.x, b.y, b.width, b.height)).toList());
            e.put("keeps", eval.keeps());
            e.put("clipped", eval.clipped());
            e.put("clips", eval.clips().stream().map(b -> List.of(b.x, b.y, b.width, b.height)).toList());
            r.put("eval", e);
        }
        return r;
    }

    /**
     * Unmasked page with overlays: OCR boxes green, text-layer boxes blue, masks translucent red,
     * missed/partial targets magenta, clipped 6th front digits orange. Shows the digits under the masks, so it
     * holds PII.
     */
    private static BufferedImage debugImage(Page page, Evaluator.PageEval eval) {
        BufferedImage img = new BufferedImage(page.image().getWidth(), page.image().getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(page.image(), 0, 0, null);
        g.setStroke(new BasicStroke(1f));
        for (TextLine l : page.lines()) {
            g.setColor(l.source() == TextLine.Source.OCR ? new Color(0, 160, 0) : new Color(0, 90, 255));
            g.draw(l.box());
        }
        for (Finding f : page.findings()) {
            g.setColor(new Color(255, 0, 0, 80));
            g.fill(f.mask());
            g.setColor(Color.RED);
            g.draw(f.mask());
        }
        if (eval != null) {
            g.setStroke(new BasicStroke(3f));
            g.setColor(Color.MAGENTA);
            for (Rectangle m : eval.misses()) {
                g.draw(m);
            }
            g.setColor(Color.ORANGE);
            for (Rectangle c : eval.clips()) {
                g.draw(c);
            }
        }
        g.dispose();
        return img;
    }

    private static String dump(List<TextLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (TextLine l : lines) {
            Rectangle b = l.box();
            sb.append(String.format(Locale.ROOT, "%s\t%d,%d,%d,%d\t%.3f\t%s%n", l.source(), b.x, b.y, b.width,
                    b.height, l.score(), l.text()));
        }
        return sb.toString();
    }

    private static List<Map<String, Object>> summaryJson(Map<String, Map<String, Agg>> summary) {
        List<Map<String, Object>> rows = new ArrayList<>();
        summary.forEach((det, groups) -> groups.forEach((group, a) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("det", det);
            r.put("group", group);
            r.put("files", a.files);
            r.put("errors", a.errors);
            r.put("pages", a.pages);
            r.put("targets", a.targets);
            r.put("covered", a.covered);
            r.put("partial", a.partial);
            r.put("missed", a.missed);
            r.put("recall", a.targets == 0 ? null : (double) a.covered / a.targets);
            r.put("safePages", a.safePages);
            r.put("evaluatedPages", a.evaluated);
            r.put("falsePositives", a.falsePositives);
            r.put("frontDigits", a.keeps);
            r.put("frontClipped", a.clipped);
            r.put("frontCoverageMean", a.keeps == 0 ? null : a.keepCoverage / a.keeps);
            int n = Math.max(1, a.pages);
            r.put("msPerPage", Map.of("render", a.render / n, "det", a.det / n, "rec", a.rec / n,
                    "pdfText", a.pdfText / n, "find", a.find / n, "wall", a.wall / n));
            rows.add(r);
        }));
        return rows;
    }

    private static String table(Map<String, Map<String, Agg>> summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("| det | group | files | pages | targets | covered | partial | missed | recall | safe pages "
                + "| FP masks | front clipped | front cover | det ms | rec ms | wall ms/page |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        summary.forEach((det, groups) -> {
            Agg total = new Agg();
            groups.forEach((group, a) -> {
                row(sb, det, group, a);
                add(total, a);
            });
            if (groups.size() > 1) {
                row(sb, det, "**all**", total);
            }
        });
        return sb.toString();
    }

    private static void row(StringBuilder sb, String det, String group, Agg a) {
        int n = Math.max(1, a.pages);
        String recall = a.targets == 0 ? "-" : String.format(Locale.ROOT, "%.3f", (double) a.covered / a.targets);
        String safe = a.evaluated == 0 ? "-" : a.safePages + "/" + a.evaluated;
        String clipped = a.keeps == 0 ? "-" : a.clipped + "/" + a.keeps;
        String cover = a.keeps == 0 ? "-" : String.format(Locale.ROOT, "%.3f", a.keepCoverage / a.keeps);
        sb.append(String.format(Locale.ROOT,
                "| %s | %s | %d%s | %d | %d | %d | %d | %d | %s | %s | %d | %s | %s | %d | %d | %d |%n",
                det, group, a.files, a.errors > 0 ? " (" + a.errors + " err)" : "", a.pages, a.targets, a.covered,
                a.partial, a.missed, recall, safe, a.falsePositives, clipped, cover, a.det / n, a.rec / n, a.wall / n));
    }

    private static void add(Agg t, Agg a) {
        t.files += a.files;
        t.errors += a.errors;
        t.pages += a.pages;
        t.evaluated += a.evaluated;
        t.targets += a.targets;
        t.covered += a.covered;
        t.partial += a.partial;
        t.missed += a.missed;
        t.falsePositives += a.falsePositives;
        t.safePages += a.safePages;
        t.keeps += a.keeps;
        t.clipped += a.clipped;
        t.keepCoverage += a.keepCoverage;
        t.render += a.render;
        t.det += a.det;
        t.rec += a.rec;
        t.pdfText += a.pdfText;
        t.find += a.find;
        t.wall += a.wall;
    }
}
