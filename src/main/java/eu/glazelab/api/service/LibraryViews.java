package eu.glazelab.api.service;

import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.CombinationDtos.StepView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.GlazeDtos.LastEdit;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.dto.TagDtos;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.Combination;
import eu.glazelab.api.model.CombinationStep;
import eu.glazelab.api.model.Glaze;
import eu.glazelab.api.model.ImageRecord;
import eu.glazelab.api.repository.CombinationRepository;
import eu.glazelab.api.repository.CombinationStepRepository;
import eu.glazelab.api.repository.GlazeEditRepository;
import eu.glazelab.api.repository.GlazeRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.security.CurrentUser.Viewer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The glaze and combination views of the listings, the sync delta and the paged first sync: one mapper per type,
 * so a delta shows an item exactly as the listing would (same visibility rules, same per-viewer fields). Each
 * method runs a constant number of statements however many items it renders. Callers hold a read-only transaction.
 */
@Component @RequiredArgsConstructor
public class LibraryViews {
    private final GlazeRepository glazes;
    private final GlazeEditRepository edits;
    private final CombinationRepository combinations;
    private final CombinationStepRepository steps;
    private final ImageRecordRepository images;

    /** Every glaze (all are public), by manufacturer and name, with the pictures the viewer may see. */
    public List<GlazeView> glazes(Viewer viewer) {
        // One query for all glaze pictures instead of one per glaze, likewise one for the newest corrections.
        Map<UUID, List<ImageRecord>> pictures = images.findByGlazeIsNotNullOrderByCreatedAtDesc().stream()
                .collect(Collectors.groupingBy(ImageRecord::getGlazeId));
        return glazeViews(glazes.findAllByOrderByManufacturer_NameAscNameAsc(), pictures,
                GlazeEditRepository.byGlaze(edits.latest()), viewer);
    }

    /** The glazes among {@code ids} that still exist, in listing order. */
    public List<GlazeView> glazes(Collection<UUID> ids, Viewer viewer) {
        if (ids.isEmpty()) return List.of();
        Map<UUID, List<ImageRecord>> pictures = images.findByGlaze_IdInOrderByCreatedAtDesc(ids).stream()
                .collect(Collectors.groupingBy(ImageRecord::getGlazeId));
        return glazeViews(glazes.findByIdInOrderByManufacturer_NameAscNameAsc(ids), pictures,
                GlazeEditRepository.byGlaze(edits.latestOf(ids)), viewer);
    }

    private static List<GlazeView> glazeViews(List<Glaze> listed, Map<UUID, List<ImageRecord>> pictures,
            Map<UUID, LastEdit> lastEdits, Viewer viewer) {
        return listed.stream()
                .map(g -> GlazeView.of(g, ImageView.visibleViews(pictures.getOrDefault(g.getId(), List.of()), viewer),
                        lastEdits.get(g.getId())))
                .toList();
    }

    /** Every published combination and the viewer's own drafts, newest first. */
    public List<CombinationView> combinations(Viewer viewer) {
        List<Combination> listed = combinations.findVisible(viewer.userId());
        return combinationViews(listed, viewer, listed.stream().map(Combination::getId).collect(Collectors.toSet()));
    }

    /** The combinations among {@code ids} the viewer may see (published, or their own drafts), in listing order. */
    public List<CombinationView> combinations(Collection<UUID> ids, Viewer viewer) {
        if (ids.isEmpty()) return List.of();
        List<Combination> listed = combinations.findVisibleAmong(ids, viewer.userId());
        // basedOnId names a source only when the viewer may see it, as in the full listing.
        Set<UUID> sources = listed.stream().map(Combination::getBasedOnId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> visibleSources = sources.isEmpty() ? Set.of() : new HashSet<>(combinations.visibleIdsAmong(sources, viewer.userId()));
        return combinationViews(listed, viewer, visibleSources);
    }

    private List<CombinationView> combinationViews(List<Combination> listed, Viewer viewer, Set<UUID> visibleSources) {
        if (listed.isEmpty()) return List.of();
        UUID userId = viewer.userId();
        List<UUID> ids = listed.stream().map(Combination::getId).toList();
        Map<UUID, List<CombinationStep>> stepsByCombination = steps.findForCombinations(ids).stream()
                .collect(Collectors.groupingBy(CombinationStep::getCombinationId));
        Map<UUID, List<ImageRecord>> pictures = images.findByCombination_IdInOrderByCreatedAtDesc(ids).stream()
                .collect(Collectors.groupingBy(ImageRecord::getCombinationId));
        List<CombinationView> all = new ArrayList<>();
        for (Combination combination : listed) {
            List<StepView> stepViews = stepsByCombination.getOrDefault(combination.getId(), List.of()).stream()
                    .map(LibraryViews::view).toList();
            AppUser author = combination.getCreatedBy();
            boolean own = author != null && author.getId().equals(userId);
            var combinationPictures = pictures.getOrDefault(combination.getId(), List.of());
            var firing = combination.getFinalFiring();
            all.add(new CombinationView(combination.getId(), combination.getTitle(),
                    combination.getDescription(), combination.getCreatedAt(), combination.getPublishedAt(),
                    combination.getBasedOnId() != null && visibleSources.contains(combination.getBasedOnId())
                            ? combination.getBasedOnId() : null,
                    firing.cone(), firing.temperatureC(), firing.atmosphere(), firing.clayBody(), firing.kiln(),
                    firing.firedOn(),
                    author == null ? null : author.getDisplayName(), author == null ? null : author.getId(),
                    own, combination.isDraft(),
                    own && CombinationService.hasForeignPhotos(combinationPictures, userId, viewer.clubIds()), stepViews,
                    ImageView.visibleViews(combinationPictures, viewer),
                    TagDtos.sorted(combination.getTags())));
        }
        return all;
    }

    /**
     * The glaze fields of a step mirror the glaze's current manufacturer and name; a glaze correction re-sends the
     * glaze only, so sync clients copy a corrected name into the steps that use it (see backend AGENTS.md "Sync").
     */
    private static StepView view(CombinationStep step) {
        Glaze glaze = step.getGlaze();
        return new StepView(step.getPosition(), step.getType(), glaze == null ? null : glaze.getId(),
                step.getCoats(), step.getLabel(), step.getMethod(), step.getNotes(),
                glaze == null ? null : glaze.getManufacturer().getName(), glaze == null ? null : glaze.getName());
    }
}
