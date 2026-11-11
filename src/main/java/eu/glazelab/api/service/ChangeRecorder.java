package eu.glazelab.api.service;

import eu.glazelab.api.model.ChangeLogEntry.Audience;
import eu.glazelab.api.model.ChangeLogEntry.Kind;
import eu.glazelab.api.model.Combination;
import eu.glazelab.api.model.ImageRecord;
import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.repository.ChangeLogRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one writer of the sync change log: every write that changes what a glaze or combination view shows calls it
 * inside its transaction (new write paths too; {@code SyncCoverageApiTest} fails for an operation that neither
 * records nor is exempt). Calls only collect; the rows are inserted in one batch in {@code beforeCommit}, after a
 * flush, so the insert is the transaction's last statement, a rollback writes nothing and repeated calls for one
 * item and audience write one row. The insert takes no lock another transaction holds (no foreign or unique keys
 * but the identity), so it never changes a lock order.
 * <p>
 * Audience = who could see the change before OR after it: call once with the state before and once after (photo
 * visibility moves). A row reaches a client only if the client is in the audience; the delta then renders the
 * item for that viewer, or a tombstone when it is no longer visible to them. An item that has a PUBLIC row in the
 * transaction gets only that row.
 */
@Component @RequiredArgsConstructor
public class ChangeRecorder {
    private final JdbcTemplate jdbc;
    // Its flush translates a constraint violation like the commit's own flush would (DataIntegrityViolationException).
    private final ChangeLogRepository changeLog;

    /** Glaze fields, tags, barcodes, creation and removal: every glaze is public. */
    public void glaze(UUID glazeId) {
        add(Kind.GLAZE, glazeId, Audience.PUBLIC, null);
    }

    /** A combination's own fields, tags, creation, publication or removal: everyone, or only the author of a draft. */
    public void combination(Combination combination) {
        combination(combination.getId(), combination.getCreatedBy() == null ? null : combination.getCreatedBy().getId(),
                !combination.isDraft());
    }

    public void combination(UUID id, UUID authorId, boolean published) {
        if (published) add(Kind.COMBINATION, id, Audience.PUBLIC, null);
        // A draft always has its author (account deletion removes drafts); without one nobody could see it.
        else if (authorId != null) add(Kind.COMBINATION, id, Audience.USER, authorId);
    }

    /**
     * A photo as it is now (call before and after a visibility change): its parent, for whoever may see the photo
     * (its effective visibility, so a draft's photo names only its author).
     */
    public void picture(ImageRecord image) {
        picture(image.getGlazeId(), image.getCombinationId(), image.effectiveVisibility(), image.getClubId(), image.getUploaderId());
    }

    /**
     * The audience of {@code ImageRecord.visibleTo} for an effective visibility: PUBLIC everyone; CLUB the club's members and the uploader (who
     * keeps seeing it after leaving); PRIVATE the uploader.
     */
    public void picture(UUID glazeId, UUID combinationId, ImageVisibility visibility, UUID clubId, UUID uploaderId) {
        Kind kind = glazeId != null ? Kind.GLAZE : Kind.COMBINATION;
        UUID item = glazeId != null ? glazeId : combinationId;
        if (visibility == ImageVisibility.PUBLIC) {
            add(kind, item, Audience.PUBLIC, null);
            return;
        }
        if (visibility == ImageVisibility.CLUB && clubId != null) add(kind, item, Audience.CLUB, clubId);
        if (uploaderId != null) add(kind, item, Audience.USER, uploaderId);
    }

    private record Item(Kind kind, UUID id) {}
    private record Target(Audience audience, UUID id) {}

    private void add(Kind kind, UUID item, Audience audience, UUID audienceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Changes are recorded inside the writing transaction");
        pending().add(new Item(kind, item), new Target(audience, audienceId));
    }

    /** This transaction's collector; synchronizations are suspended with their transaction, so nesting stays separate. */
    private Pending pending() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations())
            if (synchronization instanceof Pending pending && pending.owner == this) return pending;
        Pending pending = new Pending(this);
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    private static final class Pending implements TransactionSynchronization {
        private final ChangeRecorder owner;
        private final Map<Item, Set<Target>> changes = new LinkedHashMap<>();

        Pending(ChangeRecorder owner) { this.owner = owner; }

        void add(Item item, Target target) {
            changes.computeIfAbsent(item, ignored -> new LinkedHashSet<>()).add(target);
        }

        /** Last among the synchronizations, so work another one flushes in beforeCommit is in place first. */
        @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

        @Override public void beforeCommit(boolean readOnly) {
            // Pending entity changes go first: the log insert is the transaction's last statement.
            owner.changeLog.flush();
            // Two statements, so no NULL UUID is bound (H2 and PostgreSQL type an untyped NULL differently).
            List<Object[]> everyone = new ArrayList<>(), named = new ArrayList<>();
            OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
            Target all = new Target(Audience.PUBLIC, null);
            changes.forEach((item, targets) -> {
                if (targets.contains(all)) everyone.add(new Object[] {now, item.kind().name(), item.id()});
                else for (Target target : targets)
                    named.add(new Object[] {now, item.kind().name(), item.id(), target.audience().name(), target.id()});
            });
            if (!everyone.isEmpty())
                owner.jdbc.batchUpdate("INSERT INTO change_log (changed_at, kind, item_id, audience) VALUES (?, ?, ?, 'PUBLIC')", everyone);
            if (!named.isEmpty())
                owner.jdbc.batchUpdate("INSERT INTO change_log (changed_at, kind, item_id, audience, audience_id) VALUES (?, ?, ?, ?, ?)", named);
        }
    }
}
