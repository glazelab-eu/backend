package eu.glazelab.api.controller;

import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * BE-5: every picture download reads the object store without holding a database connection. With a pool of two
 * connections (Flyway needs two at start), two downloads blocked inside {@code storage.get} must leave the pool free
 * for the next request, which would otherwise wait out the pool timeout and fail.
 */
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=2000"})
class PictureConnectionApiTest extends ApiTestBase {
    @MockitoSpyBean ImageStorage storage;
    private volatile String blocked;
    private volatile CountDownLatch entered = new CountDownLatch(2);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach void releaseStore() {
        release.countDown();
    }

    private void blockOn(String key) throws Exception {
        blocked = key;
        entered = new CountDownLatch(2);
        doAnswer(call -> {
            if (call.getArgument(0, String.class).equals(blocked)) {
                entered.countDown();
                assertTrue(release.await(20, TimeUnit.SECONDS));
            }
            return call.callRealMethod();
        }).when(storage).get(anyString());
    }

    /** Starts {@code uri} twice on other threads, waits until it is inside the store, then needs the database meanwhile. */
    private void assertPoolFreeWhileReading(RestTestClient client, String uri, Object id, RestTestClient other) throws Exception {
        var downloads = Stream.generate(() -> CompletableFuture.supplyAsync(() ->
                client.get().uri(uri, id).exchange().returnResult(byte[].class).getStatus().value())).limit(2).toList();
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the downloads never reached the store");
        long start = System.nanoTime();
        me(other);
        assertTrue(System.nanoTime() - start < 1_500_000_000L, "the database was held by the download");
        blocked = null;
        release.countDown();
        for (var download : downloads) assertEquals(200, download.get(20, TimeUnit.SECONDS));
    }

    private String key(String sql, UUID id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    @Test void aPhotoDownloadLeavesThePoolFree() throws Exception {
        RestTestClient ada = user("Ada");
        UUID combination = addCombination(ada, unique("Pool"), addGlaze(ada, unique("Pool"), "Shino", "PC-50"));
        UUID photo = uploadTile(ada, combination, "PRIVATE", null);
        blockOn(ImageStorage.cardKey(key("SELECT storage_key FROM image WHERE id = ?", photo)));
        assertPoolFreeWhileReading(ada, "/api/images/{id}/card", photo, user("Bea"));
    }

    @Test void aFullSizePhotoDownloadLeavesThePoolFree() throws Exception {
        RestTestClient ada = user("Ada");
        UUID combination = addCombination(ada, unique("Pool"), addGlaze(ada, unique("Pool"), "Shino", "PC-51"));
        UUID photo = uploadTile(ada, combination, "PUBLIC", null);
        blockOn(key("SELECT storage_key FROM image WHERE id = ?", photo));
        assertPoolFreeWhileReading(guest(), "/api/images/{id}", photo, ada);
    }

    @Test void aProfilePictureDownloadLeavesThePoolFree() throws Exception {
        RestTestClient ada = user("Ada");
        UUID picture = avatar(ada);
        blockOn(key("SELECT storage_key FROM user_avatar WHERE id = ?", picture));
        assertPoolFreeWhileReading(ada, "/api/avatars/{id}", picture, user("Bea"));
    }

    @Test void aReportedPictureDownloadLeavesThePoolFree() throws Exception {
        RestTestClient ada = user("Ada");
        UUID picture = avatar(ada);
        user("Bea").post().uri("/api/avatars/{id}/reports", picture).body(Map.of()).exchange().expectStatus().isNoContent();
        UUID report = jdbc.queryForObject("SELECT id FROM avatar_report WHERE avatar_id = ?", UUID.class, picture);
        blockOn(key("SELECT storage_key FROM user_avatar WHERE id = ?", picture));
        assertPoolFreeWhileReading(siteAdmin(), "/api/admin/avatar-reports/{id}/picture", report, ada);
    }

    @Test void aFeedbackPhotoDownloadLeavesThePoolFree() throws Exception {
        RestTestClient ada = user("Ada");
        UUID feedback = created(sendFeedback(ada, "Pool", Map.of(), TestImages.png(20, 40)));
        UUID photo = jdbc.queryForObject("SELECT id FROM feedback_photo WHERE feedback_id = ?", UUID.class, feedback);
        blockOn(key("SELECT storage_key FROM feedback_photo WHERE id = ?", photo));
        assertPoolFreeWhileReading(siteAdmin(), "/api/admin/feedback/photos/{id}", photo, ada);
    }
}
