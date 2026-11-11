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
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One reported event of an opted-in account (V32; backend guide "Error reports"), scrubbed by {@link ReportScrubber}
 * before it is built, with the device facts of the batch it came in. Linked to its account by ID only; it goes with
 * the account and after the retention.
 */
@Entity @Table(name = "client_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ClientReport {
    /** What the app caught: an error, a warning or a promise rejection nobody handled. */
    public enum Kind { ERROR, WARNING, UNHANDLED_REJECTION }
    /** The primary pointer: touch (coarse), mouse or pen (fine), or none. */
    public enum Pointer { COARSE, FINE, NONE }
    /** Whether a service worker controls the page (UNSUPPORTED: the browser has no service workers). */
    public enum ServiceWorker { UNSUPPORTED, NONE, ACTIVE }
    public enum ColorScheme { LIGHT, DARK }

    @Id @EqualsAndHashCode.Include @ToString.Include private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) @ToString.Include private Kind kind;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(nullable = false, length = ReportScrubber.MESSAGE_LENGTH) private String message;
    @Column(length = ReportScrubber.STACK_LENGTH) private String stack;
    @Column(nullable = false) private int occurrences;
    @Column(name = "build_label", nullable = false, length = 64) private String buildLabel;
    @Column(name = "route_name", length = 40) private String routeName;
    @Column(length = 40) private String browser;
    @Column(name = "browser_version", length = 30) private String browserVersion;
    @Column(length = 40) private String os;
    @Column(name = "os_version", length = 30) private String osVersion;
    @Column(name = "screen_width") private Integer screenWidth;
    @Column(name = "screen_height") private Integer screenHeight;
    @Column(name = "viewport_width") private Integer viewportWidth;
    @Column(name = "viewport_height") private Integer viewportHeight;
    @Column(name = "pixel_ratio") private Double pixelRatio;
    @Enumerated(EnumType.STRING) @Column(length = 10) private Pointer pointer;
    @Column(length = 35) private String language;
    private Boolean standalone;
    private Boolean online;
    @Column(name = "connection_type", length = 10) private String connectionType;
    @Enumerated(EnumType.STRING) @Column(name = "service_worker", length = 12) private ServiceWorker serviceWorker;
    @Column(name = "update_pending") private Boolean updatePending;
    @Column(name = "reduced_motion") private Boolean reducedMotion;
    @Enumerated(EnumType.STRING) @Column(name = "color_scheme", length = 10) private ColorScheme colorScheme;
    @Column(name = "timezone_offset_minutes") private Integer timezoneOffsetMinutes;
    @Column(name = "device_memory_gb") private Double deviceMemoryGb;

    @Builder
    @SuppressWarnings("java:S107")
    private ClientReport(UUID userId, Instant receivedAt, Instant occurredAt, Kind kind, String fingerprint, String message,
            String stack, int occurrences, String buildLabel, String routeName, String browser, String browserVersion,
            String os, String osVersion, Integer screenWidth, Integer screenHeight, Integer viewportWidth,
            Integer viewportHeight, Double pixelRatio, Pointer pointer, String language, Boolean standalone,
            Boolean online, String connectionType, ServiceWorker serviceWorker, Boolean updatePending,
            Boolean reducedMotion, ColorScheme colorScheme, Integer timezoneOffsetMinutes, Double deviceMemoryGb) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.receivedAt = receivedAt;
        this.occurredAt = occurredAt;
        this.kind = kind;
        this.fingerprint = fingerprint;
        this.message = message;
        this.stack = stack;
        this.occurrences = occurrences;
        this.buildLabel = buildLabel;
        this.routeName = routeName;
        this.browser = browser;
        this.browserVersion = browserVersion;
        this.os = os;
        this.osVersion = osVersion;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.pixelRatio = pixelRatio;
        this.pointer = pointer;
        this.language = language;
        this.standalone = standalone;
        this.online = online;
        this.connectionType = connectionType;
        this.serviceWorker = serviceWorker;
        this.updatePending = updatePending;
        this.reducedMotion = reducedMotion;
        this.colorScheme = colorScheme;
        this.timezoneOffsetMinutes = timezoneOffsetMinutes;
        this.deviceMemoryGb = deviceMemoryGb;
    }
}
