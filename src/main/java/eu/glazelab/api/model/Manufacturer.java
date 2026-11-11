package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A glaze manufacturer: a display name and a short ID that prefixes its glaze codes (AMACO-PC-20).
 * Both are unique regardless of letter case; there is no editing or deletion.
 */
@Entity @Table(name = Manufacturer.TABLE)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Manufacturer {
    static final String TABLE = "manufacturer";
    static final String SHORT_ID = "short_id";
    static final String NAME_KEY = "name_key";
    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(name = SHORT_ID, nullable = false, length = 10) private String shortId;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = NAME_KEY, nullable = false, length = 100) @Getter(AccessLevel.NONE) private String nameKey;

    public Manufacturer(String name, String shortId) {
        this.id = UUID.randomUUID();
        this.name = Text.strip(name);
        this.nameKey = nameKey(name);
        this.shortId = shortId(shortId);
    }

    /** Stored upper case, so the unique constraint ignores letter case. */
    public static String shortId(String shortId) {
        return shortId.trim().toUpperCase(Locale.ROOT);
    }

    /** The lower-case name behind the unique name constraint. */
    public static String nameKey(String name) {
        return Text.strip(name).toLowerCase(Locale.ROOT);
    }
}
