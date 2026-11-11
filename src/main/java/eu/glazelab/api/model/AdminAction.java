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
 * One site-admin action (V31 {@code admin_action}, old and new value V39; backend guide "Bans"), written in the action's own transaction. The
 * target IDs carry no foreign key, so the row outlives what it names; a deleted admin leaves {@code actorId} null.
 */
@Entity @Table(name = "admin_action")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AdminAction {
    public enum Type { BAN, UNBAN, AVATAR_REMOVED, CLUB_ADMIN_PROMOTED, TITLE_CORRECTED }

    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "actor_id", updatable = false) private UUID actorId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30, updatable = false) private Type action;
    @Column(name = "target_user_id", updatable = false) private UUID targetUserId;
    @Column(name = "target_id", updatable = false) private UUID targetId;
    @Column(length = 500, updatable = false) private String reason;
    /** A correction's value before and after ({@code TITLE_CORRECTED}, V39); null for every other action. */
    @Column(name = "old_value", length = 160, updatable = false) private String oldValue;
    @Column(name = "new_value", length = 160, updatable = false) private String newValue;

    public AdminAction(UUID actorId, Type action, UUID targetUserId, UUID targetId, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.createdAt = now;
        this.actorId = actorId;
        this.action = action;
        this.targetUserId = targetUserId;
        this.targetId = targetId;
        this.reason = reason;
    }

    /** A site admin corrected the title of another account's combination: its author, the combination, both titles. */
    public static AdminAction titleCorrected(UUID actorId, UUID authorId, UUID combinationId, String oldTitle, String newTitle, Instant now) {
        AdminAction action = new AdminAction(actorId, Type.TITLE_CORRECTED, authorId, combinationId, null, now);
        action.oldValue = oldTitle;
        action.newValue = newTitle;
        return action;
    }
}
