package eu.glazelab.api.controller;

import eu.glazelab.api.support.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class ImageEncoderTest {
    private static ImageEncoder.Encoded encode(byte[] upload) throws IOException {
        return ImageEncoder.encode(new MockMultipartFile("file", "photo.jpg", "image/jpeg", upload));
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    /** A 3x2 image whose pixels hold their own index (0..5) in the blue channel. */
    private static BufferedImage indexed() {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 6; i++) image.setRGB(i % 3, i / 3, i);
        return image;
    }

    private static String rows(BufferedImage image) {
        var text = new StringBuilder();
        for (int y = 0; y < image.getHeight(); y++) {
            if (y > 0) text.append('/');
            for (int x = 0; x < image.getWidth(); x++) text.append(image.getRGB(x, y) & 0xff);
        }
        return text.toString();
    }

    @Test void everyExifOrientationIsTurnedUpright() {
        // Source rows "012/345"; expected display per the EXIF/TIFF orientation definitions.
        String[] expected = {null, "012/345", "210/543", "543/210", "345/012", "03/14/25", "30/41/52", "52/41/30", "25/14/03"};
        for (int orientation = 1; orientation <= 8; orientation++)
            assertEquals(expected[orientation], rows(ImageEncoder.orient(indexed(), orientation)), "orientation " + orientation);
    }

    @Test void orientationIsReadFromBothByteOrders() {
        byte[] jpeg = TestImages.jpeg(indexed(), 0.9f);
        for (boolean little : new boolean[] {true, false}) {
            byte[] tagged = TestImages.withExifOrientation(jpeg, 6, little);
            int app1 = 4 + ((jpeg[4] & 0xff) << 8 | (jpeg[5] & 0xff));
            byte[] segment = java.util.Arrays.copyOfRange(tagged, app1 + 4, app1 + 4 + 32);
            assertEquals(6, ImageEncoder.exifOrientation(segment));
        }
        assertEquals(0, ImageEncoder.exifOrientation("Exif\0\0MM".getBytes()));
        assertEquals(0, ImageEncoder.exifOrientation(new byte[40]));
    }

    @Test void photosAreScaledUprightProgressiveAndStripped() throws IOException {
        var portrait = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 400; x++) for (int y = 0; y < 200; y++) portrait.setRGB(x, y, x < 200 ? 0xd02020 : 0x2020d0);
        byte[] upload = TestImages.withExifOrientation(TestImages.jpeg(portrait, 0.9f), 6, false);
        assertTrue(TestImages.hasMarker(upload, 0xe1), "fixture carries Exif");

        ImageEncoder.Encoded encoded = encode(upload);
        assertEquals(200, encoded.width());
        assertEquals(400, encoded.height());
        BufferedImage stored = decode(encoded.jpeg());
        assertEquals(200, stored.getWidth());
        assertEquals(400, stored.getHeight());
        assertTrue((stored.getRGB(100, 50) >> 16 & 0xff) > 150, "the left (red) half is now on top");
        assertTrue((stored.getRGB(100, 350) & 0xff) > 150, "the right (blue) half is now at the bottom");
        assertTrue(TestImages.hasMarker(encoded.jpeg(), 0xc2), "progressive JPEG");
        assertFalse(TestImages.hasMarker(encoded.jpeg(), 0xe1), "no Exif or XMP");
    }

    @Test void largePhotosAreBoundedInDimensionsAndSize() throws IOException {
        byte[] upload = TestImages.photo(4000, 3000);
        ImageEncoder.Encoded encoded = encode(upload);
        assertEquals(ImageEncoder.MAX_SIDE, encoded.width());
        assertEquals(1200, encoded.height());
        BufferedImage stored = decode(encoded.jpeg());
        assertEquals(1600, stored.getWidth());
        assertEquals(1200, stored.getHeight());
        // About 0.1-0.2 bytes per pixel for camera photos; a regression to a lossless or high-quality setting
        // would roughly double this.
        assertTrue(encoded.jpeg().length < 400_000, "stored " + encoded.jpeg().length + " bytes");
        assertTrue(encoded.jpeg().length * 5 < upload.length, "upload " + upload.length + ", stored " + encoded.jpeg().length);
    }

    @Test void theCardCopyIsScaledFromTheUprightFullSizeInTheSameEncode() throws IOException {
        byte[] upload = TestImages.withExifOrientation(TestImages.photo(4000, 3000), 6, false);
        ImageEncoder.Encoded encoded = encode(upload);
        assertEquals(List.of(1200, 1600), List.of(encoded.width(), encoded.height()));
        BufferedImage card = decode(encoded.card());
        assertEquals(List.of(360, ImageEncoder.CARD_SIDE), List.of(card.getWidth(), card.getHeight()));
        assertTrue(TestImages.hasMarker(encoded.card(), 0xc2), "progressive JPEG");
        assertFalse(TestImages.hasMarker(encoded.card(), 0xe1), "no Exif or XMP");
        // Same quality as the full size at about a tenth of the pixels.
        assertTrue(encoded.card().length * 5 < encoded.jpeg().length, "full " + encoded.jpeg().length + ", card " + encoded.card().length);
    }

    @Test void smallImagesKeepTheirSizeAndTransparencyBecomesWhite() throws IOException {
        ImageEncoder.Encoded encoded = encode(TestImages.png(30, 20));
        assertEquals(30, encoded.width());
        assertEquals(20, encoded.height());
        assertSame(encoded.jpeg(), encoded.card(), "a picture that fits the card is its own card");
        assertTrue((decode(encoded.jpeg()).getRGB(15, 10) & 0xffffff) > 0xf0f0f0, "white background");
    }

    @Test void excessivePixelsAreRejectedFromTheHeaderBeforeBrokenPixelDataIsDecoded() throws IOException {
        for (int side : new int[] {5000, 7000}) {
            var error = assertThrows(ResponseStatusException.class,
                    () -> encode(TestImages.pngWithBrokenData(side, side)));
            assertEquals(400, error.getStatusCode().value());
            assertEquals("Image dimensions are too large", error.getReason());
        }
        // Header rejection must also release the encoder for the next upload.
        assertEquals(30, encode(TestImages.png(30, 20)).width());
    }

    @Test void concurrentEncodesFailPromptlyWithoutOpeningTheirInputAndCapacityIsReleased() throws Exception {
        byte[] png = TestImages.png(30, 20);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocked = new MockMultipartFile("file", "photo.png", "image/png", png) {
            @Override public InputStream getInputStream() throws IOException {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Test input timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                }
                return super.getInputStream();
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> ImageEncoder.encode(blocked));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var unopened = new MockMultipartFile("file", "photo.png", "image/png", png) {
                    @Override public InputStream getInputStream() { throw new AssertionError("Busy uploads must not read input"); }
                };
                for (int i = 0; i < 4; i++) {
                    var error = assertThrows(ResponseStatusException.class, () -> ImageEncoder.encode(unopened));
                    assertEquals(503, error.getStatusCode().value());
                    assertEquals("Image processing is busy. Please try uploading again.", error.getReason());
                }
            } finally {
                release.countDown();
            }
            // The card copy is made inside the encode that held the permit, never in a second one.
            ImageEncoder.Encoded done = first.get(5, TimeUnit.SECONDS);
            assertEquals(30, done.width());
            assertNotNull(done.card());
        }
        // A failed decode must release capacity too.
        assertThrows(ResponseStatusException.class, () -> encode(TestImages.pngWithBrokenData(30, 20)));
        assertEquals(30, encode(png).width());
    }
}
