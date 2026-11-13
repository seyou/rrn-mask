package rrnmask.doc;

/**
 * Reads the EXIF orientation tag (0x0112) of a JPEG. Only APP1 "Exif" segments before the image data are
 * looked at, every offset is bounds-checked, and anything malformed means "upright" (1): uploads are untrusted.
 */
final class ExifOrientation {
    private static final int TAG_ORIENTATION = 0x0112;

    private ExifOrientation() {}

    static int read(byte[] b) {
        if (b.length < 4 || (b[0] & 0xff) != 0xFF || (b[1] & 0xff) != 0xD8) {
            return 1;
        }
        int pos = 2;
        while (pos + 4 <= b.length && (b[pos] & 0xff) == 0xFF) {
            int marker = b[pos + 1] & 0xff;
            if (marker == 0xDA || marker == 0xD9) {
                break;  // start of scan / end of image: no metadata past here
            }
            int len = u16(b, pos + 2, false);
            int body = pos + 4;
            int end = pos + 2 + len;
            if (len < 2 || end > b.length) {
                break;
            }
            if (marker == 0xE1 && end - body >= 6 && b[body] == 'E' && b[body + 1] == 'x' && b[body + 2] == 'i'
                    && b[body + 3] == 'f' && b[body + 4] == 0 && b[body + 5] == 0) {
                return tiff(b, body + 6, end);
            }
            pos = end;
        }
        return 1;
    }

    /** TIFF header at {@code start}; IFD0 entries are 12 bytes: tag, type, count, value. */
    private static int tiff(byte[] b, int start, int end) {
        if (end - start < 8) {
            return 1;
        }
        boolean little;
        if (b[start] == 'I' && b[start + 1] == 'I') {
            little = true;
        } else if (b[start] == 'M' && b[start + 1] == 'M') {
            little = false;
        } else {
            return 1;
        }
        if (u16(b, start + 2, little) != 42) {
            return 1;
        }
        long ifd = u32(b, start + 4, little);
        if (ifd < 8 || ifd > end - start - 2) {
            return 1;
        }
        int p = start + (int) ifd;
        int count = u16(b, p, little);
        for (int i = 0; i < count; i++) {
            int e = p + 2 + i * 12;
            if (e + 12 > end) {
                return 1;
            }
            if (u16(b, e, little) == TAG_ORIENTATION) {
                int v = u16(b, e + 8, little);  // SHORT, stored left-justified in the value field
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int i, boolean little) {
        int x = b[i] & 0xff;
        int y = b[i + 1] & 0xff;
        return little ? x | (y << 8) : (x << 8) | y;
    }

    private static long u32(byte[] b, int i, boolean little) {
        long v = 0;
        for (int k = 0; k < 4; k++) {
            long x = b[i + (little ? 3 - k : k)] & 0xffL;
            v = (v << 8) | x;
        }
        return v;
    }
}
