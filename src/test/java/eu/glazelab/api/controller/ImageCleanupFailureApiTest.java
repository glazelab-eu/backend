package eu.glazelab.api.controller;

import eu.glazelab.api.repository.ImageDeletionRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

/** Its own context keeps the failing adapter out of the ordinary deletion/storage tests. */
@Import(ImageCleanupFailureApiTest.StorageConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ImageCleanupFailureApiTest extends ApiTestBase {
    @Autowired FailingCleanupStorage storage;
    @Autowired ImageRecordRepository images;
    @Autowired ImageDeletionRepository deletions;
    @Autowired eu.glazelab.api.repository.AppUserRepository users;

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfig {
        @Bean @Primary
        FailingCleanupStorage failingCleanupStorage(@Qualifier("imageStorage") ImageStorage delegate) {
            return new FailingCleanupStorage(delegate);
        }
    }

    static class FailingCleanupStorage implements ImageStorage {
        final ImageStorage delegate;
        final Map<String, Boolean> attempts = new ConcurrentHashMap<>();
        volatile Runnable onDelete = () -> {};

        FailingCleanupStorage(ImageStorage delegate) { this.delegate = delegate; }
        public void put(String key, byte[] jpeg) throws IOException { delegate.put(key, jpeg); }
        public byte[] get(String key) throws IOException { return delegate.get(key); }
        public void delete(String key) throws IOException {
            attempts.put(key, TransactionSynchronizationManager.isActualTransactionActive());
            onDelete.run();
            throw new IOException("Simulated storage cleanup failure");
        }
    }

    @Test void imageDeletionCommitsAndKeepsItsLogWhenObjectCleanupFails(CapturedOutput output) throws Exception {
        var owner = user("CleanupOwner");
        var actor = siteAdmin();
        var glaze = addGlaze(owner, unique("Cleanup"), "Blue", "1");
        var image = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        String key = images.findById(image).orElseThrow().getStorageKey();
        byte[] bytes = storage.get(key);

        actor.delete().uri("/api/images/{id}", image).exchange().expectStatus().isNoContent();

        assertFalse(images.existsById(image));
        var log = deletions.findAll().stream().filter(row -> row.getImageId().equals(image)).findFirst().orElseThrow();
        assertEquals(glaze, log.getGlazeId());
        assertEquals(me(actor).id(), log.getDeletedBy());
        assertEquals(Boolean.FALSE, storage.attempts.get(key), "Cleanup must run outside the committed transaction");
        assertEquals(Boolean.FALSE, storage.attempts.get(ImageStorage.cardKey(key)), "a failed full-size delete still tries the card copy");
        assertArrayEquals(bytes, storage.get(key));
        guest().get().uri("/api/images/{id}", image).exchange().expectStatus().isNotFound();
        assertTrue(output.getAll().contains("Could not delete stored image " + key + " after image removal"));
        assertTrue(output.getAll().contains("Could not delete the card copy of stored image " + key + " after image removal"));
    }

    @Test void glazeRemovalCommitsAllLogsAndAttemptsEveryObjectDespiteCleanupFailures(CapturedOutput output) throws Exception {
        var owner = user("GlazeCleanupOwner");
        var admin = siteAdmin();
        var glaze = addGlaze(owner, unique("Cleanup"), "Green", "1");
        var first = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        var second = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PRIVATE", "kind", "FINISHED"), TestImages.png(24, 24)));
        var keys = Map.of(first, images.findById(first).orElseThrow().getStorageKey(),
                second, images.findById(second).orElseThrow().getStorageKey());
        var bytes = new java.util.HashMap<String, byte[]>();
        for (String key : keys.values()) bytes.put(key, storage.get(key));

        admin.delete().uri("/api/glazes/{id}", glaze).exchange().expectStatus().isNoContent();

        assertTrue(glazes(guest()).stream().noneMatch(row -> row.id().equals(glaze)));
        for (var entry : keys.entrySet()) {
            assertFalse(images.existsById(entry.getKey()));
            var log = deletions.findAll().stream().filter(row -> row.getImageId().equals(entry.getKey())).findFirst().orElseThrow();
            assertEquals(glaze, log.getGlazeId());
            assertEquals("Green", log.getTitle());
            assertEquals(me(admin).id(), log.getDeletedBy());
            assertEquals(Boolean.FALSE, storage.attempts.get(entry.getValue()));
            assertEquals(Boolean.FALSE, storage.attempts.get(ImageStorage.cardKey(entry.getValue())));
            assertArrayEquals(bytes.get(entry.getValue()), storage.get(entry.getValue()));
            assertTrue(output.getAll().contains("Could not delete stored image " + entry.getValue()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void accountDeletionKeepsAnonymousLogWhenObjectCleanupFails(boolean clubOnly, CapturedOutput output) throws Exception {
        var owner = user("AccountCleanupOwner");
        var userId = me(owner).id();
        var glaze = addGlaze(owner, unique("Cleanup"), "Blue", "1");
        var club = clubOnly ? createClub(owner, unique("Cleanup club")) : null;
        var settings = clubOnly
                ? Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "TEST_TILE")
                : Map.of("visibility", "PRIVATE", "kind", "TEST_TILE");
        var image = created(uploadGlazeImage(owner, glaze, settings, TestImages.png(24, 24)));
        String key = images.findById(image).orElseThrow().getStorageKey();
        byte[] bytes = storage.get(key);
        var accountAtCleanup = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        storage.onDelete = () -> accountAtCleanup.set(users.existsById(userId));
        try {
            owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        } finally {
            storage.onDelete = () -> {};
        }
        assertEquals(Boolean.FALSE, accountAtCleanup.get(), "Cleanup must follow the account deletion's commit");
        assertFalse(users.existsById(userId));
        if (club != null) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, club));
        assertFalse(images.existsById(image));
        var row = deletions.findAll().stream().filter(d -> d.getImageId().equals(image)).findFirst().orElseThrow();
        assertNull(row.getUploaderId());
        assertNull(row.getDeletedBy());
        assertEquals(Boolean.FALSE, storage.attempts.get(key));
        assertArrayEquals(bytes, storage.get(key));
        assertTrue(output.getAll().contains("Could not delete stored image " + key + " after image removal"));
    }
}
