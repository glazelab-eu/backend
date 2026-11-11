package eu.glazelab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Account bans (backend guide "Bans"); site admins only. No view ever carries a banned address. */
public final class BanDtos {
    private BanDtos() {}

    /**
     * One blocklist entry: its reason and ban time. It names nobody: neither the banned person nor the banning admin
     * is kept with the entry (34-7, data minimisation).
     */
    public record BanView(UUID id, String reason, Instant bannedAt) {}
    public record BanPageView(List<BanView> entries, boolean hasMore) {}
}
