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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * PP-OCR CTC recognition. The character dictionary is the ONNX metadata value {@code character}
 * (RapidOCR embeds it); class 0 is the CTC blank and the last class is a space.
 * Character positions come from the CTC time steps: each emitted character sits at the centre of its run,
 * and boundaries between characters are the midpoints of neighbouring centres.
 */
final class TextRecognizer {
    private static final int HEIGHT = 48;
    private static final int MIN_WIDTH = 320;
    private static final int BATCH = 6;
    private static final float[] HALF = {0.5f, 0.5f, 0.5f};

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final String[] vocab;

    TextRecognizer(OrtEnvironment env, OrtSession session, String dict) {
        this.env = env;
        this.session = session;
        this.inputName = session.getInputNames().iterator().next();
        if (dict == null) {
            throw new IllegalArgumentException("recognition model has no embedded 'character' dictionary");
        }
        String[] entries = dict.split("\n");
        vocab = new String[entries.length + 2];
        vocab[0] = "";
        System.arraycopy(entries, 0, vocab, 1, entries.length);
        vocab[vocab.length - 1] = " ";
    }

    List<TextLine> recognize(BufferedImage page, List<Rectangle> boxes) throws OrtException {
        int n = boxes.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> ratio(boxes.get(i))));
        TextLine[] results = new TextLine[n];
        for (int b = 0; b < n; b += BATCH) {
            int count = Math.min(BATCH, n - b);
            double maxRatio = (double) MIN_WIDTH / HEIGHT;
            for (int k = 0; k < count; k++) {
                maxRatio = Math.max(maxRatio, ratio(boxes.get(order[b + k])));
            }
            int width = (int) (HEIGHT * maxRatio);
            int sample = 3 * HEIGHT * width;
            float[] input = new float[count * sample];
            int[] resizedWidth = new int[count];
            for (int k = 0; k < count; k++) {
                Rectangle r = boxes.get(order[b + k]);
                int rw = Math.max(1, (int) Math.min(width, Math.ceil(HEIGHT * ratio(r))));
                resizedWidth[k] = rw;
                BufferedImage crop = Images.resize(page.getSubimage(r.x, r.y, r.width, r.height), rw, HEIGHT);
                Images.writeBgrPlanes(crop, input, k * sample, width, HEIGHT * width, HALF, HALF);
            }
            float[] out;
            long[] shape;
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input),
                    new long[] {count, 3, HEIGHT, width});
                 OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
                OnnxTensor o = (OnnxTensor) result.get(0);
                shape = o.getInfo().getShape();
                FloatBuffer fb = o.getFloatBuffer();
                out = new float[fb.remaining()];
                fb.get(out);
            }
            int steps = (int) shape[1];
            int classes = (int) shape[2];
            if (classes != vocab.length) {
                throw new IllegalStateException("model has " + classes + " classes, dictionary " + vocab.length);
            }
            for (int k = 0; k < count; k++) {
                int idx = order[b + k];
                results[idx] = decode(out, k * steps * classes, steps, classes, boxes.get(idx),
                        resizedWidth[k], width);
            }
        }
        List<TextLine> lines = new ArrayList<>(n);
        for (TextLine line : results) {
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private TextLine decode(float[] out, int offset, int steps, int classes, Rectangle box, int resizedWidth,
                            int inputWidth) {
        List<String> tokens = new ArrayList<>();
        List<int[]> runs = new ArrayList<>();
        double scoreSum = 0;
        int prev = 0;
        for (int t = 0; t < steps; t++) {
            int base = offset + t * classes;
            int best = 0;
            float bestP = out[base];
            for (int c = 1; c < classes; c++) {
                if (out[base + c] > bestP) {
                    bestP = out[base + c];
                    best = c;
                }
            }
            if (best != 0 && best != prev) {
                tokens.add(vocab[best]);
                runs.add(new int[] {t, t});
                scoreSum += bestP;
            } else if (best != 0) {
                runs.getLast()[1] = t;
            }
            prev = best;
        }
        if (tokens.isEmpty()) {
            return null;
        }
        double stepPx = (double) inputWidth / steps * box.width / resizedWidth;
        StringBuilder text = new StringBuilder();
        List<Double> centers = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            double c = box.x + (runs.get(i)[0] + runs.get(i)[1] + 1) / 2.0 * stepPx;
            String tok = tokens.get(i);
            for (int j = 0; j < tok.length(); j++) {
                text.append(tok.charAt(j));
                centers.add(c);
            }
        }
        int m = text.length();
        float[] left = new float[m];
        float[] right = new float[m];
        for (int i = 0; i < m; i++) {
            left[i] = i == 0 ? box.x : (float) ((centers.get(i - 1) + centers.get(i)) / 2);
            right[i] = i == m - 1 ? box.x + box.width : (float) ((centers.get(i) + centers.get(i + 1)) / 2);
        }
        return new TextLine(box, text.toString(), (float) (scoreSum / tokens.size()), left, right,
                TextLine.Source.OCR);
    }

    private static double ratio(Rectangle r) {
        return (double) r.width / r.height;
    }
}
