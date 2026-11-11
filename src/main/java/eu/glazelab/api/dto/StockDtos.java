package eu.glazelab.api.dto;

import eu.glazelab.api.model.Stock;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

/** Stock responses; the rules are in service.StockService. */
public final class StockDtos {
    private StockDtos() {}

    /**
     * One entry as {@code viewerId} sees it: club entries belong to the club ({@code userId} null); {@code addedById}
     * is the member who added a club entry while they are one (null for personal stock, after they left or deleted
     * their account), and {@code canRemove} applies {@code Stock.removableBy} for the viewer. {@code keepPrivate}: the
     * owner keeps a personal entry from their buddies (always false for club stock).
     */
    public record StockView(UUID id, UUID glazeId, @Schema(nullable = true) UUID userId, @Schema(nullable = true) UUID clubId,
                     int quantityG, @Schema(nullable = true) Integer jarSizeG, String manufacturer, String name,
                     @Schema(nullable = true) String clubName, @Schema(nullable = true) UUID addedById, boolean canRemove,
                     boolean keepPrivate) {
        public static StockView of(Stock s, UUID viewerId, Set<UUID> adminClubIds) {
            UUID clubId = s.getClub() == null ? null : s.getClub().getId();
            return new StockView(s.getId(), s.getGlaze().getId(),
                    s.getUser() == null ? null : s.getUser().getId(), clubId, s.getQuantityG(), s.getJarSizeG(),
                    s.getGlaze().getManufacturer().getName(), s.getGlaze().getName(),
                    s.getClub() == null ? null : s.getClub().getName(), s.getAddedById(),
                    Stock.removableBy(clubId, s.getAddedById(), viewerId, adminClubIds), s.isKeepPrivate());
        }
    }

    /** {@code PATCH /api/stock/{id}}: whether the owner keeps a personal entry from their buddies. */
    public record StockPrivacy(@NotNull Boolean keepPrivate) {}
}
