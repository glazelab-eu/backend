package eu.glazelab.api.dto;

import eu.glazelab.api.model.Text.Plain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Collection;
import java.util.List;

/** Tag editing shared by glazes and combinations; the rules live in model.Tags. */
public final class TagDtos {
    private TagDtos() {}

    /**
     * Tags to remove, then to add, as typed: the server normalizes them. Removing an absent tag or
     * adding a present one changes nothing, so concurrent edits by different users do not clash.
     */
    public record TagEdit(@Schema(nullable = true) @Size(max = 20) List<@NotNull @Plain @Size(max = 100) String> add,
            @Schema(nullable = true) @Size(max = 20) List<@NotNull @Plain @Size(max = 100) String> remove) {
        public List<String> adding() { return add == null ? List.of() : add; }
        public List<String> removing() { return remove == null ? List.of() : remove; }
    }

    /** An item's tags after an edit, sorted. */
    public record TagsView(List<String> tags) {
        public static TagsView of(Collection<String> tags) { return new TagsView(sorted(tags)); }
    }

    public static List<String> sorted(Collection<String> tags) {
        return tags.stream().sorted().toList();
    }
}
