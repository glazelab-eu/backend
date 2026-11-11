package eu.glazelab.api.controller;

import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import static org.junit.jupiter.api.Assertions.*;

/** A failed upload leaves neither of the picture's objects behind. Its own context keeps the hooked adapter out of other tests. */
@Import(ImageUploadCleanupApiTest.StorageConfig.class)
class ImageUploadCleanupApiTest extends ApiTestBase {
    @Autowired HookedStorage storage;

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfig {
        @Bean @Primary
        HookedStorage hookedStorage(@Qualifier("imageStorage") ImageStorage delegate) { return new HookedStorage(delegate); }
    }

    /** Records every key written and runs {@code afterPut} after each write (it may throw to fail that write). */
    static class HookedStorage implements ImageStorage {
        final ImageStorage delegate;
        final List<String> written = new CopyOnWriteArrayList<>();
        volatile ThrowingHook afterPut = key -> {};

        interface ThrowingHook { void run(String key) throws IOException; }

        HookedStorage(ImageStorage delegate) { this.delegate = delegate; }
        public void put(String key, byte[] jpeg) throws IOException {
            delegate.put(key, jpeg);
            written.add(key);
            afterPut.run(key);
        }
        public byte[] get(String key) throws IOException { return delegate.get(key); }
        public void delete(String key) throws IOException { delegate.delete(key); }
    }

    @AfterEach void reset() {
        storage.afterPut = key -> {};
        storage.written.clear();
    }

    private void assertNothingLeft() throws IOException {
        assertEquals(2, storage.written.size(), "full size and card were written: " + storage.written);
        for (String key : storage.written)
            assertThrows(NoSuchFileException.class, () -> storage.get(key), key + " should be removed");
    }

    @Test void failedDatabaseWorkRemovesBothObjects() throws IOException {
        var ada = user("Ada");
        UUID club = createClub(ada, unique("Kiln"));
        UUID combo = addCombination(ada, unique("Cleanup"), addGlaze(ada, unique("Cleanup"), "Blue", "PC-20"));
        UUID adaId = me(ada).id();
        // Ada leaves the club after both objects are stored, so the membership recheck under the club lock refuses the upload.
        storage.afterPut = key -> {
            if (key.endsWith(".card.jpg")) jdbc.update("delete from membership where club_id = ? and user_id = ?", club, adaId);
        };
        var fields = Map.of("visibility", "CLUB", "kind", "TEST_TILE", "clubId", club.toString());
        upload(ada, combo, fields, PNG).expectStatus().isForbidden();
        assertNothingLeft();
    }

    /** Feedback stores up to three photos before its transaction: one failed write removes every object written, and no row stays. */
    @Test void aFailedFeedbackPhotoRemovesEveryObjectOfTheFeedback() throws IOException {
        String email = email("Cleanup");
        var sender = signUp(email);
        storage.afterPut = key -> {
            if (storage.written.size() == 6) throw new IOException("Simulated write failure of the third card");
        };
        sendFeedback(sender, "Three photos", Map.of(), PNG, PNG, PNG).expectStatus().is5xxServerError();
        assertEquals(6, storage.written.size(), storage.written.toString());
        for (String key : storage.written)
            assertThrows(NoSuchFileException.class, () -> storage.get(key), key + " should be removed");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feedback f JOIN app_user u ON u.id = f.user_id WHERE u.email = ?",
                Integer.class, email));
    }

    @Test void aFailedCardWriteRemovesTheFullSize() throws IOException {
        var ada = user("Ada");
        UUID combo = addCombination(ada, unique("Cleanup"), addGlaze(ada, unique("Cleanup"), "Blue", "PC-20"));
        storage.afterPut = key -> {
            if (key.endsWith(".card.jpg")) throw new IOException("Simulated card write failure");
        };
        upload(ada, combo, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), PNG).expectStatus().is5xxServerError();
        assertNothingLeft();
    }
}
