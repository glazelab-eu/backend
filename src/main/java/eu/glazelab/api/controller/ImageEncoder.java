package eu.glazelab.api.controller;

import eu.glazelab.api.config.MinuteSummary;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Node;

/**
 * Decodes an upload and re-encodes it as a progressive JPEG of at most {@link #MAX_SIDE} px, upright and
 * without metadata (no EXIF, GPS or camera data), plus a card-size copy of at most {@link #CARD_SIDE} px for
 * covers and thumbnails, scaled from the full size within the same encoder permit. Settings were chosen by measuring real photos.
 * WebP would save roughly 10-15 % more but needs a native encoder in Java. Profile pictures
 * ({@link #encodeAvatar}) take the same limits, sniffing, permit and metadata stripping and become a centred square.
 */
final class ImageEncoder {
    static final String UNSUPPORTED_IMAGE = "Unsupported image";
    static final String IMAGE_TOO_LARGE = "Choose an image under 12 MB";
    static final String DIMENSIONS_TOO_LARGE = "Image dimensions are too large";
    static final String ENCODER_BUSY = "Image processing is busy. Please try uploading again.";
    private static final Logger log = LoggerFactory.getLogger(ImageEncoder.class);
    /** Refusals per reason class and busy answers, one log line a minute each (backend guide "Logs"). */
    static final MinuteSummary REFUSALS = new MinuteSummary();
    static final int MAX_SIDE = 1600;
    /** Long edge of the card variant (covers, thumbnails, admin report rows); about 20-40 kB at {@link #QUALITY}. */
    static final int CARD_SIDE = 480;
    static final float QUALITY = 0.80f;
    /** Side of a stored profile picture (never upscaled) and of its small copy (member rows, author lines). */
    static final int AVATAR_SIDE = 256, AVATAR_SMALL_SIDE = 64;
    private static final long MAX_BYTES = 12_000_000, MAX_PIXELS = 24_000_000;
    // A 16-bit RGBA source alone needs eight bytes per pixel. Never queue large decodes in request threads.
    static final Semaphore ENCODING = new Semaphore(1);
    private static final String JPEG_METADATA = "javax_imageio_jpeg_image_1.0";

    /** The full-size JPEG with its dimensions and the card-size JPEG (the same bytes when the picture is no larger). */
    record Encoded(byte[] jpeg, byte[] card, int width, int height) {}

    private ImageEncoder() {}

    static Encoded encode(MultipartFile file) throws IOException {
        return encode(file, ImageEncoder::photo);
    }

    /**
     * A profile picture: the centred square of the upload (the client crops it; anything else is cut to its middle),
     * upright, at most {@link #AVATAR_SIDE} px, and its {@link #AVATAR_SMALL_SIDE} px copy as {@code card}, both
     * without metadata and inside the one encoder permit.
     */
    static Encoded encodeAvatar(MultipartFile file) throws IOException {
        return encode(file, ImageEncoder::avatar);
    }

    /**
     * Several photos (feedback) inside ONE encoder permit, in their order, so a parallel upload cannot take the permit
     * between two of them; each file passes the same checks as a single photo.
     */
    static List<Encoded> encodeAll(List<MultipartFile> files) throws IOException {
        for (MultipartFile file : files) checkSize(file);
        acquire();
        try {
            List<Encoded> encoded = new ArrayList<>(files.size());
            for (MultipartFile file : files) encoded.add(decode(file, ImageEncoder::photo));
            return encoded;
        } finally {
            ENCODING.release();
        }
    }

    private interface Encoding { Encoded apply(BufferedImage source, int orientation) throws IOException; }

    private static Encoded encode(MultipartFile file, Encoding encoding) throws IOException {
        checkSize(file);
        acquire();
        try {
            return decode(file, encoding);
        } finally {
            ENCODING.release();
        }
    }

    private static void checkSize(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw refused("empty or too large", IMAGE_TOO_LARGE);
    }

    private static void acquire() {
        if (!ENCODING.tryAcquire()) {
            int events = REFUSALS.count("busy");
            if (events > 0) log.warn("Image encoder busy: {} upload(s) answered 503 since the last line", events);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ENCODER_BUSY);
        }
    }

    /** A 400 for an upload outside the limits, logged by its reason class only (never a name, size or ID). */
    private static ResponseStatusException refused(String reason, String message) {
        int events = REFUSALS.count(reason);
        if (events > 0) log.info("Image upload refused: {}; {} refusal(s) of this kind since the last line", reason, events);
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static Encoded decode(MultipartFile file, Encoding encoding) throws IOException {
        BufferedImage source;
        int orientation;
        try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
            if (input == null) throw refused("unsupported format", UNSUPPORTED_IMAGE);
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw refused("unsupported format", UNSUPPORTED_IMAGE);
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS)
                    throw refused("too many pixels", DIMENSIONS_TOO_LARGE);
                orientation = orientation(reader);
                source = reader.read(0);
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (IOException | RuntimeException ex) {
                // Malformed pixel data only fails while decoding, after the header was accepted.
                throw refused("undecodable data", UNSUPPORTED_IMAGE);
            } finally { reader.dispose(); }
        }
        return encoding.apply(source, orientation);
    }

    private static Encoded photo(BufferedImage source, int orientation) throws IOException {
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage image = orient(scale(source, width, height), orientation);
        byte[] full = jpeg(image);
        return new Encoded(full, card(image, full), image.getWidth(), image.getHeight());
    }

    /**
     * Crops before scaling and turns last, like photos: the centre square of a picture is the centre square of the
     * picture turned upright, for every EXIF orientation, so only the stored pixels are ever rotated.
     */
    private static Encoded avatar(BufferedImage source, int orientation) throws IOException {
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage square = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
        int target = Math.min(AVATAR_SIDE, side);
        BufferedImage image = orient(scale(square, target, target), orientation);
        byte[] full = jpeg(image);
        byte[] small = target <= AVATAR_SMALL_SIDE ? full : jpeg(scale(image, AVATAR_SMALL_SIDE, AVATAR_SMALL_SIDE));
        return new Encoded(full, small, target, target);
    }

    /** The upright full-size image scaled to {@link #CARD_SIDE}; a picture that already fits keeps its bytes. */
    private static byte[] card(BufferedImage image, byte[] full) throws IOException {
        int side = Math.max(image.getWidth(), image.getHeight());
        if (side <= CARD_SIDE) return full;
        double scale = (double) CARD_SIDE / side;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        return jpeg(scale(image, width, height));
    }

    /**
     * Halves the image with bicubic steps before the final resize. A single bicubic step from a large photo
     * aliases fine texture, which looked worse and cost about 6 % more bytes in the measurements.
     */
    static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        boolean opaque = false;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
            opaque = true;
        }
        return opaque && current.getWidth() == width && current.getHeight() == height
                ? current : draw(current, width, height);
    }

    /** Draws onto an opaque RGB canvas; transparent areas become white. */
    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return rgb;
    }

    /** Progressive JPEG with optimized Huffman tables (about 6 % smaller, same pixels) and no metadata. */
    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            var params = (JPEGImageWriteParam) writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(QUALITY);
            params.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            params.setOptimizeHuffmanTables(true);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }

    /** The EXIF orientation (1-8) of a JPEG upload, or 1 when absent or unreadable. */
    private static int orientation(ImageReader reader) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(0);
            if (metadata == null || !JPEG_METADATA.equals(metadata.getNativeMetadataFormatName())) return 1;
            Node markers = ((IIOMetadataNode) metadata.getAsTree(JPEG_METADATA))
                    .getElementsByTagName("markerSequence").item(0);
            for (Node marker = markers == null ? null : markers.getFirstChild(); marker != null;
                    marker = marker.getNextSibling()) {
                if (marker instanceof IIOMetadataNode node && "unknown".equals(node.getNodeName())
                        && "225".equals(node.getAttribute("MarkerTag")) && node.getUserObject() instanceof byte[] app1) {
                    int value = exifOrientation(app1);
                    if (value > 0) return value;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Orientation is a display hint; an unreadable one leaves the pixels as stored.
        }
        return 1;
    }

    /** Reads tag 0x0112 from IFD0 of an APP1 "Exif" segment; 0 when absent. */
    static int exifOrientation(byte[] app1) {
        if (app1.length < 14 || app1[0] != 'E' || app1[1] != 'x' || app1[2] != 'i' || app1[3] != 'f'
                || app1[4] != 0 || app1[5] != 0) return 0;
        boolean little = app1[6] == 'I' && app1[7] == 'I';
        if (!little && !(app1[6] == 'M' && app1[7] == 'M')) return 0;
        ByteBuffer tiff = ByteBuffer.wrap(app1, 6, app1.length - 6).slice()
                .order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int ifd = tiff.getInt(4);
        if (ifd < 8 || ifd > tiff.limit() - 2) return 0;
        int entries = Short.toUnsignedInt(tiff.getShort(ifd));
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry > tiff.limit() - 12) return 0;
            if (Short.toUnsignedInt(tiff.getShort(entry)) == 0x0112) {
                int value = Short.toUnsignedInt(tiff.getShort(entry + 8));
                return value >= 1 && value <= 8 ? value : 0;
            }
        }
        return 0;
    }

    /** Applies an EXIF orientation, so the stored image is upright once the metadata is dropped. */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) return image;
        int w = image.getWidth(), h = image.getHeight();
        boolean swap = orientation >= 5;
        int outW = swap ? h : w, outH = swap ? w : h;
        int[] in = image.getRGB(0, 0, w, h, null, 0, w), out = new int[in.length];
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int sx, sy;
                switch (orientation) {
                    case 2 -> { sx = w - 1 - x; sy = y; }
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }
                    case 4 -> { sx = x; sy = h - 1 - y; }
                    case 5 -> { sx = y; sy = x; }
                    case 6 -> { sx = y; sy = h - 1 - x; }
                    case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }
                    default -> { sx = w - 1 - y; sy = x; }
                }
                out[y * outW + x] = in[sy * w + sx];
            }
        }
        BufferedImage result = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, outW, outH, out, 0, outW);
        return result;
    }
}
