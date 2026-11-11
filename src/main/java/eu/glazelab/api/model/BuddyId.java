package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Comparator;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * The key of a buddy pair: the two accounts in {@link #ORDER}, so a pair has one row whoever asked
 * ({@code ck_buddy_order}). {@link #of} orders any two distinct IDs.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode
@ToString
public class BuddyId implements Serializable {
    /**
     * The database's UUID order (PostgreSQL and H2 compare the 16 bytes unsigned), which {@link UUID#compareTo}
     * is not: it compares the two halves as signed numbers. The pair key and the account lock order both use it.
     */
    public static final Comparator<UUID> ORDER = Comparator.comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    @Column(name = "user_a") private UUID userA;
    @Column(name = "user_b") private UUID userB;

    private BuddyId(UUID userA, UUID userB) {
        this.userA = userA;
        this.userB = userB;
    }

    /** The pair of two different accounts, smaller ID first. */
    public static BuddyId of(UUID one, UUID other) {
        if (one.equals(other)) throw new IllegalArgumentException("A buddy pair needs two accounts");
        return ORDER.compare(one, other) < 0 ? new BuddyId(one, other) : new BuddyId(other, one);
    }

    /** The account of the pair that is not {@code userId}. */
    public UUID other(UUID userId) {
        return userA.equals(userId) ? userB : userA;
    }
}
