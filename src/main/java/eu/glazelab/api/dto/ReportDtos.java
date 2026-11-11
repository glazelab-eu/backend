package eu.glazelab.api.dto;

import eu.glazelab.api.model.ClientReport;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Opt-in error reports (backend guide "Error reports"). Only plain, short device facts are accepted: no free text
 * besides an event's message and stack, which the server scrubs ({@code model.ReportScrubber}). No URL, query string,
 * user agent string, email address or IP address is ever part of a report.
 */
public final class ReportDtos {
    private ReportDtos() {}

    /** Names as browsers and operating systems call themselves (letters, digits, spaces, dots, hyphens, brackets). */
    static final String NAME = "[\\p{L}\\p{N} ._()/-]*";
    static final String VERSION = "[A-Za-z0-9._-]*";

    /** Turns the account's error reports on or off; off also deletes the reports already stored. */
    public record DiagnosticsInput(@NotNull Boolean enabled) {}

    /** The device facts of a batch, captured when it is sent; every field is optional (browsers differ). */
    public record DeviceInfo(
            @Schema(nullable = true) @Size(max = 40) @Pattern(regexp = NAME) String browser,
            @Schema(nullable = true) @Size(max = 30) @Pattern(regexp = VERSION) String browserVersion,
            @Schema(nullable = true) @Size(max = 40) @Pattern(regexp = NAME) String os,
            @Schema(nullable = true) @Size(max = 30) @Pattern(regexp = VERSION) String osVersion,
            @Schema(nullable = true) @Min(0) @Max(100_000) Integer screenWidth,
            @Schema(nullable = true) @Min(0) @Max(100_000) Integer screenHeight,
            @Schema(nullable = true) @Min(0) @Max(100_000) Integer viewportWidth,
            @Schema(nullable = true) @Min(0) @Max(100_000) Integer viewportHeight,
            @Schema(nullable = true) @DecimalMin("0") @DecimalMax("16") Double pixelRatio,
            @Schema(nullable = true) ClientReport.Pointer pointer,
            @Schema(nullable = true, description = "A BCP 47 language tag such as en-GB")
            @Size(max = 35) @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*") String language,
            @Schema(nullable = true, description = "Installed (standalone display mode) rather than in a browser tab") Boolean standalone,
            @Schema(nullable = true) Boolean online,
            @Schema(nullable = true, description = "navigator.connection.effectiveType where the browser has it")
            @Pattern(regexp = "slow-2g|2g|3g|4g") String connection,
            @Schema(nullable = true) ClientReport.ServiceWorker serviceWorker,
            @Schema(nullable = true, description = "A new app version waits to be activated") Boolean updatePending,
            @Schema(nullable = true) Boolean reducedMotion,
            @Schema(nullable = true) ClientReport.ColorScheme colorScheme,
            @Schema(nullable = true, description = "Date.getTimezoneOffset(): minutes behind UTC")
            @Min(-1080) @Max(1080) Integer timezoneOffsetMinutes,
            @Schema(nullable = true, description = "navigator.deviceMemory where the browser has it")
            @DecimalMin("0") @DecimalMax("1024") Double deviceMemoryGb) {}

    /**
     * One event: at most 4 KB as JSON. {@code route} is the route's NAME (such as {@code glaze}), never a URL;
     * {@code count} says how often the app saw the same event before sending it (default 1).
     */
    public record ReportEvent(
            @NotNull ClientReport.Kind kind,
            @NotBlank @Size(max = 1000) String message,
            @Schema(nullable = true) @Size(max = 3000) String stack,
            @Schema(nullable = true) @Min(1) @Max(1000) Integer count,
            @NotNull Instant occurredAt,
            @Schema(description = "The app's build label as its footer shows it, such as 0.1.0 (abc1234)")
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._+() -]+") String build,
            @Schema(nullable = true) @Size(max = 40) @Pattern(regexp = "[a-z][a-z0-9-]*") String route) {}

    /** Up to 20 events from one device, at most 32 KB in all. */
    public record ReportBatch(@NotNull @Valid DeviceInfo device,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid ReportEvent> events) {}

    // --- Reading the reports (site admins only; backend guide "Error reports"). No view carries an account ID or name.

    /** How many reports of a group came from one build. */
    public record ReportBuildCount(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String build,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long reports) {}
    /** How many reports of a group came from one browser and OS (either may be unknown). */
    public record ReportDeviceCount(@Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String browser,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String os,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long reports) {}

    /**
     * One message: every stored report with the same fingerprint (kind plus the message with digit runs normalised).
     * {@code reports} rows, {@code occurrences} their summed repeat counts; seen times are receive times; the viewport
     * widths are the narrowest and widest the reports came from (null when none said).
     */
    public record ReportGroupView(String fingerprint, ClientReport.Kind kind, String message, long reports,
            long occurrences, Instant firstSeen, Instant lastSeen, List<ReportBuildCount> builds,
            List<ReportDeviceCount> devices, @Schema(nullable = true) Integer minViewportWidth,
            @Schema(nullable = true) Integer maxViewportWidth) {}
    public record ReportGroupPageView(List<ReportGroupView> groups, boolean hasMore) {}

    /** The build labels present in the stored reports, newest first (for the filter). */
    public record ReportBuildsView(List<String> builds) {}

    /** One stored report with its scrubbed stack and the device facts of its batch. */
    public record ReportOccurrenceView(java.util.UUID id, Instant receivedAt, Instant occurredAt, int occurrences,
            String build, @Schema(nullable = true) String route, @Schema(nullable = true) String stack, DeviceInfo device) {}
    public record ReportOccurrencePageView(List<ReportOccurrenceView> occurrences, boolean hasMore) {}
}
