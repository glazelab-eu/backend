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
 * One feedback of a signed-in account (V35; backend guide "Feedback"): its text, the context the app attached unless
 * the sender left it out (route name, build label, language, device class) and up to three {@link FeedbackPhoto}s. Only
 * site admins read it; it goes with the account and {@code glazelab.feedback.retention} after it was marked done.
 */
@Entity @Table(name = "feedback")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Feedback {
    public static final int MAX_MESSAGE = 2000;

    /** A site admin's mark: open until handled, then done (and deleted after the retention). */
    public enum Status { OPEN, DONE }
    /** The sender's device as the app saw it: a phone-wide window, another touch device, or a fine pointer. */
    public enum Device { PHONE, TABLET, DESKTOP }

    @Id @EqualsAndHashCode.Include @ToString.Include private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = MAX_MESSAGE) private String message;
    @Column(name = "route_name", length = 40) private String routeName;
    @Column(name = "build_label", length = 64) private String buildLabel;
    @Column(length = 35) private String language;
    @Enumerated(EnumType.STRING) @Column(length = 10) private Device device;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) @ToString.Include private Status status;
    @Column(name = "done_at") private Instant doneAt;

    public Feedback(UUID userId, String message, String routeName, String buildLabel, String language, Device device, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.message = message;
        this.routeName = routeName;
        this.buildLabel = buildLabel;
        this.language = language;
        this.device = device;
        this.createdAt = now;
        this.status = Status.OPEN;
    }
}
