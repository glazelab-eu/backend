package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One entry of a club's wishlist (V40): a catalogue glaze ({@link Type#GLAZE}) or another item with a name and a link
 * ({@link Type#ITEM}), with an optional note. It belongs to the club; {@code addedBy} is the member who added it, cleared
 * when they leave the club and nulled with their account, so the API never names a former member.
 */
@Entity @Table(name = "club_wishlist")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class WishlistEntry {
    /** What an entry names; a shared string enum (frontend {@code lib/clubs/wishlist.ts}). */
    public enum Type { GLAZE, ITEM }

    public static final int MAX_NAME = 120;
    public static final int MAX_NOTE = 200;

    @Id @EqualsAndHashCode.Include private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "club_id", nullable = false) @ToString.Exclude private Club club;
    @Enumerated(EnumType.STRING) @Column(name = "entry_type", nullable = false, length = 10) private Type type;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "glaze_id") @ToString.Exclude private Glaze glaze;
    @Column(name = "item_name", length = MAX_NAME) @ToString.Exclude private String itemName;
    @Column(length = WishlistLinks.MAX_LENGTH) @ToString.Exclude private String link;
    @Column(length = MAX_NOTE) @ToString.Exclude private String note;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by") @ToString.Exclude private AppUser addedBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    /** When a club admin or the adder marked it acquired; null while it is still wanted. */
    @Column(name = "acquired_at") @Setter private Instant acquiredAt;

    private WishlistEntry(Club club, Type type, Glaze glaze, String itemName, String link, String note, AppUser addedBy) {
        this.id = UUID.randomUUID();
        this.club = club;
        this.type = type;
        this.glaze = glaze;
        this.itemName = itemName;
        this.link = link;
        this.note = note;
        this.addedBy = addedBy;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static WishlistEntry glaze(Club club, Glaze glaze, String note, AppUser addedBy) {
        return new WishlistEntry(club, Type.GLAZE, glaze, null, null, note, addedBy);
    }

    public static WishlistEntry item(Club club, String name, String link, String note, AppUser addedBy) {
        return new WishlistEntry(club, Type.ITEM, null, name, link, note, addedBy);
    }

    /**
     * The single rule for removing an entry and marking it acquired or wanted again: an ADMIN of the club, or the member
     * who added it while they still are one ({@code addedById} is cleared when they leave).
     */
    public static boolean manageableBy(UUID addedById, UUID userId, boolean clubAdmin) {
        return clubAdmin || userId.equals(addedById);
    }
}
