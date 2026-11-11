package eu.glazelab.api.model;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity @Table(name = Stock.TABLE, indexes = {
        @Index(name = "stock_user_unique", columnList = Stock.GLAZE_ID + "," + Stock.USER_ID, unique = true),
        @Index(name = "stock_club_unique", columnList = Stock.GLAZE_ID + "," + Stock.CLUB_ID, unique = true) },
        check = @CheckConstraint(constraint = Stock.QUANTITY_G + " >= 0 AND ((" + Stock.USER_ID + " IS NULL) <> ("
                + Stock.CLUB_ID + " IS NULL))"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Stock {
    static final String TABLE = "stock";
    static final String GLAZE_ID = "glaze_id";
    static final String USER_ID = "user_id";
    static final String CLUB_ID = "club_id";
    static final String QUANTITY_G = "quantity_g";
    static final String JAR_SIZE_G = "jar_size_g";
    static final String ADDED_BY = "added_by";
    static final String KEEP_PRIVATE = "keep_private";
    @Id @EqualsAndHashCode.Include private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = GLAZE_ID, nullable = false) @ToString.Exclude private Glaze glaze;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = USER_ID) @ToString.Exclude private AppUser user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = CLUB_ID) @ToString.Exclude private Club club;
    @Column(name = QUANTITY_G, nullable = false) @Setter private int quantityG;
    /** Grams per jar when known (V19, positive); "+1 jar" adds it to the amount. */
    @Column(name = JAR_SIZE_G) @Setter private Integer jarSizeG;
    /**
     * The member who put a club entry on the shelf (V27): information and their removal right only, cleared when
     * they leave the club or delete their account; never set on personal stock ({@code ck_stock_added_by}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = ADDED_BY) @ToString.Exclude private AppUser addedBy;
    /**
     * A personal entry its owner keeps from their buddies (V30, {@code ck_stock_keep_private}: never on club stock,
     * which buddies never see); {@code StockService.keepPrivate} is the only writer.
     */
    @Column(name = KEEP_PRIVATE, nullable = false) @Setter private boolean keepPrivate;
    /**
     * When the entry was created (V34), the activity feed's "new on the shelf"; null for entries older than the column.
     * Set once here, never by a save (a removed and re-added entry is a new row, so a new addition).
     */
    @Column(name = "created_at", updatable = false) private Instant createdAt;

    /** A personal entry ({@code club} null) or a club entry that {@code addedBy} put on the club's shelf. */
    public Stock(Glaze glaze, AppUser user, Club club, AppUser addedBy, int quantityG) {
        this.id = UUID.randomUUID();
        this.glaze = glaze;
        this.user = user;
        this.club = club;
        this.addedBy = club == null ? null : addedBy;
        this.quantityG = quantityG;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getAddedById() { return addedBy == null ? null : addedBy.getId(); }

    /**
     * The single removal rule of a stock entry: personal stock by its owner (the only one who sees it), club stock by
     * an ADMIN of the club or by the member who added it while they still are one ({@code addedById} is cleared when
     * they leave). Any member may change a club entry's amount; removing it takes it off everyone's shelf.
     */
    public static boolean removableBy(UUID clubId, UUID addedById, UUID userId, Set<UUID> adminClubIds) {
        return clubId == null || adminClubIds.contains(clubId) || userId.equals(addedById);
    }
}
