package eu.glazelab.api.dto;

import java.util.List;
import java.util.UUID;

/** Public totals only: never the identities of the accounts that starred an item. */
public final class FavoriteDtos {
    private FavoriteDtos() {}

    public record FavoriteCountView(UUID id, long count) {}
    public record FavoriteCountsView(List<FavoriteCountView> glazes, List<FavoriteCountView> combinations) {}
}
