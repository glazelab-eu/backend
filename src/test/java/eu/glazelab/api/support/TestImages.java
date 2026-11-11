package eu.glazelab.api.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

/** Image payloads for upload tests: real encodings and hand-built malformed PNG files. */
public final class TestImages {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private TestImages() {}

    public static byte[] png(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }

    public static byte[] jpeg(BufferedImage image, float quality) {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var bytes = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** A camera-like photo: smooth shading, a few glaze-like blobs and sensor noise, saved as a high-quality JPEG. */
    public static byte[] photo(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double u = (double) x / width, v = (double) y / height;
                double blob = Math.sin(u * 9) * Math.cos(v * 7) * 40;
                int r = clamp(150 + 60 * u + blob + random.nextGaussian() * 6);
                int g = clamp(120 + 50 * v - blob / 2 + random.nextGaussian() * 6);
                int b = clamp(90 + 40 * (1 - u) + blob / 3 + random.nextGaussian() * 6);
                image.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        return jpeg(image, 0.92f);
    }

    /**
     * A picture-like scene for profile picture tests: a lit background and overlapping shapes placed by {@code seed}, so
     * two seeds are clearly different pictures and every test can upload a picture nobody has blocked.
     */
    public static BufferedImage scene(long seed, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var random = new Random(seed);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(random.nextInt(0xffffff)),
                width, height, new java.awt.Color(random.nextInt(0xffffff))));
        graphics.fillRect(0, 0, width, height);
        for (int i = 0; i < 14; i++) {
            graphics.setColor(new java.awt.Color(random.nextInt(0xffffff)));
            int w = width / 8 + random.nextInt(width / 3), h = height / 8 + random.nextInt(height / 3);
            int x = random.nextInt(width) - w / 2, y = random.nextInt(height) - h / 2;
            if (random.nextBoolean()) graphics.fillOval(x, y, w, h);
            else graphics.fillRect(x, y, w, h);
        }
        graphics.dispose();
        return image;
    }

    /**
     * A picture no other call returns: 8 px squares of random colours, so its exact hashes are new and its dHash is as
     * good as random (two such pictures differ in about 32 of 64 bits; at most 8 has a chance of about 1e-10), and a
     * block that one test adds never refuses another test's upload.
     */
    public static byte[] unique() {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        var random = new java.security.SecureRandom();
        for (int y = 0; y < 64; y += 8)
            for (int x = 0; x < 64; x += 8) {
                int rgb = random.nextInt(0x1000000);
                for (int dy = 0; dy < 8; dy++) for (int dx = 0; dx < 8; dx++) image.setRGB(x + dx, y + dy, rgb);
            }
        return png(image);
    }

    /** {@link #scene} as a high-quality JPEG. */
    public static byte[] scene(long seed) {
        return jpeg(scene(seed, 600, 600), 0.92f);
    }

    /** The part of {@code image} from ({@code x}, {@code y}) of the given size, as its own image. */
    public static BufferedImage crop(BufferedImage image, int x, int y, int width, int height) {
        var copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = copy.createGraphics();
        graphics.drawImage(image, -x, -y, null);
        graphics.dispose();
        return copy;
    }

    /** {@code image} scaled to the given size. */
    public static BufferedImage scaled(BufferedImage image, int width, int height) {
        var copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = copy.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return copy;
    }

    public static byte[] png(BufferedImage image) {
        var bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }

    private static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    /** Inserts an APP1 Exif segment with the given orientation tag after the JFIF APP0 segment. */
    public static byte[] withExifOrientation(byte[] jpeg, int orientation, boolean littleEndian) {
        var order = littleEndian ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN;
        var tiff = ByteBuffer.allocate(26).order(order);
        tiff.put(littleEndian ? new byte[] {'I', 'I'} : new byte[] {'M', 'M'}).putShort((short) 42).putInt(8);
        tiff.putShort((short) 1).putShort((short) 0x0112).putShort((short) 3).putInt(1)
                .putShort((short) orientation).putShort((short) 0).putInt(0);
        byte[] exif = ByteBuffer.allocate(6 + tiff.capacity()).put("Exif".getBytes(StandardCharsets.US_ASCII))
                .put(new byte[2]).put(tiff.array()).array();
        int app0End = 4 + ((jpeg[4] & 0xff) << 8 | (jpeg[5] & 0xff));
        var bytes = new ByteArrayOutputStream();
        bytes.write(jpeg, 0, app0End);
        bytes.write(0xff);
        bytes.write(0xe1);
        bytes.write((exif.length + 2) >> 8);
        bytes.write((exif.length + 2) & 0xff);
        bytes.writeBytes(exif);
        bytes.write(jpeg, app0End, jpeg.length - app0End);
        return bytes.toByteArray();
    }

    /** True when the JPEG contains the given marker (for example 0xC2 progressive, 0xE1 Exif) before its scan data. */
    public static boolean hasMarker(byte[] jpeg, int marker) {
        int i = 2;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xff) == 0xff) {
            int type = jpeg[i + 1] & 0xff;
            if (type == marker) return true;
            if (type == 0xda) return false;
            i += 2 + ((jpeg[i + 2] & 0xff) << 8 | (jpeg[i + 3] & 0xff));
        }
        return false;
    }

    /** An animated GIF with one frame per colour, each filled with that colour. */
    public static byte[] animatedGif(int width, int height, java.awt.Color... frames) {
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        var bytes = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (var colour : frames) {
                var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                graphics.setColor(colour);
                graphics.fillRect(0, 0, width, height);
                graphics.dispose();
                writer.writeToSequence(new IIOImage(image, null, null), null);
            }
            writer.endWriteSequence();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** A JPEG whose left half is {@code left} and right half {@code right}. */
    public static byte[] halves(int width, int height, java.awt.Color left, java.awt.Color right) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(left);
        graphics.fillRect(0, 0, width / 2, height);
        graphics.setColor(right);
        graphics.fillRect(width / 2, 0, width - width / 2, height);
        graphics.dispose();
        return jpeg(image, 0.95f);
    }

    /** A PNG whose header declares the given size but whose pixel data is not valid zlib data. */
    public static byte[] pngWithBrokenData(int width, int height) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(SIGNATURE);
        bytes.writeBytes(chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0).array()));
        bytes.writeBytes(chunk("IDAT", "definitely not deflate data".getBytes(StandardCharsets.US_ASCII)));
        bytes.writeBytes(chunk("IEND", new byte[0]));
        return bytes.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(name);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(name).put(data)
                .putInt((int) crc.getValue()).array();
    }
}
