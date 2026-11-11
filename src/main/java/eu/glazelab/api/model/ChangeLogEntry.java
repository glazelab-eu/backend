package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One row of the sync change log (V28): a glaze or combination whose view changed for an audience. Read here;
 * written only by {@code service.ChangeRecorder} (a batch insert as the last statement of the changing
 * transaction), so the entity has no constructor for new rows.
 */
@Entity @Table(name = "change_log")
@Getter @ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ChangeLogEntry {
    /** What changed: a glaze view or a combination view (with the photo metadata inside them). */
    public enum Kind { GLAZE, COMBINATION }
    /** Who receives the row: everyone, the members of {@code audienceId}'s club, or the account {@code audienceId}. */
    public enum Audience { PUBLIC, CLUB, USER }

    @Id @EqualsAndHashCode.Include private Long revision;
    @Column(name = "changed_at", nullable = false) private Instant changedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private Kind kind;
    @Column(name = "item_id", nullable = false) private UUID itemId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private Audience audience;
    @Column(name = "audience_id") private UUID audienceId;
}
