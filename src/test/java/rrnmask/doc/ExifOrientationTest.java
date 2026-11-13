package rrnmask.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.onnxruntime.OrtException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ExifOrientationTest {

    /** A real JPEG with an APP1 Exif segment (IFD0 holding only the orientation tag) inserted after SOI. */
    private static byte[] jpegWithOrientation(int width, int height, int orientation, boolean little)
            throws IOException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        byte[] plain = jpeg.toByteArray();

        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.writeBytes(little ? new byte[] {'I', 'I'} : new byte[] {'M', 'M'});
        write16(tiff, 42, little);
        write32(tiff, 8, little);          // IFD0 right after the header
        write16(tiff, 1, little);          // one entry
        write16(tiff, 0x0112, little);     // orientation
        write16(tiff, 3, little);          // SHORT
        write32(tiff, 1, little);          // count
        write16(tiff, orientation, little);
        write16(tiff, 0, little);          // value field padding
        write32(tiff, 0, little);          // no next IFD
        byte[] body = tiff.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);            // SOI
        out.write(0xFF);
        out.write(0xE1);
        int len = 2 + 6 + body.length;
        out.write(len >> 8);
        out.write(len & 0xff);
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(body);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    private static void write16(ByteArrayOutputStream o, int v, boolean little) {
        if (little) {
            o.write(v & 0xff);
            o.write((v >> 8) & 0xff);
        } else {
            o.write((v >> 8) & 0xff);
            o.write(v & 0xff);
        }
    }

    private static void write32(ByteArrayOutputStream o, int v, boolean little) {
        if (little) {
            write16(o, v & 0xffff, true);
            write16(o, v >>> 16, true);
        } else {
            write16(o, v >>> 16, false);
            write16(o, v & 0xffff, false);
        }
    }

    @Test
    void readsOrientationInBothByteOrders() throws IOException {
        assertEquals(6, ExifOrientation.read(jpegWithOrientation(8, 4, 6, true)));
        assertEquals(8, ExifOrientation.read(jpegWithOrientation(8, 4, 8, false)));
    }

    @Test
    void rotatedPhotoIsDecodedUpright() throws IOException, OrtException {
        List<BufferedImage> pages = new ArrayList<>();
        new Redactor(new Redactor.Options(200, null, false))
                .redact(new ByteArrayInputStream(jpegWithOrientation(80, 40, 6, false)), p -> pages.add(p.image()));
        assertEquals(1, pages.size());
        assertEquals(40, pages.getFirst().getWidth());
        assertEquals(80, pages.getFirst().getHeight());
    }

    @Test
    void malformedOrMissingExifMeansUpright() throws IOException {
        byte[] good = jpegWithOrientation(8, 4, 6, true);
        // Cut inside the Exif segment: its declared length now runs past the end.
        assertEquals(1, ExifOrientation.read(Arrays.copyOf(good, 20)));
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB), "jpeg", plain);
        assertEquals(1, ExifOrientation.read(plain.toByteArray()));
        assertEquals(1, ExifOrientation.read(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
    }
}
