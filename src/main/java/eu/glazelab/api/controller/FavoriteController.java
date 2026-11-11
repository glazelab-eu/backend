package eu.glazelab.api.controller;

import eu.glazelab.api.model.Favorite;
import eu.glazelab.api.dto.FavoriteDtos.FavoriteCountView;
import eu.glazelab.api.dto.FavoriteDtos.FavoriteCountsView;
import eu.glazelab.api.repository.FavoriteRepository;
import eu.glazelab.api.repository.GlazeRepository;
import eu.glazelab.api.repository.CombinationRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.CombinationService;
import eu.glazelab.api.service.GlazeService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/favorites")
@Transactional
@RequiredArgsConstructor
public class FavoriteController {
    public record FavoritesView(List<UUID> glazeIds, List<UUID> combinationIds) {}
    private final FavoriteRepository favorites;
    private final GlazeRepository glazes;
    private final CombinationRepository combinations;
    private final CurrentUser current;
    @GetMapping("/counts") @Transactional(readOnly = true)
    FavoriteCountsView favoriteCounts() {
        return new FavoriteCountsView(
                favorites.glazeCounts().stream().map(row -> new FavoriteCountView(row.getItemId(), row.getTotal())).toList(),
                favorites.combinationCounts().stream().map(row -> new FavoriteCountView(row.getItemId(), row.getTotal())).toList());
    }
    @GetMapping @Transactional(readOnly = true)
    FavoritesView favorites(Authentication auth) {
        UUID userId = current.user(auth).getId();
        // One query: the combination rows come with the favourites, so the visibility check below touches no lazy proxy.
        var rows = favorites.findWithCombinationByUser_Id(userId);
        // Publishing is one-way and others' drafts cannot be starred, so the filter is a guard only.
        return new FavoritesView(rows.stream().filter(f -> f.getGlaze() != null).map(f -> f.getGlaze().getId()).sorted().toList(),
                rows.stream().filter(f -> f.getCombination() != null && f.getCombination().visibleTo(userId))
                        .map(f -> f.getCombination().getId()).sorted().toList());
    }
    @PutMapping("/glazes/{id}")
    void favoriteGlaze(@PathVariable UUID id, Authentication auth) {
        var user = current.lockUser(current.user(auth).getId());
        var glaze = glazes.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GlazeService.UNKNOWN_GLAZE));
        if (favorites.findByUser_IdAndGlaze_Id(user.getId(), id).isEmpty()) favorites.save(new Favorite(user, glaze, null));
    }
    @PutMapping("/combinations/{id}")
    void favoriteCombination(@PathVariable UUID id, Authentication auth) {
        var user = current.lockUser(current.user(auth).getId());
        // Someone else's draft does not exist for this account.
        var combination = combinations.findById(id).filter(found -> found.visibleTo(user.getId())).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, CombinationService.NOT_FOUND));
        if (favorites.findByUser_IdAndCombination_Id(user.getId(), id).isEmpty()) favorites.save(new Favorite(user, null, combination));
    }
    @DeleteMapping("/glazes/{id}")
    void unfavoriteGlaze(@PathVariable UUID id, Authentication auth) {
        var user = current.lockUser(current.user(auth).getId());
        favorites.findByUser_IdAndGlaze_Id(user.getId(), id).ifPresent(favorites::delete);
    }
    @DeleteMapping("/combinations/{id}")
    void unfavoriteCombination(@PathVariable UUID id, Authentication auth) {
        var user = current.lockUser(current.user(auth).getId());
        favorites.findByUser_IdAndCombination_Id(user.getId(), id).ifPresent(favorites::delete);
    }
}
