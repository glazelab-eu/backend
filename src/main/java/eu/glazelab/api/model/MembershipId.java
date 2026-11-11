package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** Value-typed composite key: equality over both IDs. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode
@ToString
public class MembershipId implements Serializable {
    @Column(name = Membership.CLUB_ID) private UUID clubId;
    @Column(name = Membership.USER_ID) private UUID userId;

    public MembershipId(UUID clubId, UUID userId) {
        this.clubId = clubId;
        this.userId = userId;
    }
}
