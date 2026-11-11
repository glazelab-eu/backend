package eu.glazelab.api.controller;

import eu.glazelab.api.storage.ImageStorage;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one answer of a stored picture (photos, profile pictures, feedback photos, reported pictures): the caller looks
 * up the record and checks access first, then this reads the object outside any transaction, so no database
 * connection waits for the object store (a slow S3 would otherwise hold the pool).
 */
final class Pictures {
    private Pictures() {}

    /**
     * The first of {@code keys} that is stored, as JPEG with {@code cache}; {@code X-Accel-Expires} is 60 for a PUBLIC
     * photo (Nginx may keep it that long) and 0 for everything else. {@code notFound} when none is stored.
     */
    static ResponseEntity<byte[]> answer(ImageStorage storage, CacheControl cache, boolean publicPhoto,
            Supplier<ResponseStatusException> notFound, String... keys) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Picture read inside a transaction");
        for (String key : keys) {
            byte[] jpeg;
            try { jpeg = storage.get(key); }
            catch (NoSuchFileException missing) { continue; }
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(cache)
                    .header("X-Accel-Expires", publicPhoto ? "60" : "0").body(jpeg);
        }
        throw notFound.get();
    }
}
