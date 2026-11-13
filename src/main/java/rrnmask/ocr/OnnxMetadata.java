package rrnmask.ocr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads one {@code metadata_props} value straight from the ONNX protobuf.
 *
 * <p>ONNX Runtime Java (1.31.1) returns metadata through JNI's modified UTF-8: characters outside the BMP
 * come back as raw bytes and the string is cut short by the difference, which drops the last entries of the
 * recognizer's character dictionary (11942 of 11945 for korean_PP-OCRv5) and misaligns nothing else only
 * by luck. Parsing the two top-level protobuf fields we need avoids that.
 */
final class OnnxMetadata {
    private static final int METADATA_PROPS = 14;

    private OnnxMetadata() {}

    static String read(Path model, String key) throws IOException {
        byte[] b = Files.readAllBytes(model);
        int[] pos = {0};
        while (pos[0] < b.length) {
            long tag = varint(b, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 2) {
                int len = (int) varint(b, pos);
                if (field == METADATA_PROPS) {
                    String[] kv = entry(b, pos[0], pos[0] + len);
                    if (key.equals(kv[0])) {
                        return kv[1];
                    }
                }
                pos[0] += len;
            } else {
                skip(b, pos, wire);
            }
        }
        return null;
    }

    /** StringStringEntryProto: key = 1, value = 2. */
    private static String[] entry(byte[] b, int start, int end) {
        String[] kv = new String[2];
        int[] pos = {start};
        while (pos[0] < end) {
            long tag = varint(b, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 2) {
                int len = (int) varint(b, pos);
                if (field == 1 || field == 2) {
                    kv[field - 1] = new String(b, pos[0], len, StandardCharsets.UTF_8);
                }
                pos[0] += len;
            } else {
                skip(b, pos, wire);
            }
        }
        return kv;
    }

    private static void skip(byte[] b, int[] pos, int wire) {
        switch (wire) {
            case 0 -> varint(b, pos);
            case 1 -> pos[0] += 8;
            case 5 -> pos[0] += 4;
            default -> throw new IllegalArgumentException("unsupported protobuf wire type " + wire);
        }
    }

    private static long varint(byte[] b, int[] pos) {
        long v = 0;
        for (int shift = 0; ; shift += 7) {
            byte x = b[pos[0]++];
            v |= (long) (x & 0x7f) << shift;
            if (x >= 0) {
                return v;
            }
        }
    }
}
