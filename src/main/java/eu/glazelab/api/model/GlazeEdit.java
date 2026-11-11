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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One changed field of a glaze correction, in the public change log (V26). The database removes it with its
 * glaze (ON DELETE CASCADE) and nulls a deleted editor (ON DELETE SET NULL); only notes may be null.
 */
@Entity @Table(name = "glaze_edit")
@NoArgsConstructor(access = AccessLevel.PROTECTED) @Getter
@ToString @EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class GlazeEdit {
    /** The fields a correction may change; the manufacturer never changes. */
    public enum Field { NAME, NOTES, CODE }

    @Id @EqualsAndHashCode.Include private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "glaze_id", nullable = false) @ToString.Exclude private Glaze glaze;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "editor_id") @ToString.Exclude private AppUser editor;
    @Column(name = "edited_at", nullable = false) private Instant editedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Field field;
    @Column(name = "old_value", length = 1000) private String oldValue;
    @Column(name = "new_value", length = 1000) private String newValue;

    public GlazeEdit(Glaze glaze, AppUser editor, Instant editedAt, Field field, String oldValue, String newValue) {
        this.id = UUID.randomUUID();
        this.glaze = glaze;
        this.editor = editor;
        this.editedAt = editedAt;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }
}
