package rrnmask;

import java.util.Arrays;

public final class Main {
    private static final String USAGE = """
            rrnmask — mask the back seven digits of resident registration numbers in images and PDFs

            rrnmask bench --out DIR [options] INPUT...
              INPUT              files or directories (pdf png jpg jpeg tif tiff bmp gif)
              --det LIST         comma-separated detectors: v5-mobile (default), v5-server,
                                 v6-tiny, v6-small, v6-medium
              --sources LIST     ocr,pdftext (default both); pdftext = embedded PDF text layer
              --models DIR       ONNX models (default: models; fetch with scripts/fetch-models.sh)
              --dpi N            PDF render resolution (default 200)
              --det-max-side N   longest side fed to the detector (default 4000)
              --threads N        ONNX Runtime intra-op threads (default: runtime decides)
              --debug            also write unmasked overlay images and recognized text (contain PII)
              Scores against <input>.truth.json when present (format: rrnmask.bench.Truth).
              Writes DIR/<det>/<input>/page-NNN.png and DIR/report.json; prints a summary table.

            rrnmask synth --out DIR [--count N] [--seed S] [--fonts DIR | --font TTF ...]
              Generates N fake registers per rendering (png-clean, jpg-scan, jpg-hard, pdf-text, pdf-scan)
              with truth files. Fonts: DIR/*.ttf (default fonts/, see scripts/fetch-fonts.sh) plus
              system Korean fonts; vector PDFs use only fonts PDFBox can embed.
            """;

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.print(USAGE);
            System.exit(2);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "bench" -> rrnmask.bench.Bench.run(rest);
            case "synth" -> rrnmask.bench.SynthGen.run(rest);
            default -> {
                System.err.print(USAGE);
                System.exit(2);
            }
        }
    }
}
