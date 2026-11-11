package eu.glazelab.api.model;

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
import lombok.ToString;

@Entity @Table(name = Favorite.TABLE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Favorite {
    static final String TABLE = "favorite";
    static final String USER_ID = "user_id";
    static final String GLAZE_ID = "glaze_id";
    static final String COMBINATION_ID = "combination_id";
    @Id @EqualsAndHashCode.Include private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = USER_ID, nullable = false) @ToString.Exclude private AppUser user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = GLAZE_ID) @ToString.Exclude @Getter private Glaze glaze;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = COMBINATION_ID) @ToString.Exclude @Getter private Combination combination;
    public Favorite(AppUser user, Glaze glaze, Combination combination) {
        this.id = UUID.randomUUID(); this.user = user; this.glaze = glaze; this.combination = combination;
    }
}
