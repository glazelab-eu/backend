package eu.glazelab.api.controller;

import java.util.UUID;
import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.CombinationDtos.NewCombination;
import eu.glazelab.api.dto.CombinationDtos.TitleEdit;
import eu.glazelab.api.dto.CombinationDtos.TitleView;
import eu.glazelab.api.dto.CommonDtos.IdView;
import eu.glazelab.api.dto.TagDtos.TagEdit;
import eu.glazelab.api.dto.TagDtos.TagsView;
import eu.glazelab.api.model.Combination;
import eu.glazelab.api.model.Tags;
import eu.glazelab.api.repository.CombinationRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.ChangeRecorder;
import eu.glazelab.api.service.CombinationService;
import eu.glazelab.api.service.LibraryViews;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/combinations")
@RequiredArgsConstructor
public class CombinationController {
    private final CombinationRepository combinations;
    private final CurrentUser current;
    private final CombinationService service;
    private final LibraryViews views;
    private final ChangeRecorder changes;

    /** Every published combination and the caller's own drafts, newest first; the frontend filters the list. */
    @GetMapping @Transactional(readOnly = true) List<CombinationView> search(Authentication auth) {
        return views.combinations(current.viewer(auth));
    }

    @PostMapping IdView create(@Valid @RequestBody NewCombination body, Authentication auth) {
        return new IdView(service.create(body, current.user(auth).getId()));
    }

    /**
     * The author edits a combination; a draft stays a draft ({@code draft} is ignored, publishing has its
     * own endpoint) and holds only its author's photos, so its steps are never locked.
     */
    @PutMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateCombination(@PathVariable UUID id, @Valid @RequestBody NewCombination body, Authentication auth) {
        service.update(id, body, current.user(auth).getId());
    }

    /**
     * Corrects only the title (no need to send the whole combination): its author, or a site admin on someone else's
     * published combination (logged). Same title rules as the PUT; a draft may keep a blank title.
     */
    @PatchMapping("/{id}/title") TitleView retitleCombination(@PathVariable UUID id, @Valid @RequestBody TitleEdit body, Authentication auth) {
        return new TitleView(service.retitle(id, body.title(), current.user(auth).getId()));
    }

    /** The author publishes a draft: it needs a title and at least one step (a custom-only list is valid). */
    @PostMapping("/{id}/publish") @ResponseStatus(HttpStatus.NO_CONTENT)
    void publishCombination(@PathVariable UUID id, Authentication auth) {
        service.publish(id, current.user(auth).getId());
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeCombination(@PathVariable UUID id, Authentication auth) {
        service.remove(id, current.user(auth).getId());
    }

    /**
     * Any signed-in user may tag any combination they can see: published ones, and their own drafts.
     */
    @PatchMapping("/{id}/tags") @Transactional TagsView editCombinationTags(
            @PathVariable UUID id, @Valid @RequestBody TagEdit body, Authentication auth) {
        UUID actor = current.user(auth).getId();
        // Locked, so concurrent edits of one combination apply in turn and the tag limit holds.
        Combination combination = combinations.lockById(id).filter(found -> found.visibleTo(actor))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, CombinationService.NOT_FOUND));
        var tags = Tags.edited(combination.getTags(), body.adding(), body.removing());
        if (!tags.equals(combination.getTags())) changes.combination(combination);
        combination.setTags(tags);
        return TagsView.of(combination.getTags());
    }
}
