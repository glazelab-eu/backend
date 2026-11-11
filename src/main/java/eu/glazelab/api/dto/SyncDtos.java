package eu.glazelab.api.dto;

import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** The incremental sync answers (service.SyncService). */
public final class SyncDtos {
    private SyncDtos() {}

    /**
     * Why a sync answer carries no delta: the client loads the glaze and combination listings in full (after this
     * answer, whose cursor, epoch and format it keeps for the next sync). {@code CURSOR}: none sent, unreadable or
     * not from this log; {@code FORMAT}: the client's data format differs; {@code EPOCH}: the account, its clubs,
     * club roles or site-admin flag changed; {@code EXPIRED}: the cursor is older than the retention;
     * {@code TOO_MANY}: more changed items than one delta carries.
     */
    public enum Resync { CURSOR, FORMAT, EPOCH, EXPIRED, TOO_MANY }

    /**
     * A sync answer. {@code cursor}, {@code epoch} and {@code format} are what the client sends next time, unchanged
     * (opaque; the server applies the overlap window). Without {@code resync}: {@code glazes} and
     * {@code combinations} are the changed items as the listings show them to this caller (upsert by ID, idempotent:
     * items inside the overlap window come again), {@code removed…Ids} the removed items the caller could see
     * (drop them; an unknown ID is ignored). {@code visibleGlazes}/{@code visibleCombinations} are the sizes of
     * the caller's listings in this answer's snapshot: after applying a delta, a client whose counts differ loads in
     * full; with {@code resync} (lists empty) they are about what the full load brings (the progress of a paged load).
     */
    public record SyncView(int format, String cursor, String epoch, @Schema(nullable = true) Resync resync,
            List<GlazeView> glazes, List<CombinationView> combinations,
            List<UUID> removedGlazeIds, List<UUID> removedCombinationIds,
            long visibleGlazes, long visibleCombinations) {}

    /**
     * One page of the caller's combination listing by ID, for a resumable first sync: take a sync cursor first,
     * then page with {@code after} = the previous {@code next} until {@code next} is null.
     */
    public record CombinationPageView(List<CombinationView> combinations, @Schema(nullable = true) UUID next) {}
}
