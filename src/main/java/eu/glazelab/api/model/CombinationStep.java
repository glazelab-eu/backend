package eu.glazelab.api.model;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity @Table(name = CombinationStep.TABLE,
        uniqueConstraints = @UniqueConstraint(columnNames = {CombinationStep.COMBINATION_ID, CombinationStep.POSITION}),
        indexes = @Index(name = "step_glaze_idx", columnList = CombinationStep.GLAZE_ID))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CombinationStep {
    static final String TABLE = "combination_step";
    static final String COMBINATION_ID = "combination_id";
    static final String POSITION = "position";
    static final String GLAZE_ID = "glaze_id";
    static final String COATS = "coats";
    static final String STEP_TYPE = "step_type";
    @Id @EqualsAndHashCode.Include private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = COMBINATION_ID, nullable = false) @ToString.Exclude private Combination combination;
    @Column(name = POSITION, nullable = false) @Getter private int position;
    @Enumerated(EnumType.STRING) @Column(name = STEP_TYPE, nullable = false, length = 10)
    @Getter private StepType type;
    /** Null for custom steps. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = GLAZE_ID) @ToString.Exclude @Getter private Glaze glaze;
    /** Null for custom steps. */
    @Column(name = COATS, check = @CheckConstraint(constraint = COATS + " > 0"))
    @Getter private Integer coats;
    @Column(length = 80) @Getter private String method;
    /** Null for glaze steps. */
    @Column(length = 80) @Getter private String label;
    /** A glaze step's notes, or a custom step's details. */
    @Column(length = 500) @Getter private String notes;

    private CombinationStep(Combination combination, int position, StepType type) {
        this.id = UUID.randomUUID();
        this.combination = combination;
        this.position = position;
        this.type = type;
    }
    public static CombinationStep glazeStep(Combination combination, int position, Glaze glaze,
            int coats, String method, String notes) {
        CombinationStep step = new CombinationStep(combination, position, StepType.GLAZE);
        step.glaze = glaze;
        step.coats = coats;
        step.method = method;
        step.notes = notes;
        return step;
    }
    public static CombinationStep customStep(Combination combination, int position, String label, String details) {
        CombinationStep step = new CombinationStep(combination, position, StepType.CUSTOM);
        step.label = label;
        step.notes = details;
        return step;
    }
    /** The combination ID without loading the combination. */
    public UUID getCombinationId() { return combination.getId(); }
}
