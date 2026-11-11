package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Two accounts as buddies (V30): {@code PENDING} while the account that did not ask has not accepted, then
 * {@code ACCEPTED}. Declining, cancelling and removing delete the row; both accounts' rows go with either account.
 */
@Entity @Table(name = Buddy.TABLE)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Buddy {
    static final String TABLE = "buddy";
    public enum Status { PENDING, ACCEPTED }
    @EmbeddedId @EqualsAndHashCode.Include private BuddyId id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status;
    @Column(name = "requested_by", nullable = false) private UUID requestedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "accepted_at") private Instant acceptedAt;

    /** A new request of {@code requestedBy} to the other account of {@code id}. */
    public Buddy(BuddyId id, UUID requestedBy, Instant now) {
        this.id = id;
        this.status = Status.PENDING;
        this.requestedBy = requestedBy;
        this.createdAt = now;
    }

    public boolean accepted() { return status == Status.ACCEPTED; }

    /** A pending request that {@code userId} received (they did not ask). */
    public boolean pendingFor(UUID userId) { return status == Status.PENDING && !requestedBy.equals(userId); }

    /** A pending request that {@code userId} sent. */
    public boolean pendingFrom(UUID userId) { return status == Status.PENDING && requestedBy.equals(userId); }

    public void accept(Instant now) {
        status = Status.ACCEPTED;
        acceptedAt = now;
    }
}
