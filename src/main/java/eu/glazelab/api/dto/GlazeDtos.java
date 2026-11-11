package eu.glazelab.api.dto;

import eu.glazelab.api.model.Text.Plain;
import static eu.glazelab.api.model.Text.Kind.MULTI_LINE;

import io.swagger.v3.oas.annotations.media.Schema;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.dto.StockDtos.StockView;
import eu.glazelab.api.model.Glaze;
import eu.glazelab.api.model.GlazeEdit;
import eu.glazelab.api.model.Manufacturer;
import eu.glazelab.api.model.Text;
import eu.glazelab.api.service.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Glaze workflow inputs, glaze responses and the detailed removal conflict shared by HTTP and the service. */
public final class GlazeDtos {
    private GlazeDtos() {}

    /** A manufacturer added together with its first glaze; the short ID is stored upper case. */
    public record NewManufacturer(@NotBlank @Plain @Size(max=100) String name,
                    @NotBlank @Pattern(regexp="\\s*[A-Za-z][A-Za-z0-9]{1,9}\\s*",
                            message="Use a manufacturer ID of 2 to 10 letters or digits, starting with a letter, such as AMACO")
                    String shortId) {}
    /**
     * "I have this glaze": stock saved with the glaze in the same transaction, under the rules of
     * {@code POST /api/stock}. {@code clubId} null means personal stock; a club needs membership (403).
     */
    public record NewGlazeStock(@NotNull @Min(0) @Max(value = StockService.MAX_QUANTITY_G, message = StockService.AMOUNT_TOO_LARGE) Integer quantityG,
                    @Schema(nullable = true) @Min(1) @Max(100_000) Integer jarSizeG,
                    @Schema(nullable = true) UUID clubId) {}
    /** Exactly one of manufacturerId (an existing manufacturer) and newManufacturer (omitted otherwise) is set. */
    public record NewGlaze(@Schema(nullable = true) UUID manufacturerId,
                    @Valid NewManufacturer newManufacturer,
                    @NotBlank @Plain @Size(max=Glaze.MAX_NAME) String name,
                    @NotBlank @Size(max=Glaze.MAX_CODE) @Pattern(regexp="\\s*[A-Za-z0-9]+([.-][A-Za-z0-9]+)*\\s*",
                            message="Use letters and digits for the glaze code, such as PC-20") String code,
                    @Plain(MULTI_LINE) @Size(max=Glaze.MAX_NOTES) String notes,
                    @Schema(nullable = true) @Size(max=20) List<@NotNull @Plain @Size(max=100) String> tags,
                    @Schema(nullable = true, description = "EAN-13, UPC-A or EAN-8; spaces and hyphens are ignored")
                    @Size(max=32) String barcode,
                    @Valid NewGlazeStock stock) {
        /** Normalize before Bean Validation checks the canonical format and length. */
        public NewGlaze {
            code = code == null ? null : Glaze.code(code);
        }
    }
    public record ManufacturerView(UUID id, String shortId, String name) {
        private static ManufacturerView of(Manufacturer manufacturer) {
            return new ManufacturerView(manufacturer.getId(), manufacturer.getShortId(), manufacturer.getName());
        }
    }
    /**
     * A catalog glaze with the pictures of the glaze itself that the caller may see, newest first.
     * The code is the manufacturer's own (PC-20); the frontend shows it with the manufacturer's name
     * (Amaco PC-20). Tags are normalized and sorted; barcodes are stored EAN-13 or EAN-8 digits, sorted.
     * {@code addedBy} is the display name of the account that added the glaze, null once it was deleted.
     * {@code lastEditedAt} is the time of the newest correction (null while there is none) and
     * {@code lastEditedBy} its editor's display name, null without a correction or once that account was deleted.
     */
    public record GlazeView(UUID id, ManufacturerView manufacturer, String name, String code,
                     @Schema(nullable = true) String notes, List<ImageView> images, List<String> tags,
                     List<String> barcodes, Instant createdAt, @Schema(nullable = true) String addedBy,
                     @Schema(nullable = true) Instant lastEditedAt, @Schema(nullable = true) String lastEditedBy) {
        public static GlazeView of(Glaze g, List<ImageView> images, LastEdit lastEdit) {
            return new GlazeView(g.getId(), ManufacturerView.of(g.getManufacturer()), g.getName(), g.getCode(),
                    g.getNotes(), images, TagDtos.sorted(g.getTags()), TagDtos.sorted(g.getBarcodes()),
                    g.getCreatedAt(), g.getCreatedBy() == null ? null : g.getCreatedBy().getDisplayName(),
                    lastEdit == null ? null : lastEdit.at(), lastEdit == null ? null : lastEdit.by());
        }
    }
    /** The newest correction of a glaze: when, and the editor's display name (null once the account was deleted). */
    public record LastEdit(Instant at, String by) {}
    /**
     * A correction of a glaze: only the fields present change. {@code name} and {@code code} absent or null keep
     * the stored value; {@code notes} absent keeps it and null or blank clears it (Jackson reads a missing
     * {@code Optional} as null and an explicit null as empty). The manufacturer never changes; other properties
     * are ignored. The code changes only for site admins or while no combination step uses the glaze.
     */
    public record GlazeCorrection(
            @Schema(nullable = true) @Plain @Size(min = 1, max = Glaze.MAX_NAME, message = NAME_LENGTH) String name,
            @Schema(nullable = true) @Size(max = Glaze.MAX_CODE) @Pattern(regexp = "[A-Za-z0-9]+([.-][A-Za-z0-9]+)*",
                    message = "Use letters and digits for the glaze code, such as PC-20") String code,
            @Schema(nullable = true, maxLength = Glaze.MAX_NOTES) Optional<@Plain(MULTI_LINE) String> notes) {
        public static final String NAME_LENGTH = "Enter a glaze name of at most 120 characters";
        /** Strip the name and normalize the code before Bean Validation checks them. */
        public GlazeCorrection {
            name = name == null ? null : Text.strip(name);
            code = code == null ? null : Glaze.code(code);
        }
    }
    /** One changed field of a correction; values are as stored (notes may be null), the editor null once deleted. */
    public record GlazeEditView(Instant editedAt, GlazeEdit.Field field, @Schema(nullable = true) String editor,
                     @Schema(nullable = true) String oldValue, @Schema(nullable = true) String newValue) {}
    /** A glaze's change log, newest first: at most {@code CatalogController.HISTORY_LIMIT} entries, {@code more} when older ones exist. */
    public record GlazeHistoryView(List<GlazeEditView> edits, boolean more) {}
    /** A glaze's barcodes after an edit, sorted. */
    public record BarcodesView(List<String> barcodes) {}
    /**
     * A glaze as it was just created (no pictures yet) and the stock entries saved with it: one when the
     * request had a stock part, otherwise none. {@code id} repeats {@code glaze.id} for clients from before
     * this answer was richer.
     */
    public record GlazeCreatedView(UUID id, GlazeView glaze, List<StockView> stock) {}
    public record UsingCombinationView(UUID id, String title) {}
    /** The 409 answer of a glaze removal: the combinations whose steps still use the glaze. */
    public record GlazeInUseView(String error, List<UsingCombinationView> combinations) {}
}
