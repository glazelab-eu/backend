package eu.glazelab.api.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity @Table(name = Combination.TABLE)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Combination {
    static final String TABLE = "combination";
    static final String TAG_TABLE = "combination_tag";
    static final String TAG_OWNER = "combination_id";
    static final String CREATED_BY = "created_by";
    static final String CREATED_AT = "created_at";
    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(nullable = false, length = 160) private String title;
    @Column(length = 2000) private String description;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = CREATED_BY) @ToString.Exclude private AppUser createdBy;
    @Column(name = CREATED_AT, nullable = false) private Instant createdAt;
    /** Null while a draft: only the author sees, opens or edits it, and its photos are PRIVATE until publication (ImageRecord.effective). */
    @Column(name = "published_at") private Instant publishedAt;
    /**
     * The combination this one is a variation of, set when it is created; null for originals and once the
     * source is deleted (the database clears it). Listings tell only viewers who may see the source.
     */
    @Column(name = "based_on_id") private UUID basedOnId;
    // The final firing (see FinalFiring), read and replaced as one value.
    @Column(length = 3) @Getter(AccessLevel.NONE) private String cone;
    @Column(name = "temperature_c") @Getter(AccessLevel.NONE) private Integer temperatureC;
    @Enumerated(EnumType.STRING) @Column(length = 10) @Getter(AccessLevel.NONE) private FiringAtmosphere atmosphere;
    @Column(name = "clay_body", length = FinalFiring.CLAY_BODY_LENGTH) @Getter(AccessLevel.NONE) private String clayBody;
    @Column(length = FinalFiring.KILN_LENGTH) @Getter(AccessLevel.NONE) private String kiln;
    @Column(name = "fired_on") @Getter(AccessLevel.NONE) private LocalDate firedOn;

    /** Normalized tags (see {@link Tags}); any signed-in user may add or remove them. */
    @ElementCollection
    @CollectionTable(name = TAG_TABLE, joinColumns = @JoinColumn(name = TAG_OWNER))
    @Column(name = "tag", nullable = false, length = Tags.MAX_LENGTH)
    @ToString.Exclude @Getter(AccessLevel.NONE) private Set<String> tags = new HashSet<>();

    public Combination(String title, String description, AppUser createdBy, boolean draft, UUID basedOnId) {
        this.id = UUID.randomUUID();
        this.basedOnId = basedOnId;
        this.title = title;
        this.description = description;
        this.createdBy = createdBy;
        if (!draft) {
            // One moment for both, so a combination created published is listed at its creation time.
            Instant now = now();
            createdAt = now;
            publishedAt = now;
        }
    }
    @PrePersist void setCreatedAt() { if (createdAt == null) createdAt = now(); }
    /** Microseconds, as the database stores them, so a saved and a reloaded time compare equal. */
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    public void edit(String title, String description) {
        this.title = title;
        this.description = description;
    }
    /** A title-only correction (already checked and stripped). */
    public void retitle(String title) { this.title = title; }

    public FinalFiring getFinalFiring() {
        return new FinalFiring(cone, temperatureC, atmosphere, clayBody, kiln, firedOn);
    }
    /** Replaces every final-firing field (already checked by {@link FinalFiring#of}); null fields clear. */
    public void setFinalFiring(FinalFiring firing) {
        cone = firing.cone();
        temperatureC = firing.temperatureC();
        atmosphere = firing.atmosphere();
        clayBody = firing.clayBody();
        kiln = firing.kiln();
        firedOn = firing.firedOn();
    }

    public boolean isDraft() { return publishedAt == null; }
    /** A draft becomes visible to everyone; publishing again changes nothing. */
    public void publish() { if (publishedAt == null) publishedAt = now(); }
    /** Whether the account may see, open or favourite this combination: drafts only their author. */
    public boolean visibleTo(UUID userId) {
        return !isDraft() || (createdBy != null && createdBy.getId().equals(userId));
    }

    public Set<String> getTags() { return Collections.unmodifiableSet(tags); }
    /** Replaces the tags, already checked with {@link Tags#edited}; unchanged rows stay. */
    public void setTags(Set<String> next) {
        tags.retainAll(next);
        tags.addAll(next);
    }
}
