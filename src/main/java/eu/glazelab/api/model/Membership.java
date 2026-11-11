package eu.glazelab.api.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity @Table(name = Membership.TABLE)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Membership {
    static final String TABLE = "membership";
    static final String CLUB_ID = "club_id";
    static final String USER_ID = "user_id";
    public enum Role { MEMBER, ADMIN }
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) @Setter private Role role = Role.MEMBER;
    @EmbeddedId @EqualsAndHashCode.Include private MembershipId id;
    @MapsId("clubId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = CLUB_ID, nullable = false) @ToString.Exclude private Club club;
    @MapsId("userId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = USER_ID, nullable = false) @ToString.Exclude private AppUser user;
    /** When the membership began (V31): a ban that removes a club's last admin promotes the longest-standing member. */
    @Column(name = "joined_at", nullable = false, updatable = false) private Instant joinedAt;

    public Membership(Club club, AppUser user) {
        this.id = new MembershipId(club.getId(), user.getId());
        this.club = club;
        this.user = user;
        this.joinedAt = Instant.now();
    }
}
