package eu.glazelab.api.storage;

import eu.glazelab.api.support.FakeS3;
import eu.glazelab.api.support.FakeS3.Request;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The S3 adapter against a minimal in-process S3 endpoint (always runs, no network service), and optionally
 * against a real S3-compatible server when GLAZELAB_TEST_S3_ENDPOINT is set (skipped otherwise).
 */
class S3ImageStorageTest {
    private FakeS3 s3;
    private List<Request> requests;

    @BeforeEach void start() throws IOException {
        s3 = new FakeS3();
        requests = s3.requests;
    }

    @AfterEach void stop() { s3.close(); }

    private S3ImageStorage storage() {
        return new S3ImageStorage(new S3Settings(s3.endpoint(), "fsn1",
                "glazelab-images", "TESTACCESSKEY", "TESTSECRETKEY", true));
    }

    private static String key() { return UUID.randomUUID() + ".jpg"; }

    @Test void storesReadsAndDeletesObjectsInTheBucket() throws IOException {
        String key = key();
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9};
        try (S3ImageStorage storage = storage()) {
            storage.put(key, jpeg);
            assertArrayEquals(jpeg, storage.get(key));
            storage.delete(key);
            assertThrows(NoSuchFileException.class, () -> storage.get(key));
        }
        Request put = requests.getFirst();
        assertEquals("PUT", put.method());
        assertEquals("/glazelab-images/" + key, put.path(), "path-style object URL");
        assertEquals("image/jpeg", put.header("Content-Type"));
        assertArrayEquals(jpeg, put.body());
        String auth = put.header("Authorization");
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=TESTACCESSKEY/"), auth);
        assertTrue(auth.contains("/fsn1/s3/aws4_request"), auth);
        assertFalse(auth.contains("TESTSECRETKEY"), auth);
        // Plain signed uploads: no aws-chunked encoding or checksum trailers, which some S3-compatible stores reject.
        assertNull(put.header("x-amz-trailer"));
        assertNull(put.header("x-amz-decoded-content-length"));
        assertFalse(String.valueOf(put.header("x-amz-content-sha256")).startsWith("STREAMING"));
        assertEquals(List.of("PUT", "GET", "DELETE", "GET"), requests.stream().map(Request::method).toList());
    }

    @Test void missingObjectsAreReportedLikeMissingFiles() throws IOException {
        try (S3ImageStorage storage = storage()) {
            assertThrows(NoSuchFileException.class, () -> storage.get(key()));
        }
    }

    @Test void serviceErrorsBecomeGenericIoErrors() throws IOException {
        s3.failing = true;
        try (S3ImageStorage storage = storage()) {
            IOException error = assertThrows(IOException.class, () -> storage.put(key(), new byte[] {1}));
            assertFalse(error instanceof NoSuchFileException);
            assertEquals("Image storage is unavailable", error.getMessage());
            IOException read = assertThrows(IOException.class, () -> storage.get(key()));
            assertFalse(read instanceof NoSuchFileException);
        }
    }

    @Test void theCardCopyIsAnObjectBesideTheFullSize() throws IOException {
        String key = key();
        String card = ImageStorage.cardKey(key);
        assertEquals(key.replace(".jpg", ".card.jpg"), card);
        try (S3ImageStorage storage = storage()) {
            storage.put(key, new byte[] {1});
            storage.put(card, new byte[] {2});
            assertArrayEquals(new byte[] {2}, storage.get(card));
            storage.delete(card);
            assertThrows(NoSuchFileException.class, () -> storage.get(card));
            assertArrayEquals(new byte[] {1}, storage.get(key));
        }
        assertEquals("/glazelab-images/" + card, requests.get(1).path());
    }

    @Test void invalidKeysNeverReachTheService() throws IOException {
        try (S3ImageStorage storage = storage()) {
            assertThrows(IllegalArgumentException.class, () -> storage.get("../other-bucket/x.jpg"));
            assertThrows(IllegalArgumentException.class, () -> storage.put("x", new byte[] {1}));
            assertThrows(IllegalArgumentException.class, () -> storage.get(UUID.randomUUID() + ".card.card.jpg"));
            assertThrows(IllegalArgumentException.class, () -> ImageStorage.cardKey(UUID.randomUUID() + ".card.jpg"));
        }
        assertEquals(List.of(), requests);
    }

    /**
     * Run against a disposable S3-compatible server, for example MinIO:
     * GLAZELAB_TEST_S3_ENDPOINT=http://127.0.0.1:9000 GLAZELAB_TEST_S3_BUCKET=test GLAZELAB_TEST_S3_ACCESS_KEY=…
     * GLAZELAB_TEST_S3_SECRET_KEY=… [GLAZELAB_TEST_S3_REGION=us-east-1] [GLAZELAB_TEST_S3_PATH_STYLE=true] mvn test
     */
    @Nested @EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_S3_ENDPOINT", matches = ".+")
    class RealServer {
        private S3ImageStorage real() {
            var env = System.getenv();
            return new S3ImageStorage(new S3Settings(env.get("GLAZELAB_TEST_S3_ENDPOINT"),
                    env.getOrDefault("GLAZELAB_TEST_S3_REGION", "us-east-1"), env.get("GLAZELAB_TEST_S3_BUCKET"),
                    env.get("GLAZELAB_TEST_S3_ACCESS_KEY"), env.get("GLAZELAB_TEST_S3_SECRET_KEY"),
                    Boolean.parseBoolean(env.getOrDefault("GLAZELAB_TEST_S3_PATH_STYLE", "true"))));
        }

        @Test void roundTripAndMissingObjects() throws IOException {
            String key = key();
            byte[] jpeg = new byte[300_000];
            new java.util.Random(7).nextBytes(jpeg);
            try (S3ImageStorage storage = real()) {
                storage.put(key, jpeg);
                assertArrayEquals(jpeg, storage.get(key));
                storage.delete(key);
                assertThrows(NoSuchFileException.class, () -> storage.get(key));
                storage.delete(key);
            }
        }

        // Photos are two objects (<uuid>.jpg and <uuid>.card.jpg). The card fallback relies on a missing key
        // answering NoSuchKey/404 (not 403, which a list-less identity could get), and deleting a missing key is a no-op.
        @Test void cardObjectBesideTheFullSizeAndMissingKeys() throws IOException {
            String key = key();
            String card = ImageStorage.cardKey(key);
            try (S3ImageStorage storage = real()) {
                assertThrows(NoSuchFileException.class, () -> storage.get(key));
                assertThrows(NoSuchFileException.class, () -> storage.get(card));
                storage.delete(card);
                storage.put(key, new byte[] {1, 2, 3});
                assertThrows(NoSuchFileException.class, () -> storage.get(card));
                storage.put(card, new byte[] {4, 5});
                assertArrayEquals(new byte[] {1, 2, 3}, storage.get(key));
                assertArrayEquals(new byte[] {4, 5}, storage.get(card));
                storage.delete(card);
                assertThrows(NoSuchFileException.class, () -> storage.get(card));
                assertArrayEquals(new byte[] {1, 2, 3}, storage.get(key));
                storage.delete(key);
                assertThrows(NoSuchFileException.class, () -> storage.get(key));
            }
        }
    }
}
