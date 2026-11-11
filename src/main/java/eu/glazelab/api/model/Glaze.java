package eu.glazelab.api.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** A catalog glaze, identified by its manufacturer and that manufacturer's code (unique together). */
@Entity @Table(name = Glaze.TABLE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Glaze {
    /** Input limits shared by creation and corrections; the code is stored with room to spare. */
    public static final int MAX_NAME = 120;
    public static final int MAX_CODE = 40;
    public static final int MAX_NOTES = 1000;
    static final String TABLE = "glaze";
    static final String TAG_TABLE = "glaze_tag";
    static final String TAG_OWNER = "glaze_id";
    static final String BARCODE_TABLE = "glaze_barcode";
    static final String MANUFACTURER_ID = "manufacturer_id";
    static final String CREATED_BY = "created_by";
    @Id @EqualsAndHashCode.Include @Getter private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = MANUFACTURER_ID, nullable = false) @ToString.Exclude @Getter private Manufacturer manufacturer;
    @Column(nullable = false, length = MAX_NAME) @Getter private String name;
    /** The manufacturer's own code, such as PC-20; shown as Amaco PC-20. */
    @Column(nullable = false, length = 60) @Getter private String code;
    @Column(length = MAX_NOTES) @Getter private String notes;
    /** Who added the glaze; null once that account was deleted (shared content stays anonymous). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = CREATED_BY) @ToString.Exclude @Getter private AppUser createdBy;
    /** When the glaze was added; glazes from before V17 carry the migration's time. */
    @Column(name = "created_at", nullable = false) @Getter private Instant createdAt;

    /** Normalized tags (see {@link Tags}); any signed-in user may add or remove them. */
    @ElementCollection
    @CollectionTable(name = TAG_TABLE, joinColumns = @JoinColumn(name = TAG_OWNER))
    @Column(name = "tag", nullable = false, length = Tags.MAX_LENGTH)
    @ToString.Exclude @Getter(AccessLevel.NONE) private Set<String> tags = new HashSet<>();

    /** Normalized retail barcodes (see {@link Barcodes}), each unique in the library. */
    @ElementCollection
    @CollectionTable(name = BARCODE_TABLE, joinColumns = @JoinColumn(name = TAG_OWNER))
    @Column(name = "barcode", nullable = false, length = 13)
    @ToString.Exclude @Getter(AccessLevel.NONE) private Set<String> barcodes = new HashSet<>();

    public Glaze(Manufacturer manufacturer, String name, String code, String notes, AppUser createdBy) {
        this.id = UUID.randomUUID();
        this.manufacturer = manufacturer;
        this.name = name;
        this.code = code(code);
        this.notes = notes;
        this.createdBy = createdBy;
        // Microseconds, the database precision, so the creating answer equals later reads.
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Printed codes: trim whitespace, upper case, and collapse spaces, slashes and hyphens. */
    public static String code(String code) {
        String spaced = code.replaceAll("(?U)[\\s\\uFEFF]", " ");
        int start = 0;
        int end = spaced.length();
        while (start < end && spaced.charAt(start) == ' ') start++;
        while (end > start && spaced.charAt(end - 1) == ' ') end--;
        return spaced.substring(start, end).toUpperCase(Locale.ROOT).replaceAll("[ /-]+", "-");
    }

    /**
     * A correction (see {@code GlazeService.correct}): name trimmed, notes as checked, and the code either the
     * stored one or a new one already normalized. Never re-normalize the stored code here: one from before
     * the current normalizer (V10 only trimmed and upper-cased) would change silently, unlogged and unlocked.
     */
    public void correct(String name, String code, String notes) {
        this.name = name;
        this.code = code;
        this.notes = notes;
    }

    public Set<String> getBarcodes() { return Collections.unmodifiableSet(barcodes); }
    /** Replaces the barcodes, already checked with {@link Barcodes#edited}; unchanged rows stay. */
    public void setBarcodes(Set<String> next) {
        barcodes.retainAll(next);
        barcodes.addAll(next);
    }

    public Set<String> getTags() { return Collections.unmodifiableSet(tags); }
    /** Replaces the tags, already checked with {@link Tags#edited}; unchanged rows stay. */
    public void setTags(Set<String> next) {
        tags.retainAll(next);
        tags.addAll(next);
    }
}
