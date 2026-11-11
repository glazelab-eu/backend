package eu.glazelab.api.controller;

import eu.glazelab.api.model.AvatarFingerprint;
import eu.glazelab.api.support.TestImages;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fingerprint of a profile picture as an upload produces it (the encoder's stored square, then the hashes): what
 * the perceptual hash catches at the default distance (8 bits, {@code glazelab.avatar.block-distance}) and what it
 * keeps apart. The measurements behind the default are in the 31-4 done entry.
 */
class AvatarFingerprintTest {
    private static final int DISTANCE = 8;

    private static AvatarFingerprint print(byte[] upload) throws Exception {
        var encoded = ImageEncoder.encodeAvatar(new MockMultipartFile("file", "picture", "image/jpeg", upload));
        return AvatarFingerprint.of(new ByteArrayInputStream(upload), encoded.jpeg());
    }

    private static int distance(AvatarFingerprint a, AvatarFingerprint b) {
        return Long.bitCount(a.dhash() ^ b.dhash());
    }

    private static boolean blocks(AvatarFingerprint blocked, AvatarFingerprint upload) {
        return upload.matches(blocked.uploadSha256(), blocked.storedSha256(), blocked.dhash(), DISTANCE);
    }

    @Test void reEncodedRescaledAndSlightlyReCroppedCopiesMatch() throws Exception {
        for (long seed = 1; seed <= 12; seed++) {
            BufferedImage scene = TestImages.scene(seed, 600, 600);
            AvatarFingerprint original = print(TestImages.jpeg(scene, 0.92f));
            List<byte[]> copies = List.of(TestImages.jpeg(scene, 0.6f), TestImages.jpeg(scene, 0.3f), TestImages.png(scene),
                    TestImages.jpeg(TestImages.scaled(scene, 420, 420), 0.85f),
                    // The same picture cropped again 2 % further right.
                    TestImages.jpeg(TestImages.crop(scene, 12, 0, 588, 588), 0.85f));
            for (byte[] copy : copies) {
                AvatarFingerprint print = print(copy);
                assertNotEquals(original.uploadSha256(), print.uploadSha256());
                assertTrue(distance(original, print) <= DISTANCE, "seed " + seed + ": " + distance(original, print));
                assertTrue(blocks(original, print), "seed " + seed);
            }
        }
    }

    @Test void exactCopiesMatchByTheirHashes() throws Exception {
        byte[] upload = TestImages.scene(7);
        AvatarFingerprint original = print(upload);
        assertEquals(original, print(upload));
        var stored = ImageEncoder.encodeAvatar(new MockMultipartFile("file", "picture", "image/jpeg", upload)).jpeg();
        AvatarFingerprint downloaded = print(stored);
        // The stored square sent again: its upload hash is the stored hash of the original.
        assertEquals(original.storedSha256(), downloaded.uploadSha256());
        assertTrue(downloaded.matches(original.uploadSha256(), original.storedSha256(), ~original.dhash(), 0));
        assertTrue(original.matches("x", original.uploadSha256(), ~original.dhash(), 0));
        assertTrue(original.matches(original.storedSha256(), "x", ~original.dhash(), 0));
        assertFalse(original.matches("x", "y", ~original.dhash(), 0));
    }

    @Test void differentPicturesStayApart() throws Exception {
        List<AvatarFingerprint> prints = new ArrayList<>();
        for (long seed = 1; seed <= 20; seed++) prints.add(print(TestImages.scene(seed)));
        for (int i = 0; i < prints.size(); i++)
            for (int j = i + 1; j < prints.size(); j++) {
                assertTrue(distance(prints.get(i), prints.get(j)) > DISTANCE, i + " " + j);
                assertFalse(blocks(prints.get(i), prints.get(j)));
            }
    }

    /** The distance counts from both sides of the threshold; almost plain pictures are matched by exact hashes only. */
    @Test void theThresholdIsInclusiveAndPlainPicturesNeedAnExactMatch() throws Exception {
        AvatarFingerprint upload = print(TestImages.scene(3));
        long eight = 0xFFL, nine = 0x1FFL;
        assertTrue(upload.matches("x", "y", upload.dhash() ^ eight, 8));
        assertFalse(upload.matches("x", "y", upload.dhash() ^ nine, 8));
        assertTrue(upload.matches("x", "y", upload.dhash() ^ nine, 9));
        assertFalse(upload.matches("x", "y", upload.dhash(), -1));

        BufferedImage plain = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        var graphics = plain.createGraphics();
        graphics.setColor(new Color(17, 99, 201));
        graphics.fillRect(0, 0, 120, 120);
        graphics.dispose();
        AvatarFingerprint flat = print(TestImages.png(plain));
        assertEquals(0, Long.bitCount(flat.dhash()));
        assertFalse(flat.matches("x", "y", flat.dhash(), 64), "no perceptual match for an uninformative hash");
        assertFalse(upload.matches("x", "y", 0L, 64), "nor against an uninformative block");
        assertTrue(flat.matches("x", flat.storedSha256(), 0L, 0));
    }
}
