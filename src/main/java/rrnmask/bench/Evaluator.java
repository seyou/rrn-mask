package rrnmask.bench;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import rrnmask.bench.Truth.PageTruth;
import rrnmask.bench.Truth.Target;
import rrnmask.find.RrnFinder.Finding;

/**
 * Scores one page's masks against its truth. A partially covered target is a miss: digits stay readable.
 * A keep region (the 6th front digit) is clipped when more than {@value #CLIPPED} of it is masked.
 */
final class Evaluator {
    static final double COVERED = 0.95;
    static final double CLIPPED = 0.15;

    record PageEval(int targets, int covered, int partial, int missed, int falsePositives, List<Rectangle> misses,
                    int keeps, int clipped, double keepCoverage, List<Rectangle> clips) {
        boolean safe() {
            return covered == targets;
        }
    }

    private Evaluator() {}

    static PageEval evaluate(PageTruth truth, int width, int height, List<Finding> findings) {
        int covered = 0;
        int partial = 0;
        int missed = 0;
        List<Rectangle> misses = new ArrayList<>();
        for (Target t : truth.targets()) {
            if (t.box() != null) {
                Rectangle box = pixels(t.box(), width, height);
                double f = coverage(box, findings);
                if (f >= COVERED) {
                    covered++;
                } else {
                    misses.add(box);
                    if (f > 0.05) {
                        partial++;
                    } else {
                        missed++;
                    }
                }
            } else if (t.text() != null && matchesText(t.text(), findings)) {
                covered++;
            } else {
                missed++;
            }
        }
        int keeps = 0;
        int clipped = 0;
        double keepCoverage = 0;
        List<Rectangle> clips = new ArrayList<>();
        for (Target k : truth.keep()) {
            if (k.box() == null) {
                continue;
            }
            Rectangle box = pixels(k.box(), width, height);
            double f = coverage(box, findings);
            keeps++;
            keepCoverage += f;
            if (f > CLIPPED) {
                clipped++;
                clips.add(box);
            }
        }
        return new PageEval(truth.targets().size(), covered, partial, missed,
                falsePositives(truth, width, height, findings), misses, keeps, clipped, keepCoverage, clips);
    }

    static Rectangle pixels(double[] box, int width, int height) {
        int x = (int) Math.floor(box[0] * width);
        int y = (int) Math.floor(box[1] * height);
        int x1 = (int) Math.ceil((box[0] + box[2]) * width);
        int y1 = (int) Math.ceil((box[1] + box[3]) * height);
        return new Rectangle(x, y, x1 - x, y1 - y);
    }

    private static double coverage(Rectangle box, List<Finding> findings) {
        long inside = 0;
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                for (Finding f : findings) {
                    if (f.mask().contains(x, y)) {
                        inside++;
                        break;
                    }
                }
            }
        }
        return box.isEmpty() ? 1.0 : (double) inside / ((long) box.width * box.height);
    }

    /** With boxes: masks touching no target/optional region. With text only: findings matching no truth text. */
    private static int falsePositives(PageTruth truth, int width, int height, List<Finding> findings) {
        List<Target> all = new ArrayList<>(truth.targets());
        all.addAll(truth.optional());
        boolean boxed = all.stream().allMatch(t -> t.box() != null);
        int fp = 0;
        for (Finding f : findings) {
            boolean hit = false;
            for (Target t : all) {
                if (boxed ? f.mask().intersects(pixels(t.box(), width, height))
                        : t.text() != null && close(digits(t.text()), f.digits())) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                fp++;
            }
        }
        return fp;
    }

    private static boolean matchesText(String text, List<Finding> findings) {
        String want = digits(text);
        return findings.stream().anyMatch(f -> close(want, f.digits()));
    }

    private static boolean close(String a, String b) {
        return levenshtein(a, b) <= 2;
    }

    private static String digits(String s) {
        return s.replaceAll("\\D", "");
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
