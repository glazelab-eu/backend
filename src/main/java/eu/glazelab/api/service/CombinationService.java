package eu.glazelab.api.service;

import eu.glazelab.api.model.Text;
import eu.glazelab.api.dto.CombinationDtos.NewCombination;
import eu.glazelab.api.dto.CombinationDtos.StepInput;
import eu.glazelab.api.model.AdminAction;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.Combination;
import eu.glazelab.api.model.CombinationStep;
import eu.glazelab.api.model.FinalFiring;
import eu.glazelab.api.model.ImageRecord;
import eu.glazelab.api.model.StepType;
import eu.glazelab.api.model.Tags;
import eu.glazelab.api.repository.AdminActionRepository;
import eu.glazelab.api.repository.CombinationRepository;
import eu.glazelab.api.repository.CombinationStepRepository;
import eu.glazelab.api.repository.FavoriteRepository;
import eu.glazelab.api.repository.GlazeRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.security.CurrentUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every combination write. Lock order: actor account → step glazes by ID → a variation's source → the
 * combination → its image rows.
 */
@Service @RequiredArgsConstructor
public class CombinationService {
    public static final int MAX_STEPS = 30;
    public static final String TOO_MANY_STEPS = "Maximum " + MAX_STEPS + " steps";
    public static final String UNKNOWN_GLAZE = "Unknown glaze in steps";
    public static final String AUTHOR_REQUIRED = "Only the author can change this combination";
    public static final String STEPS_LOCKED = "Other people have added photos. Record a variation to change the steps.";
    public static final String DELETE_LOCKED = "Other people have added photos. This combination cannot be deleted.";
    public static final String NOT_FOUND = "Combination not found";
    public static final String TITLE_REQUIRED = "Give the combination a title";
    public static final String STEP_REQUIRED = "Add at least one step";
    private final CombinationRepository combinations;
    private final CombinationStepRepository steps;
    private final ImageRecordRepository images;
    private final GlazeRepository glazes;
    private final FavoriteRepository favorites;
    private final CurrentUser current;
    private final ImageRemovalService imageRemoval;
    private final ChangeRecorder changes;
    private final TransactionTemplate transactions;
    private final AdminActionRepository adminActions;

    /** Scalar preflight avoids caching a parent before a later locking read. Caller holds the account lock. */
    public void requireAuthor(UUID id, UUID actor) {
        var owner = combinations.ownership(id).filter(found -> found.visibleTo(actor))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (!actor.equals(owner.getAuthorId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, AUTHOR_REQUIRED);
    }

    /** Caller holds a transaction. Account first, then parent, matching uploads and audit removals. */
    public Combination lockOwned(UUID id, UUID actor) {
        current.lockUser(actor);
        Combination combination = combinations.lockById(id).filter(found -> found.visibleTo(actor))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (combination.getCreatedBy() == null || !combination.getCreatedBy().getId().equals(actor))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, AUTHOR_REQUIRED);
        return combination;
    }

    /**
     * A photo of another or an anonymous uploader that the author can see locks the steps (edit 409) and the
     * deletion: the one copy of that rule, also behind {@code CombinationView.stepsLocked}. Uploads and visibility
     * changes keep every foreign photo visible to the author ({@code ImageController.AUTHOR_MUST_SEE}); a CLUB photo
     * of a club the author left (or was removed from) is no longer visible to them, so it stops locking, and a
     * deletion removes it with the combination (audited like every photo removal). The author's clubs are read
     * without a club lock: a leave racing with an edit or deletion gives the result of one order or the other.
     */
    public static boolean hasForeignPhotos(Collection<ImageRecord> pictures, UUID actorId, Set<UUID> actorClubIds) {
        return pictures.stream().anyMatch(image -> !image.uploadedBy(actorId) && image.visibleTo(actorId, actorClubIds));
    }

    /** A published combination needs a title and at least one step; a draft may lack both. */
    public static void requirePublishable(String title, int stepCount) {
        if (title == null || title.isBlank()) throw invalid(TITLE_REQUIRED);
        if (stepCount == 0) throw invalid(STEP_REQUIRED);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** The one validator of a step list: at most {@link #MAX_STEPS} steps, each checked by its type. */
    private static void checked(List<StepInput> inputs) {
        if (inputs.size() > MAX_STEPS) throw invalid(TOO_MANY_STEPS);
        inputs.forEach(CombinationService::checked);
    }

    /** Checks a step's fields by its type; returns the effective type. */
    private static StepType checked(StepInput step) {
        StepType type = step.type() == null ? StepType.GLAZE : step.type();
        if (type == StepType.GLAZE) {
            if (step.glazeId() == null || step.coats() == null)
                throw invalid("Glaze steps need a glaze and coats");
            if (step.label() != null) throw invalid("Glaze steps have no label");
        } else {
            if (step.label() == null || step.label().isBlank()) throw invalid("Custom steps need a label");
            if (step.glazeId() != null || step.coats() != null || step.method() != null)
                throw invalid("Custom steps have no glaze, coats or method");
        }
        return type;
    }

    /** A field the request left out keeps {@code stored}; one sent as null clears it. */
    private static <T> T sent(Optional<T> field, T stored) {
        return field == null ? stored : field.orElse(null);
    }

    /**
     * The request's final firing, checked and normalised: every field it sends replaces the stored one, a
     * missing field keeps it (clients from before the final firing send none and must not clear it).
     */
    private static FinalFiring firing(NewCombination body, FinalFiring stored) {
        return FinalFiring.of(sent(body.cone(), stored.cone()), sent(body.temperatureC(), stored.temperatureC()),
                sent(body.atmosphere(), stored.atmosphere()), sent(body.clayBody(), stored.clayBody()),
                sent(body.kiln(), stored.kiln()), sent(body.firedOn(), stored.firedOn()));
    }

    /**
     * The tags after the author's PUT: with {@code baseTags}, only what the form added or removed relative to where it
     * started, so tags other accounts set meanwhile stay (any signed-in user tags combinations, without a version
     * check); without it the sent set replaces the stored one.
     */
    private static Set<String> editedTags(Set<String> stored, NewCombination body) {
        Set<String> next = Tags.of(body.tags());
        if (body.baseTags() == null) return next;
        Set<String> base = body.baseTags().stream().map(Tags::normalize).collect(Collectors.toSet());
        return Tags.edited(stored, next.stream().filter(tag -> !base.contains(tag)).toList(),
                base.stream().filter(tag -> !next.contains(tag)).toList());
    }

    /** Creates a combination (a draft when {@code body.draft()}) for the actor; returns its ID. */
    public UUID create(NewCombination body, UUID actorId) {
        return transactions.execute(status -> {
            AppUser user = current.lockUser(actorId);
            boolean draft = Boolean.TRUE.equals(body.draft());
            // Every saved step satisfies its type, draft or not; only published content needs a title and a step.
            checked(body.steps());
            FinalFiring firing = firing(body, FinalFiring.NONE);
            if (!draft) requirePublishable(body.title(), body.steps().size());
            lockStepGlazes(body.steps());
            Set<String> tags = Tags.of(body.tags());
            Combination created = new Combination(Text.clean(body.title(), Text.Kind.LINE), Text.check(body.description(), Text.Kind.MULTI_LINE), user, draft,
                    visibleSource(body.basedOnId(), user.getId()));
            created.setTags(tags);
            created.setFinalFiring(firing);
            Combination combination = combinations.save(created);
            steps.saveAll(records(combination, body.steps()));
            changes.combination(combination);
            return combination.getId();
        });
    }

    /**
     * The author edits a combination; a draft stays a draft ({@code draft} is ignored, publishing has its
     * own endpoint) and holds only its author's photos, so its steps are never locked.
     */
    public void update(UUID id, NewCombination body, UUID actor) {
        transactions.executeWithoutResult(status -> {
            current.lockUser(actor);
            requireAuthor(id, actor);
            checked(body.steps());
            var requested = body.steps().stream().map(CombinationService::canonical).toList();
            lockStepGlazes(body.steps());
            Combination combination = lockOwned(id, actor);
            FinalFiring firing = firing(body, combination.getFinalFiring());
            if (!combination.isDraft()) requirePublishable(body.title(), body.steps().size());
            var existing = steps.findForCombinations(List.of(id));
            var previous = existing.stream().map(step -> canonical(new StepInput(step.getType(),
                    step.getGlaze() == null ? null : step.getGlaze().getId(), step.getCoats(), step.getLabel(),
                    step.getMethod(), step.getNotes()))).toList();
            if (!requested.equals(previous)) {
                if (!combination.isDraft()
                        && hasForeignPhotos(images.findByCombination_IdInOrderByCreatedAtDesc(List.of(id)), actor,
                                current.clubIds(actor)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, STEPS_LOCKED);
                var next = records(combination, body.steps());
                steps.deleteAll(existing);
                steps.flush();
                steps.saveAll(next);
            }
            combination.edit(Text.clean(body.title(), Text.Kind.LINE), Text.check(body.description(), Text.Kind.MULTI_LINE));
            combination.setTags(editedTags(combination.getTags(), body));
            // Metadata like the title: a change never touches the steps or their photo lock.
            combination.setFinalFiring(firing);
            changes.combination(combination);
        });
    }

    /**
     * Corrects only the title: the author on their own combination, a site admin on any combination they may see (a draft
     * of someone else is 404 for them too). Same title rules as the PUT; an unchanged title writes nothing. A site admin's
     * correction of someone else's title is logged in {@code admin_action} with both titles. Locks actor → combination.
     */
    public String retitle(UUID id, String requested, UUID actor) {
        return transactions.execute(status -> {
            AppUser user = current.lockUser(actor);
            // Scalar preflight before the row lock, so the locking read loads the row fresh.
            var owner = combinations.ownership(id).filter(found -> found.visibleTo(actor))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
            boolean author = actor.equals(owner.getAuthorId());
            if (!author && !user.isSiteAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, AUTHOR_REQUIRED);
            Combination combination = combinations.lockById(id).filter(found -> found.visibleTo(actor))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
            String title = Text.clean(requested, Text.Kind.LINE);
            if (!combination.isDraft() && title.isEmpty()) throw invalid(TITLE_REQUIRED);
            String previous = combination.getTitle();
            if (title.equals(previous)) return previous;
            combination.retitle(title);
            changes.combination(combination);
            if (!author) adminActions.save(AdminAction.titleCorrected(actor, owner.getAuthorId(), id, previous, title, Instant.now()));
            return title;
        });
    }

    /**
     * The source of a variation if the author may see it, else null. The row lock comes after the glazes
     * and before the new row, like source deletion, which also locks its combination first; a source
     * deleted meanwhile is gone by now.
     */
    private UUID visibleSource(UUID sourceId, UUID actor) {
        if (sourceId == null) return null;
        return combinations.lockById(sourceId).filter(found -> found.visibleTo(actor)).map(Combination::getId).orElse(null);
    }

    /** Canonical comparison only: a metadata-only UI round trip does not rewrite nullable/blank steps. */
    private static StepInput canonical(StepInput step) {
        return new StepInput(checked(step), step.glazeId(), step.coats(),
                step.label() == null ? "" : Text.strip(step.label()),
                step.method() == null ? "" : Text.strip(step.method()), step.notes() == null ? "" : step.notes());
    }

    private List<CombinationStep> records(Combination combination, List<StepInput> inputs) {
        List<CombinationStep> result = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            StepInput input = inputs.get(index);
            result.add(checked(input) == StepType.CUSTOM
                    ? CombinationStep.customStep(combination, index + 1, Text.strip(input.label()), input.notes())
                    : CombinationStep.glazeStep(combination, index + 1,
                            glazes.findById(input.glazeId()).orElseThrow(() -> invalid(UNKNOWN_GLAZE)),
                            input.coats(), input.method(), input.notes()));
        }
        return result;
    }

    private void lockStepGlazes(List<StepInput> inputs) {
        var ids = inputs.stream().map(StepInput::glazeId).filter(Objects::nonNull).distinct().toList();
        // DB ID order matches account deletion's authored-glaze locks, before combination/image locks.
        if (!ids.isEmpty() && glazes.lockByIds(ids).size() != ids.size()) throw invalid(UNKNOWN_GLAZE);
    }

    /** The author publishes a draft once it is complete; its saved steps were checked by type on save. */
    public void publish(UUID id, UUID actor) {
        transactions.executeWithoutResult(status -> {
            Combination combination = lockOwned(id, actor);
            if (!combination.isDraft()) return;
            requirePublishable(combination.getTitle(), steps.findForCombinations(List.of(id)).size());
            combination.publish();
            changes.combination(combination);
            // Variations name their source only to viewers who may see it: now everyone.
            recordVariations(id);
        });
    }

    /** Variations show their source's ID only while the viewer may see the source: a publication or removal changes them. */
    private void recordVariations(UUID sourceId) {
        for (var variation : combinations.variationsOf(sourceId))
            changes.combination(variation.getId(), variation.getAuthorId(), variation.getPublishedAt() != null);
    }

    public void remove(UUID id, UUID actor) {
        transactions.executeWithoutResult(status -> {
            Combination combination = lockOwned(id, actor);
            var pictures = images.lockByCombinationId(id);
            if (hasForeignPhotos(pictures, actor, current.clubIds(actor)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, DELETE_LOCKED);
            imageRemoval.remove(pictures, actor);
            changes.combination(combination);
            // The database clears based_on_id of its variations.
            recordVariations(id);
            favorites.deleteByCombinationId(id);
            steps.deleteAll(steps.findForCombinations(List.of(id)));
            combinations.delete(combination);
            combinations.flush();
        });
    }
}
