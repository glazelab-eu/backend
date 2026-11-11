package eu.glazelab.api.repository;

import java.util.UUID;
import eu.glazelab.api.model.CombinationStep;

import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CombinationStepRepository extends JpaRepository<CombinationStep, UUID> {
    /**
     * Names used by search and its response must not trigger a lazy query per glaze/manufacturer. Left
     * joins: custom steps have no glaze and must stay in the listing.
     */
    @Query("select s from CombinationStep s left join fetch s.glaze g left join fetch g.manufacturer"
            + " where s.combination.id in :combinationIds order by s.position")
    List<CombinationStep> findForCombinations(Collection<UUID> combinationIds);
    /** Whether any glaze step of any combination, drafts included, uses the glaze (custom steps have none). */
    boolean existsByGlaze_Id(UUID glazeId);
}
