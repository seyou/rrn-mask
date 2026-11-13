package rrnmask;

import ai.onnxruntime.OrtException;
import java.awt.Rectangle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import rrnmask.doc.Redactor;
import rrnmask.find.RrnFinder.Finding;
import rrnmask.ocr.Images;
import rrnmask.ocr.OcrEngine;

/**
 * Masks the back seven digits of resident registration numbers in an image or PDF read from an
 * {@link InputStream} and hands back each masked page as encoded image bytes. Nothing touches disk.
 *
 * <pre>{@code
 * RrnMaskService service = new RrnMaskService(RrnMaskService.Options.builder(Path.of("models")).build());
 *
 * // Streaming: each page goes to the sink as soon as it is masked; only one page's rasters are in memory.
 * try (InputStream upload = multipartFile.getInputStream()) {
 *     service.redact(upload, page -> storage.put(page.pageNumber(), page.image(), page.contentType()));
 * }
 *
 * // Small documents: collect every page; the detector can be chosen per call.
 * try (InputStream upload = multipartFile.getInputStream()) {
 *     RrnMaskService.Result result = service.redact(upload, Detector.V6_SMALL);
 * }
 * }</pre>
 *
 * <p>The input is not consumed incrementally: PDFs need random access and multi-page TIFFs a frame index, so the
 * upload is buffered in memory while it is processed. Limit upload size before calling. Streams are never closed
 * here; the caller owns them.
 *
 * <p>Thread-safe; build one instance and share it. Each detector's ONNX sessions load once: the default
 * detector at construction, others on first use. Masks are a proposal: OCR can miss a number, so a person
 * should confirm the result before the original is discarded.
 */
public final class RrnMaskService implements AutoCloseable {

    public enum ImageFormat {
        PNG("png", "image/png"),
        JPEG("jpeg", "image/jpeg");

        private final String imageIoName;
        private final String contentType;

        ImageFormat(String imageIoName, String contentType) {
            this.imageIoName = imageIoName;
            this.contentType = contentType;
        }

        public String contentType() {
            return contentType;
        }
    }

    /** One masked rectangle in page-image pixels. {@code kind} is {@code PATTERN} or {@code COLUMN}. */
    public record Mask(int x, int y, int width, int height, String kind) {}

    /**
     * One page, masked and encoded. PDFs give one entry per page rendered at {@link Options#dpi()}; multi-page
     * TIFFs one per frame; other images a single page. No recognized text or digits are returned.
     */
    public record MaskedPage(int pageNumber, int width, int height, byte[] image, String contentType,
                             List<Mask> masks) {}

    public record Result(Detector detector, List<MaskedPage> pages) {
        public int maskCount() {
            return pages.stream().mapToInt(p -> p.masks().size()).sum();
        }
    }

    /** What a streaming call processed; the pages themselves went to the {@link PageSink}. */
    public record Summary(Detector detector, int pages, int masks) {}

    /** Receives masked pages in page order, one at a time. An exception stops processing and propagates. */
    @FunctionalInterface
    public interface PageSink {
        void accept(MaskedPage page) throws IOException;
    }

    /** Settings fixed for the life of the service. Only the detector can also be chosen per call. */
    public static final class Options {
        private final Path modelDir;
        private final Detector detector;
        private final int dpi;
        private final int threads;
        private final int detMaxSide;
        private final boolean pdfTextLayer;
        private final ImageFormat format;
        private final float jpegQuality;

        private Options(Builder b) {
            modelDir = b.modelDir;
            detector = b.detector;
            dpi = b.dpi;
            threads = b.threads;
            detMaxSide = b.detMaxSide;
            pdfTextLayer = b.pdfTextLayer;
            format = b.format;
            jpegQuality = b.jpegQuality;
        }

        /** @param modelDir directory holding the ONNX files from {@code scripts/fetch-models.sh} */
        public static Builder builder(Path modelDir) {
            return new Builder(modelDir);
        }

        public Path modelDir() {
            return modelDir;
        }

        public Detector detector() {
            return detector;
        }

        public int dpi() {
            return dpi;
        }

        public int threads() {
            return threads;
        }

        public int detMaxSide() {
            return detMaxSide;
        }

        public boolean pdfTextLayer() {
            return pdfTextLayer;
        }

        public ImageFormat format() {
            return format;
        }

        public float jpegQuality() {
            return jpegQuality;
        }

        public static final class Builder {
            private final Path modelDir;
            private Detector detector = Detector.V6_TINY;
            private int dpi = 200;
            private int threads = 0;
            private int detMaxSide = 4000;
            private boolean pdfTextLayer = true;
            private ImageFormat format = ImageFormat.PNG;
            private float jpegQuality = 0.9f;

            private Builder(Path modelDir) {
                this.modelDir = Objects.requireNonNull(modelDir, "modelDir");
            }

            /** Default detector for {@link #redact(byte[])}. Default {@link Detector#V6_TINY}. */
            public Builder detector(Detector detector) {
                this.detector = Objects.requireNonNull(detector, "detector");
                return this;
            }

            /** PDF render resolution. Default 200. */
            public Builder dpi(int dpi) {
                if (dpi < 72 || dpi > 600) {
                    throw new IllegalArgumentException("dpi must be 72..600: " + dpi);
                }
                this.dpi = dpi;
                return this;
            }

            /** ONNX Runtime intra-op threads per inference; 0 lets the runtime decide. Default 0. */
            public Builder threads(int threads) {
                if (threads < 0) {
                    throw new IllegalArgumentException("threads must be >= 0: " + threads);
                }
                this.threads = threads;
                return this;
            }

            /** Longest side fed to the detector; larger pages are downscaled for detection only. Default 4000. */
            public Builder detMaxSide(int detMaxSide) {
                if (detMaxSide < 64) {
                    throw new IllegalArgumentException("detMaxSide must be >= 64: " + detMaxSide);
                }
                this.detMaxSide = detMaxSide;
                return this;
            }

            /** Also use text embedded in PDFs (exact positions). Default true. */
            public Builder pdfTextLayer(boolean pdfTextLayer) {
                this.pdfTextLayer = pdfTextLayer;
                return this;
            }

            /** Output encoding. Default PNG (lossless, so box edges stay exact). */
            public Builder format(ImageFormat format) {
                this.format = Objects.requireNonNull(format, "format");
                return this;
            }

            /** JPEG quality 0..1, used only with {@link ImageFormat#JPEG}. Default 0.9. */
            public Builder jpegQuality(float jpegQuality) {
                if (jpegQuality <= 0 || jpegQuality > 1) {
                    throw new IllegalArgumentException("jpegQuality must be in (0, 1]: " + jpegQuality);
                }
                this.jpegQuality = jpegQuality;
                return this;
            }

            public Options build() {
                return new Options(this);
            }
        }
    }

    private final Options options;
    private final Map<Detector, OcrEngine> engines = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * Loads the default detector and the recognizer.
     *
     * @throws IOException when a model file is missing or unreadable
     */
    public RrnMaskService(Options options) throws IOException {
        this.options = Objects.requireNonNull(options, "options");
        engine(options.detector());
    }

    public Options options() {
        return options;
    }

    /** Masks {@code document} with the default detector and returns every page. */
    public Result redact(InputStream document) throws IOException {
        return redact(document, options.detector());
    }

    /** Masks {@code document} with {@code detector} and returns every page. */
    public Result redact(InputStream document, Detector detector) throws IOException {
        List<MaskedPage> pages = new ArrayList<>();
        redact(document, detector, pages::add);
        return new Result(detector, List.copyOf(pages));
    }

    /** Masks {@code document} with the default detector, handing each page to {@code sink} as it is done. */
    public Summary redact(InputStream document, PageSink sink) throws IOException {
        return redact(document, options.detector(), sink);
    }

    /**
     * Masks {@code document} (PDF, PNG, JPEG, BMP, GIF or TIFF) with {@code detector}, handing each page to
     * {@code sink} as soon as it is masked and encoded. The detector's models load on first use and stay loaded.
     *
     * @throws IOException when the input is empty or not a readable image or PDF, the detector's model is
     *                     missing, or {@code sink} fails
     */
    public Summary redact(InputStream document, Detector detector, PageSink sink) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(sink, "sink");
        Redactor redactor = new Redactor(new Redactor.Options(options.dpi(), engine(detector),
                options.pdfTextLayer()));
        int[] counts = new int[2];  // pages, masks
        try {
            redactor.redact(document, page -> {
                List<Mask> masks = new ArrayList<>(page.findings().size());
                for (Finding f : page.findings()) {
                    Rectangle r = f.mask();
                    masks.add(new Mask(r.x, r.y, r.width, r.height, f.kind().name()));
                }
                byte[] image = Images.encode(page.masked(), options.format().imageIoName, options.jpegQuality());
                sink.accept(new MaskedPage(page.number(), page.masked().getWidth(), page.masked().getHeight(),
                        image, options.format().contentType(), List.copyOf(masks)));
                counts[0]++;
                counts[1] += masks.size();
            });
        } catch (OrtException e) {
            throw new IllegalStateException("OCR inference failed", e);
        }
        return new Summary(detector, counts[0], counts[1]);
    }

    private OcrEngine engine(Detector detector) throws IOException {
        if (closed) {
            throw new IllegalStateException("service is closed");
        }
        OcrEngine engine = engines.get(detector);
        if (engine != null) {
            return engine;
        }
        synchronized (engines) {
            engine = engines.get(detector);
            if (engine == null) {
                Path det = options.modelDir().resolve(detector.modelFile());
                Path rec = options.modelDir().resolve(Detector.RECOGNIZER);
                for (Path p : List.of(det, rec)) {
                    if (!Files.isRegularFile(p)) {
                        throw new IOException("model not found: " + p + " (run scripts/fetch-models.sh)");
                    }
                }
                try {
                    engine = new OcrEngine(det, rec, options.threads(), options.detMaxSide());
                } catch (OrtException e) {
                    throw new IOException("cannot load " + detector.id() + " models from " + options.modelDir(), e);
                }
                engines.put(detector, engine);
            }
            return engine;
        }
    }

    /** Releases the ONNX sessions. Call only once no {@code redact} call is in flight. */
    @Override
    public void close() {
        closed = true;
        synchronized (engines) {
            for (OcrEngine engine : engines.values()) {
                try {
                    engine.close();
                } catch (OrtException e) {
                    // Closing native sessions; nothing to recover.
                }
            }
            engines.clear();
        }
    }
}
