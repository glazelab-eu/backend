package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity @Table(name = Club.TABLE)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Club {
    static final String TABLE = "club";
    static final String OWNER_ID = "owner_id";
    static final String HOUSE_RULES = "house_rules";
    static final String INVITES_ENABLED = "invites_enabled";
    static final String INVITE_HASH = "invite_hash";
    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(nullable = false, length = 120) @Setter private String name;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = OWNER_ID) @ToString.Exclude private AppUser owner;

    @Column(name = HOUSE_RULES, nullable = false, length = 10000) @Setter private String houseRules = "";
    @Column(name = INVITES_ENABLED, nullable = false) @Setter private boolean invitesEnabled;
    @Column(name = INVITE_HASH, length = 64) @ToString.Exclude @Setter private String inviteHash;

    public Club(String name, AppUser owner) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.owner = owner;
    }

    /** A creator who leaves is no longer named by the club (the legacy owner field; roles decide everything). */
    public void forgetOwner(UUID userId) {
        if (owner != null && owner.getId().equals(userId)) owner = null;
    }
}
