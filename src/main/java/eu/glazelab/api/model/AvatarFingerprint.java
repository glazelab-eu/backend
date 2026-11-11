package eu.glazelab.api.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/**
 * What identifies a profile picture for the re-upload block (backend guide "Profile pictures"): the SHA-256 of the
 * uploaded bytes, the SHA-256 of the stored (normalised) square, and a 64-bit difference hash (dHash) of that square.
 * The exact hashes catch the same file and the downloaded stored copy; the dHash catches a re-encoded, rescaled or
 * slightly re-cropped copy. Nothing here can recognise an edited or re-photographed picture.
 */
public record AvatarFingerprint(String uploadSha256, String storedSha256, long dhash) {
    /**
     * A dHash with fewer set or unset bits than this carries too little information to compare perceptually: an almost
     * plain picture or a smooth gradient hashes to nearly all zeros (or ones) whatever it shows, so such a block, or such
     * an upload, is matched by its exact hashes only.
     */
    static final int MIN_INFORMATIVE_BITS = 8;
    /**
     * A bit is set only when the left cell is brighter by more than this (of 255 levels): two cells of one flat area are
     * equal up to encoding noise, and without the margin every re-encode would flip such bits at random (measured: a
     * re-encode at JPEG quality 0.6 moved up to 9 bits without it and at most 2 with it; see the 31-4 done entry).
     */
    static final double TIE_MARGIN = 4;

    /** Hashes the upload as sent and the stored square (a JPEG the encoder wrote). */
    public static AvatarFingerprint of(InputStream upload, byte[] stored) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        for (int read; (read = upload.read(buffer)) > 0; ) digest.update(buffer, 0, read);
        BufferedImage square = ImageIO.read(new ByteArrayInputStream(stored));
        if (square == null) throw new IOException("Stored profile picture is unreadable");
        return new AvatarFingerprint(HexFormat.of().formatHex(digest.digest()), sha256(stored), dhash(square));
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * The difference hash: the picture's luminance averaged over a grid of 9 columns and 8 rows, then one bit per
     * horizontal neighbour pair (set when the left cell is brighter by more than {@link #TIE_MARGIN}), row by row from
     * the top left (bit 63 first).
     */
    static long dhash(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        double[][] cells = new double[8][9];
        for (int row = 0; row < 8; row++) {
            int top = row * height / 8, bottom = Math.max(top + 1, (row + 1) * height / 8);
            for (int column = 0; column < 9; column++) {
                int left = column * width / 9, right = Math.max(left + 1, (column + 1) * width / 9);
                double sum = 0;
                for (int y = top; y < bottom; y++) {
                    for (int x = left; x < right; x++) {
                        int rgb = image.getRGB(Math.min(x, width - 1), Math.min(y, height - 1));
                        sum += 0.299 * (rgb >> 16 & 0xff) + 0.587 * (rgb >> 8 & 0xff) + 0.114 * (rgb & 0xff);
                    }
                }
                cells[row][column] = sum / ((double) (bottom - top) * (right - left));
            }
        }
        long hash = 0;
        for (int row = 0; row < 8; row++)
            for (int column = 0; column < 8; column++)
                hash = hash << 1 | (cells[row][column] > cells[row][column + 1] + TIE_MARGIN ? 1 : 0);
        return hash;
    }

    static boolean informative(long dhash) {
        int set = Long.bitCount(dhash);
        return set >= MIN_INFORMATIVE_BITS && 64 - set >= MIN_INFORMATIVE_BITS;
    }

    /**
     * Whether this upload is the blocked picture: either exact hash equals either exact hash of the block (the same
     * file, or the stored copy downloaded and sent again), or both dHashes are informative and differ in at most
     * {@code distance} bits.
     */
    public boolean matches(String blockedUpload, String blockedStored, long blockedDhash, int distance) {
        if (uploadSha256.equals(blockedUpload) || uploadSha256.equals(blockedStored)
                || storedSha256.equals(blockedUpload) || storedSha256.equals(blockedStored)) return true;
        return informative(dhash) && informative(blockedDhash) && Long.bitCount(dhash ^ blockedDhash) <= distance;
    }
}
