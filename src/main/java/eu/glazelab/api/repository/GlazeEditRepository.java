package eu.glazelab.api.repository;

import eu.glazelab.api.dto.GlazeDtos.GlazeEditView;
import eu.glazelab.api.dto.GlazeDtos.LastEdit;
import eu.glazelab.api.model.GlazeEdit;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GlazeEditRepository extends JpaRepository<GlazeEdit, UUID> {
    interface Entry {
        Instant getEditedAt(); GlazeEdit.Field getField(); String getEditor(); String getOldValue(); String getNewValue();
        default GlazeEditView view() { return new GlazeEditView(getEditedAt(), getField(), getEditor(), getOldValue(), getNewValue()); }
    }
    /** One page of a glaze's change log with the editors' current names (never their IDs); no count query. */
    @Query("select e.editedAt as editedAt, e.field as field, u.displayName as editor, e.oldValue as oldValue,"
            + " e.newValue as newValue from GlazeEdit e left join e.editor u where e.glaze.id = :glazeId")
    List<Entry> history(UUID glazeId, Pageable page);

    interface Latest { UUID getGlazeId(); Instant getEditedAt(); String getEditor(); }
    /**
     * The newest correction of every edited glaze in one statement, so the listing never queries per glaze.
     * One correction writes all its rows with one time and editor, so ties carry the same answer.
     */
    @Query("select e.glaze.id as glazeId, e.editedAt as editedAt, u.displayName as editor from GlazeEdit e"
            + " left join e.editor u where e.editedAt = (select max(o.editedAt) from GlazeEdit o where o.glaze = e.glaze)")
    List<Latest> latest();
    @Query("select e.glaze.id as glazeId, e.editedAt as editedAt, u.displayName as editor from GlazeEdit e"
            + " left join e.editor u where e.glaze.id = :glazeId"
            + " and e.editedAt = (select max(o.editedAt) from GlazeEdit o where o.glaze.id = :glazeId)")
    List<Latest> latestOf(UUID glazeId);
    @Query("select e.glaze.id as glazeId, e.editedAt as editedAt, u.displayName as editor from GlazeEdit e"
            + " left join e.editor u where e.glaze.id in :glazeIds"
            + " and e.editedAt = (select max(o.editedAt) from GlazeEdit o where o.glaze = e.glaze)")
    List<Latest> latestOf(Collection<UUID> glazeIds);
    /** The glazes an account corrected: their {@code lastEditedBy} may name it. */
    @Query("select distinct e.glaze.id from GlazeEdit e where e.editor.id = :editorId")
    List<UUID> glazeIdsEditedBy(UUID editorId);

    /** Glaze ID → its newest correction, from {@link #latest()} or {@link #latestOf}. */
    static Map<UUID, LastEdit> byGlaze(List<Latest> rows) {
        return rows.stream().collect(Collectors.toMap(Latest::getGlazeId,
                row -> new LastEdit(row.getEditedAt(), row.getEditor()), (first, second) -> first));
    }
}
