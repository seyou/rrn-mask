package rrnmask.ocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * PP-OCR DB text detection. Pre/post-processing follows PaddleOCR 3.x defaults (ImageNet normalisation on
 * BGR, thresh 0.3, unclip 1.5) except box_thresh 0.5 instead of 0.6, RapidOCR's value: faint text (light scans,
 * runs of asterisks) scores lower, and on the synthetic set 0.5 found more numbers with no new false masks.
 * Boxes are axis-aligned: connected components of the probability bitmap stand in for cv2 contours,
 * which is exact for upright documents and loose for skewed ones.
 */
final class TextDetector {
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    private static final float THRESH = 0.3f;
    private static final float BOX_THRESH = 0.5f;
    private static final double UNCLIP_RATIO = 1.5;
    private static final int MIN_SIDE = 64;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final int maxSide;

    TextDetector(OrtEnvironment env, OrtSession session, int maxSide) {
        this.env = env;
        this.session = session;
        this.inputName = session.getInputNames().iterator().next();
        this.maxSide = maxSide;
    }

    List<Rectangle> detect(BufferedImage image) throws OrtException {
        int h = image.getHeight();
        int w = image.getWidth();
        double ratio = 1.0;
        if (Math.min(h, w) < MIN_SIDE) {
            ratio = (double) MIN_SIDE / Math.min(h, w);
        }
        if (Math.max(h, w) * ratio > maxSide) {
            ratio = (double) maxSide / Math.max(h, w);
        }
        int rh = Math.max(32, (int) Math.round(h * ratio / 32) * 32);
        int rw = Math.max(32, (int) Math.round(w * ratio / 32) * 32);
        BufferedImage resized = (rh == h && rw == w) ? image : Images.resize(image, rw, rh);

        float[] input = new float[3 * rh * rw];
        Images.writeBgrPlanes(resized, input, 0, rw, rh * rw, MEAN, STD);
        float[] prob;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[] {1, 3, rh, rw});
             OrtSession.Result out = session.run(Map.of(inputName, tensor))) {
            FloatBuffer fb = ((OnnxTensor) out.get(0)).getFloatBuffer();
            prob = new float[fb.remaining()];
            fb.get(prob);
        }
        if (prob.length != rh * rw) {
            throw new IllegalStateException("detector output " + prob.length + " != " + rh + "x" + rw);
        }
        return boxes(prob, rw, rh, (double) w / rw, (double) h / rh, w, h);
    }

    private static List<Rectangle> boxes(float[] prob, int w, int h, double sx, double sy, int ow, int oh) {
        byte[] bits = new byte[w * h];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = prob[i] > THRESH ? (byte) 1 : 0;
        }
        int[] stack = new int[4096];
        List<Rectangle> out = new ArrayList<>();
        for (int start = 0; start < bits.length; start++) {
            if (bits[start] == 0) {
                continue;
            }
            bits[start] = 0;
            int sp = 0;
            stack[sp++] = start;
            int minX = w, minY = h, maxX = -1, maxY = -1;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % w;
                int y = p / w;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int ny = Math.max(0, y - 1); ny <= Math.min(h - 1, y + 1); ny++) {
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(w - 1, x + 1); nx++) {
                        int q = ny * w + nx;
                        if (bits[q] != 0) {
                            bits[q] = 0;
                            if (sp == stack.length) {
                                stack = Arrays.copyOf(stack, sp * 2);
                            }
                            stack[sp++] = q;
                        }
                    }
                }
            }
            int bw = maxX - minX + 1;
            int bh = maxY - minY + 1;
            if (Math.min(bw, bh) < 3) {
                continue;
            }
            double sum = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    sum += prob[y * w + x];
                }
            }
            if (sum / ((double) bw * bh) < BOX_THRESH) {
                continue;
            }
            double d = (double) bw * bh * UNCLIP_RATIO / (2.0 * (bw + bh));
            double x0 = minX - d, y0 = minY - d, x1 = maxX + 1 + d, y1 = maxY + 1 + d;
            if (Math.min(x1 - x0, y1 - y0) < 5) {
                continue;
            }
            int ox0 = clamp((int) Math.floor(x0 * sx), ow);
            int oy0 = clamp((int) Math.floor(y0 * sy), oh);
            int ox1 = clamp((int) Math.ceil(x1 * sx), ow);
            int oy1 = clamp((int) Math.ceil(y1 * sy), oh);
            if (ox1 - ox0 >= 2 && oy1 - oy0 >= 2) {
                out.add(new Rectangle(ox0, oy0, ox1 - ox0, oy1 - oy0));
            }
        }
        return out;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }
}
