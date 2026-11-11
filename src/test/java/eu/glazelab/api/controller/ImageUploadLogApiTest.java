package eu.glazelab.api.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refused uploads and the busy encoder (backend guide "Logs"): one line per reason class and minute with a count, never
 * the file name, a caption, the size or an ID.
 */
class ImageUploadLogApiTest extends ApiTestBase {
    private static final String FILE_NAME = "canary-file-name.png", CAPTION = "Canary caption";
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach void attach() { ImageEncoder.REFUSALS.reset(); captured.start(); root.addAppender(captured); }
    @AfterEach void detach() { root.detachAppender(captured); }

    private List<String> lines(String prefix) {
        return List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).filter(line -> line.startsWith(prefix)).toList();
    }

    private static int upload(RestTestClient client, UUID glaze, byte[] file) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new HttpEntity<>(new ByteArrayResource(file) {
            @Override public String getFilename() { return FILE_NAME; }
        }, headers));
        parts.add("visibility", "PRIVATE");
        parts.add("kind", "TEST_TILE");
        parts.add("caption", CAPTION);
        return client.post().uri("/api/glazes/{id}/images", glaze).contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                .exchange().expectBody(String.class).returnResult().getStatus().value();
    }

    /** A PNG whose header claims {@code side} x {@code side} pixels (the reader refuses it before decoding). */
    private static byte[] huge(int side) {
        byte[] png = TestImages.png(2, 2);
        ByteBuffer.wrap(png, 16, 8).putInt(side).putInt(side);
        var crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());
        return png;
    }

    @Test void refusalsAreCountedPerReasonClassWithoutTheFileOrIds() {
        var uploader = user("Refused");
        UUID glaze = addGlaze(uploader, unique("Maker"), "Blue", "R-" + shortId());
        for (int i = 0; i < 3; i++) assertThat(upload(uploader, glaze, "not an image".getBytes())).isEqualTo(400);
        assertThat(upload(uploader, glaze, huge(10_000))).isEqualTo(400);
        assertThat(upload(uploader, glaze, TestImages.pngWithBrokenData(24, 24))).isEqualTo(400);
        assertThat(upload(uploader, glaze, new byte[0])).isEqualTo(400);
        var refusals = lines("Image upload refused: ");
        assertThat(refusals).containsExactly(
                "Image upload refused: unsupported format; 1 refusal(s) of this kind since the last line",
                "Image upload refused: too many pixels; 1 refusal(s) of this kind since the last line",
                "Image upload refused: undecodable data; 1 refusal(s) of this kind since the last line",
                "Image upload refused: empty or too large; 1 refusal(s) of this kind since the last line");
        assertThat(String.join("\n", refusals)).doesNotContain(FILE_NAME, CAPTION, glaze.toString(), me(uploader).id().toString());
    }

    @Test void aBusyEncoderLogsOnceAMinuteWithACount() throws Exception {
        var uploader = user("Busy");
        UUID glaze = addGlaze(uploader, unique("Maker"), "Blue", "B-" + shortId());
        ImageEncoder.ENCODING.acquire();
        try {
            for (int i = 0; i < 3; i++) assertThat(upload(uploader, glaze, TestImages.png(8, 8))).isEqualTo(503);
        } finally {
            ImageEncoder.ENCODING.release();
        }
        assertThat(lines("Image encoder busy")).containsExactly("Image encoder busy: 1 upload(s) answered 503 since the last line");
        assertThat(upload(uploader, glaze, TestImages.png(8, 8))).isEqualTo(200);
    }
}
