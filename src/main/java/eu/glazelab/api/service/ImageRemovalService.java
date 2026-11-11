package eu.glazelab.api.service;

import eu.glazelab.api.model.ImageDeletion;
import eu.glazelab.api.model.ImageRecord;
import eu.glazelab.api.repository.ImageDeletionRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.storage.ImageStorage;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Locked image records and their audit entries change together; both objects of each picture are removed only on commit
 * (also for profile pictures, {@link #deleteObjectsAfterCommit}).
 */
@Service @RequiredArgsConstructor @Slf4j
public class ImageRemovalService {
    private final ImageDeletionRepository deletions;
    private final ImageRecordRepository images;
    private final ImageStorage storage;
    private final PlatformTransactionManager transactionManager;
    private final ChangeRecorder changes;

    @Transactional(propagation = Propagation.MANDATORY)
    public void remove(List<ImageRecord> pictures, UUID actor) {
        // A draft's photos were never visible to anyone but their author: the moderation log never names a draft.
        deletions.saveAll(pictures.stream().filter(image -> !image.onDraft()).map(image -> new ImageDeletion(image, actor)).toList());
        // Each parent's view loses the photo for whoever could see it.
        pictures.forEach(changes::picture);
        List<String> keys = pictures.stream().map(ImageRecord::getStorageKey).toList();
        images.deleteAll(pictures);
        deleteObjectsAfterCommit(keys);
    }

    /**
     * Deletes both objects of each key (the full size and its card copy; deleting an absent card is a no-op) once the
     * caller's transaction has committed, never on rollback: photo removals and profile pictures share it. Failures
     * only warn: an unreferenced object is harmless, a record without its object is not.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteObjectsAfterCommit(List<String> keys) {
        if (keys.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // afterCommit precedes Spring's resource cleanup. Suspend that completed context for I/O.
                var cleanup = new TransactionTemplate(transactionManager);
                cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                cleanup.executeWithoutResult(status -> {
                    for (String key : keys) {
                        try { storage.delete(key); }
                        catch (Exception error) { log.warn("Could not delete stored image {} after image removal", key); }
                        try { storage.delete(ImageStorage.cardKey(key)); }
                        catch (Exception error) { log.warn("Could not delete the card copy of stored image {} after image removal", key); }
                    }
                });
            }
        });
    }
}
