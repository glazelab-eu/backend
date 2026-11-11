package eu.glazelab.api.dto;

import eu.glazelab.api.model.Text.Plain;
import eu.glazelab.api.service.CombinationService;
import static eu.glazelab.api.model.Text.Kind.MULTI_LINE;

import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.model.FiringAtmosphere;
import eu.glazelab.api.model.StepType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Combination requests, checked and saved by service.CombinationService, and the combination responses. */
public final class CombinationDtos {
    private CombinationDtos() {}

    /**
     * A glaze application ({@code GLAZE}, the default: glaze and coats required, no label) or a custom
     * step such as a firing ({@code CUSTOM}: label required, details in {@code notes}, no glaze, coats or
     * method).
     */
    public record StepInput(@Schema(nullable = true) StepType type, @Schema(nullable = true) UUID glazeId,
                     @Schema(nullable = true) @Min(1) Integer coats,
                     @Schema(nullable = true) @Plain @Size(max=80) String label,
                     @Schema(nullable = true) @Plain @Size(max=80) String method,
                     @Schema(nullable = true) @Plain(MULTI_LINE) @Size(max=500) String notes) {}

    /**
     * {@code draft} (create only; default false) saves a draft that only its author sees: it may lack a
     * title and steps, but every step it has is checked by type. A published combination needs both.
     * {@code basedOnId} (create only; edits ignore it) names the combination this is a variation of; a
     * source the author cannot see or that no longer exists is left out, as if none was given.
     * The final-firing fields ({@code cone} … {@code firedOn}, see model.FinalFiring) are optional; text is
     * trimmed and blank counts as none. A field sent as null clears it; an edit keeps a field the request
     * leaves out (Jackson reads a missing {@code Optional} as null and an explicit null as empty), so clients
     * from before the final firing never clear it. On PUT, {@code baseTags} (the tags the editor started from) turns
     * {@code tags} into a difference: tags added or removed in the form are applied to the stored set, tags other
     * accounts added or removed meanwhile stay as they are; without it the PUT replaces the tag set (older clients).
     */
    public record NewCombination(@NotNull @Plain @Size(max=160) String title,
                          @Plain(MULTI_LINE) @Size(max=2000) String description,
                          @NotNull @Size(max=CombinationService.MAX_STEPS, message=CombinationService.TOO_MANY_STEPS)
                          List<@NotNull @Valid StepInput> steps,
                          @Schema(nullable = true) @Size(max=20) List<@NotNull @Plain @Size(max=100) String> tags,
                          @Schema(nullable = true, description = "PUT only: the tags the edit started from; when sent, only the difference to tags is applied, so tags others changed meanwhile stay")
                          @Size(max=20) List<@NotNull @Plain @Size(max=100) String> baseTags,
                          @Schema(nullable = true) Boolean draft,
                          @Schema(nullable = true) UUID basedOnId,
                          @Schema(nullable = true, description = "Orton cone: an optional leading 0, then 1 or 2 digits (6, 10, 04, 022)") Optional<@Plain String> cone,
                          @Schema(nullable = true, minimum = "100", maximum = "1600") Optional<Integer> temperatureC,
                          @Schema(nullable = true) Optional<FiringAtmosphere> atmosphere,
                          @Schema(nullable = true, maxLength = 120) Optional<@Plain String> clayBody,
                          @Schema(nullable = true, maxLength = 80) Optional<@Plain String> kiln,
                          @Schema(nullable = true) Optional<LocalDate> firedOn) {}

    /**
     * A title-only correction ({@code PATCH /api/combinations/{id}/title}): stripped like the PUT's title; a published
     * combination needs one that is not blank, a draft may keep a blank one.
     */
    public record TitleEdit(@NotNull @Plain @Size(max=160) String title) {}

    /** The combination's title as stored after a correction. */
    public record TitleView(String title) {}

    /** Glaze fields are null for custom steps, {@code label} for glaze steps. */
    public record StepView(int position, StepType type, @Schema(nullable = true) UUID glazeId,
                    @Schema(nullable = true) Integer coats, @Schema(nullable = true) String label,
                    @Schema(nullable = true) String method, @Schema(nullable = true) String notes,
                    @Schema(nullable = true) String manufacturer, @Schema(nullable = true) String name) {}
    /**
     * {@code author} and {@code authorId} are null once the author's account was deleted; {@code own} is
     * true only in the author's session. {@code draft} combinations are listed only for their author.
     * {@code publishedAt} (an instant like {@code createdAt}) is null exactly for drafts: a combination
     * counts as new, and is listed, from its publication, not from when the draft was started.
     * {@code basedOnId} is the source of a variation, null unless the viewer may see that source (never a
     * draft that is not theirs) or once it was deleted. The final-firing fields are null when not recorded.
     */
    public record CombinationView(UUID id, String title, @Schema(nullable = true) String description, Instant createdAt,
                           @Schema(nullable = true) Instant publishedAt,
                           @Schema(nullable = true) UUID basedOnId,
                           @Schema(nullable = true) String cone, @Schema(nullable = true) Integer temperatureC,
                           @Schema(nullable = true) FiringAtmosphere atmosphere, @Schema(nullable = true) String clayBody,
                           @Schema(nullable = true) String kiln, @Schema(nullable = true) LocalDate firedOn,
                           @Schema(nullable = true) String author, @Schema(nullable = true) UUID authorId, boolean own, boolean draft, boolean stepsLocked,
                           List<StepView> steps, List<ImageView> images, List<String> tags) {}
}
