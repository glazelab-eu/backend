package eu.glazelab.api.controller;

import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.FakeS3;
import eu.glazelab.api.support.TestImages;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** Both objects of a picture through the S3 adapter (an in-process fake bucket): stored on upload, removed with the picture. */
class S3ImageApiTest extends ApiTestBase {
    private static final String BUCKET = "/glazelab-images/";
    private static final FakeS3 S3 = start();

    @Autowired ImageRecordRepository records;

    private static FakeS3 start() {
        try { return new FakeS3(); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }

    @DynamicPropertySource static void s3(DynamicPropertyRegistry registry) {
        registry.add("glazelab.storage", () -> "s3");
        registry.add("glazelab.s3.endpoint", S3::endpoint);
        registry.add("glazelab.s3.region", () -> "fsn1");
        registry.add("glazelab.s3.bucket", () -> "glazelab-images");
        registry.add("glazelab.s3.access-key", () -> "TESTACCESSKEY");
        registry.add("glazelab.s3.secret-key", () -> "TESTSECRETKEY");
        registry.add("glazelab.s3.path-style", () -> "true");
    }

    @AfterAll static void stop() { S3.close(); }

    @Test void uploadStoresBothObjectsAndDeletionRemovesBoth() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Bucket"), "Blue", "PC-20");
        UUID id = created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.photo(2000, 1500)));
        String key = records.findById(id).orElseThrow().getStorageKey();
        Set<String> objects = Set.of(BUCKET + key, BUCKET + ImageStorage.cardKey(key));
        assertTrue(S3.objects.keySet().containsAll(objects), S3.objects.keySet().toString());
        byte[] card = guest().get().uri("/api/images/{id}/card", id).exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertArrayEquals(S3.objects.get(BUCKET + ImageStorage.cardKey(key)), card);
        assertTrue(card.length * 5 < S3.objects.get(BUCKET + key).length);

        ada.delete().uri("/api/images/{id}", id).exchange().expectStatus().isNoContent();
        for (String object : objects) assertFalse(S3.objects.containsKey(object), object);
        guest().get().uri("/api/images/{id}/card", id).exchange().expectStatus().isNotFound();
    }
}
