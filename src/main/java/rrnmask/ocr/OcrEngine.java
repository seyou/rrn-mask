package rrnmask.ocr;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** PaddleOCR detection + recognition on ONNX Runtime. Thread-safe: ORT sessions allow concurrent runs. */
public final class OcrEngine implements AutoCloseable {

    public record Result(List<TextLine> lines, long detMillis, long recMillis) {}

    private final OrtSession.SessionOptions options;
    private final OrtSession det;
    private final OrtSession rec;
    private final TextDetector detector;
    private final TextRecognizer recognizer;

    /**
     * @param threads     ORT intra-op threads; 0 lets ORT decide
     * @param detMaxSide  longest side fed to the detector; larger pages are downscaled
     */
    public OcrEngine(Path detModel, Path recModel, int threads, int detMaxSide) throws OrtException, IOException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) {
            options.setIntraOpNumThreads(threads);
        }
        det = env.createSession(detModel.toString(), options);
        rec = env.createSession(recModel.toString(), options);
        detector = new TextDetector(env, det, detMaxSide);
        recognizer = new TextRecognizer(env, rec, OnnxMetadata.read(recModel, "character"));
    }

    public Result read(BufferedImage image) throws OrtException {
        BufferedImage rgb = Images.toRgb(image);
        long t0 = System.nanoTime();
        List<Rectangle> boxes = detector.detect(rgb);
        long t1 = System.nanoTime();
        List<TextLine> lines = recognizer.recognize(rgb, boxes);
        long t2 = System.nanoTime();
        return new Result(lines, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
    }

    @Override
    public void close() throws OrtException {
        det.close();
        rec.close();
        options.close();
    }
}
