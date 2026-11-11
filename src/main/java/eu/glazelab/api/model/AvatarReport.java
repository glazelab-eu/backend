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
 * One account's report of another account's profile picture (V31; backend guide "Profile pictures"). It outlives the
 * picture (no foreign key on {@code avatar_id}) and stays after a site admin closed it; the picture's account, the
 * reporter and the closing admin are nulled when their account is deleted (ON DELETE SET NULL). At most one report per
 * picture and reporter. The reason shares the photo reports' limit. Status changes are bulk updates per picture
 * ({@code AvatarReportRepository.close}).
 */
@Entity @Table(name = "avatar_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AvatarReport {
    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(name = "avatar_id", nullable = false) private UUID avatarId;
    @Column(name = "owner_id") private UUID ownerId;
    @Column(name = "reporter_id") private UUID reporterId;
    @Column(length = ImageReport.MAX_REASON) private String reason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private AvatarReportStatus status;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "closed_by") private UUID closedBy;

    public AvatarReport(UserAvatar picture, UUID reporterId, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.avatarId = picture.getId();
        this.ownerId = picture.getUserId();
        this.reporterId = reporterId;
        this.reason = reason;
        this.createdAt = now;
        this.status = AvatarReportStatus.OPEN;
    }
}
