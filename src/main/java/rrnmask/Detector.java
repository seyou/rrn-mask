package rrnmask;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * PaddleOCR text-detection models, as fetched by {@code scripts/fetch-models.sh}. Recognition is always
 * {@link #RECOGNIZER}: PP-OCRv6 has no Korean recognizer.
 */
public enum Detector {
    V5_MOBILE("v5-mobile", "ch_PP-OCRv5_det_mobile.onnx"),
    V5_SERVER("v5-server", "ch_PP-OCRv5_det_server.onnx"),
    V6_TINY("v6-tiny", "PP-OCRv6_det_tiny.onnx"),
    V6_SMALL("v6-small", "PP-OCRv6_det_small.onnx"),
    V6_MEDIUM("v6-medium", "PP-OCRv6_det_medium.onnx");

    public static final String RECOGNIZER = "korean_PP-OCRv5_rec_mobile.onnx";

    private final String id;
    private final String modelFile;

    Detector(String id, String modelFile) {
        this.id = id;
        this.modelFile = modelFile;
    }

    /** Short name used on the command line, e.g. {@code v6-tiny}. */
    public String id() {
        return id;
    }

    public String modelFile() {
        return modelFile;
    }

    public static Detector fromId(String id) {
        for (Detector d : values()) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown detector " + id + "; one of "
                + Arrays.stream(values()).map(Detector::id).collect(Collectors.joining(", ")));
    }
}
