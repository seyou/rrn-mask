package rrnmask.ocr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Raw ONNX protobuf handling for two runtime gaps, by reading the top-level {@code ModelProto} fields directly.
 *
 * <ul>
 *   <li>The PaddleOCR exports declare IR version 10; ONNX Runtime 1.12 refuses anything above
 *       {@value #MAX_IR_VERSION}. IR 9/10 only add tensor types (float8, int4) these float32 models do not use,
 *       so the declared version is lowered in memory. A model that really used them would still fail to load,
 *       loudly, on the unknown type.</li>
 *   <li>ONNX Runtime Java returns metadata through JNI's modified UTF-8: characters outside the BMP come back as
 *       raw bytes and the string is cut short, dropping the last entries of the recognizer's dictionary
 *       (11942 of 11945 for korean_PP-OCRv5). {@link #metadata} decodes it as real UTF-8.</li>
 * </ul>
 */
final class OnnxModelFile {
    static final int MAX_IR_VERSION = 8;
    private static final int IR_VERSION = 1;
    private static final int METADATA_PROPS = 14;

    private OnnxModelFile() {}

    /** Model bytes ready for {@code OrtEnvironment.createSession(byte[], …)}. */
    static byte[] load(Path model) throws IOException {
        byte[] b = Files.readAllBytes(model);
        int[] pos = {0};
        while (pos[0] < b.length) {
            long tag = varint(b, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (field == IR_VERSION && wire == 0) {
                int at = pos[0];
                long ir = varint(b, pos);
                if (ir > MAX_IR_VERSION) {
                    if (pos[0] - at != 1) {
                        throw new IOException("unexpected ir_version encoding in " + model);
                    }
                    b[at] = (byte) MAX_IR_VERSION;  // one-byte varint either way
                }
                return b;
            }
            skip(b, pos, wire);
        }
        return b;
    }

    static String metadata(byte[] b, String key) {
        int[] pos = {0};
        while (pos[0] < b.length) {
            long tag = varint(b, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 2 && field == METADATA_PROPS) {
                int len = (int) varint(b, pos);
                String[] kv = entry(b, pos[0], pos[0] + len);
                if (key.equals(kv[0])) {
                    return kv[1];
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
            if (wire == 2 && (field == 1 || field == 2)) {
                int len = (int) varint(b, pos);
                kv[field - 1] = new String(b, pos[0], len, StandardCharsets.UTF_8);
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
            case 2 -> {
                int len = (int) varint(b, pos);
                pos[0] += len;
            }
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
