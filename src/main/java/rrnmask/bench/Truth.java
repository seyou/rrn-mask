package rrnmask.bench;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Ground truth for one input file, stored next to it as {@code <file>.truth.json}.
 *
 * <pre>{@code
 * {"group": "real-scan",
 *  "pages": [{"page": 1,
 *             "targets":  [{"box": [0.41, 0.33, 0.07, 0.011]}, {"text": "900101-1234567"}],
 *             "optional": [{"box": [0.41, 0.36, 0.07, 0.011]}]}]}
 * }</pre>
 *
 * A target is a back-seven-digit region that must be masked. {@code box} is {@code [x, y, w, h]} as fractions
 * of page width/height, so it holds at any render DPI; with only {@code text}, a target counts as found when
 * some finding's digits are within edit distance 2. {@code optional} regions (corporate registration numbers)
 * may be masked without counting as false positives and are not part of recall.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Truth(String group, List<PageTruth> pages) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageTruth(int page, List<Target> targets, List<Target> optional) {
        public List<Target> targets() {
            return targets == null ? List.of() : targets;
        }

        public List<Target> optional() {
            return optional == null ? List.of() : optional;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(String text, double[] box) {}

    PageTruth page(int number) {
        if (pages != null) {
            for (PageTruth p : pages) {
                if (p.page() == number) {
                    return p;
                }
            }
        }
        return new PageTruth(number, List.of(), List.of());
    }
}
