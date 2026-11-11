package eu.glazelab.api.service;

import eu.glazelab.api.dto.FeedbackDtos.FeedbackPageView;
import eu.glazelab.api.dto.FeedbackDtos.FeedbackPhotoView;
import eu.glazelab.api.dto.FeedbackDtos.FeedbackView;
import eu.glazelab.api.model.Feedback;
import eu.glazelab.api.model.FeedbackPhoto;
import eu.glazelab.api.repository.FeedbackPhotoRepository;
import eu.glazelab.api.repository.FeedbackRepository;
import eu.glazelab.api.security.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * In-app feedback (backend guide "Feedback"): stored for the site admins, never shown to anyone else. Sending locks the
 * sender's account row and nothing after it, so an account deletion queued before it leaves nothing and one queued after
 * it deletes what it stored. Site admins' marks and deletions are single statements and take no lock. Stored objects go
 * after commit, never on rollback; nothing of a feedback (text, context, file names) is ever logged.
 */
@Service @RequiredArgsConstructor @Slf4j
public class FeedbackService {
    public static final String FEEDBACK_NOT_FOUND = "Feedback not found";

    private final FeedbackRepository feedback;
    private final FeedbackPhotoRepository photos;
    private final CurrentUser current;
    private final ImageRemovalService removal;
    private final TransactionTemplate transactions;

    /** How long a feedback stays after a site admin marked it done (the terms name it). */
    @Value("${glazelab.feedback.retention}") private Duration retention;

    /** The context the app attached (each part null when the sender left it out). */
    public record Context(String route, String build, String language, Feedback.Device device) {}

    /** A stored photo: its storage key and size. */
    public record StoredPhoto(String key, int width, int height) {}

    /** Stores one feedback with its photos (already written to storage); under the sender's account lock. */
    @Transactional
    public UUID send(UUID userId, String message, Context context, List<StoredPhoto> stored) {
        current.lockUser(userId);
        Feedback saved = feedback.saveAndFlush(new Feedback(userId, message, context.route(), context.build(),
                context.language(), context.device(), Instant.now()));
        for (int i = 0; i < stored.size(); i++)
            photos.save(new FeedbackPhoto(saved.getId(), i, stored.get(i).key(), stored.get(i).width(), stored.get(i).height()));
        photos.flush();
        return saved.getId();
    }

    /** A page of the site admins' list (newest first): one slice statement, one for the page's photos, one count. */
    @Transactional(readOnly = true)
    public FeedbackPageView page(Feedback.Status status, Pageable page) {
        var rows = status == null ? feedback.listing(page) : feedback.listing(status, page);
        List<UUID> ids = rows.stream().map(row -> row.getFeedback().getId()).toList();
        Map<UUID, List<FeedbackPhotoView>> pictures = ids.isEmpty() ? Map.of()
                : feedback.photosOf(ids).stream().collect(Collectors.groupingBy(FeedbackPhoto::getFeedbackId,
                        Collectors.mapping(photo -> new FeedbackPhotoView(photo.getId(), photo.getWidth(), photo.getHeight()), Collectors.toList())));
        List<FeedbackView> entries = rows.stream().map(row -> {
            Feedback item = row.getFeedback();
            return new FeedbackView(item.getId(), item.getMessage(), item.getRouteName(), item.getBuildLabel(), item.getLanguage(),
                    item.getDevice(), item.getUserId(), row.getSender(), item.getCreatedAt(), item.getStatus(), item.getDoneAt(),
                    pictures.getOrDefault(item.getId(), List.of()));
        }).toList();
        return new FeedbackPageView(entries, rows.hasNext(), feedback.countByStatus(Feedback.Status.OPEN));
    }

    /**
     * Marks a feedback done (from now on its retention runs; marking it done again, as an admin on an out-of-date page may, keeps the
     * first time) or open again (it no longer runs); 404 when it is gone.
     */
    @Transactional
    public void mark(UUID id, Feedback.Status status) {
        int changed = status == Feedback.Status.DONE ? feedback.markDone(id, Instant.now()) : feedback.reopen(id);
        if (changed != 1) throw notFound();
    }

    /** A site admin deletes a feedback with its photos (objects after commit); 404 when it is gone. */
    @Transactional
    public void delete(UUID id) {
        List<String> keys = feedback.keysOf(id);
        if (feedback.deleteOne(id) != 1) throw notFound();
        removal.deleteObjectsAfterCommit(keys);
    }

    /** Every feedback of the account, inside its deletion, a ban or the pending purge ({@code AccountRows}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeOf(UUID userId) {
        List<String> keys = new ArrayList<>(feedback.keysOfUser(userId));
        feedback.deleteOfUser(userId);
        removal.deleteObjectsAfterCommit(keys);
    }

    /** Daily: deletes feedback marked done longer than the retention ago, with its photos. Logs the count only. */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public int purge() {
        Integer removed = transactions.execute(status -> {
            Instant before = Instant.now().minus(retention);
            List<String> keys = feedback.expiredKeys(before);
            int count = feedback.purge(before);
            removal.deleteObjectsAfterCommit(keys);
            return count;
        });
        if (removed != null && removed > 0) log.info("Deleted {} feedback entries past their retention", removed);
        return removed == null ? 0 : removed;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, FEEDBACK_NOT_FOUND);
    }
}
