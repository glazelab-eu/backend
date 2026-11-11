package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ClubDtos.NewClub;
import eu.glazelab.api.dto.CommonDtos.IdView;
import eu.glazelab.api.dto.ClubDtos.ClubSettings;
import eu.glazelab.api.dto.ClubDtos.RoleInput;
import eu.glazelab.api.dto.ClubDtos.InviteInput;
import eu.glazelab.api.dto.ClubDtos.InviteView;
import eu.glazelab.api.dto.ClubDtos.InvitePreviewView;
import eu.glazelab.api.dto.ClubDtos.ClubView;
import eu.glazelab.api.dto.ClubDtos.MemberView;
import eu.glazelab.api.dto.ClubDtos.AcquiredInput;
import eu.glazelab.api.dto.ClubDtos.NewWishlistEntry;
import eu.glazelab.api.dto.ClubDtos.WishlistEntryView;
import eu.glazelab.api.dto.ClubDtos.WishlistView;
import eu.glazelab.api.service.WishlistService;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.ClubService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/clubs")
@RequiredArgsConstructor
public class ClubController {
    private final ClubService clubs;
    private final WishlistService wishlist;
    private final CurrentUser current;
    @GetMapping
    List<ClubView> clubs(Authentication auth) { return clubs.list(current.user(auth)); }
    @PostMapping
    IdView createClub(@Valid @RequestBody NewClub body, Authentication auth) {
        return new IdView(clubs.create(body, current.user(auth)));
    }
    @PutMapping("/{id}")
    void updateClub(@PathVariable UUID id, @Valid @RequestBody ClubSettings body, Authentication auth) {
        clubs.update(id, body, current.user(auth));
    }
    @GetMapping("/{id}/members")
    List<MemberView> clubMembers(@PathVariable UUID id, Authentication auth) {
        return clubs.members(id, current.user(auth));
    }
    @PutMapping("/{id}/members/{userId}")
    void changeClubRole(@PathVariable UUID id, @PathVariable UUID userId,
            @Valid @RequestBody RoleInput body, Authentication auth) {
        clubs.changeRole(id, userId, body.role(), current.user(auth));
    }
    /** A member leaves ({@code userId} is their own ID) or a club admin removes another member (see {@code ClubService.removeMember}). */
    @DeleteMapping("/{id}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeClubMember(@PathVariable UUID id, @PathVariable UUID userId, Authentication auth) {
        clubs.removeMember(id, userId, current.user(auth));
    }
    @PostMapping("/{id}/invite")
    InviteView regenerateInvite(@PathVariable UUID id, Authentication auth) {
        return clubs.regenerate(id, current.user(auth));
    }
    /** The club's wishlist, for its members (403 for anyone else, as the member list). */
    @GetMapping("/{id}/wishlist")
    WishlistView clubWishlist(@PathVariable UUID id, Authentication auth) {
        return wishlist.list(id, current.user(auth));
    }
    @PostMapping("/{id}/wishlist")
    WishlistEntryView addWishlistEntry(@PathVariable UUID id, @Valid @RequestBody NewWishlistEntry body, Authentication auth) {
        return wishlist.add(id, body, current.user(auth));
    }
    @DeleteMapping("/{id}/wishlist/{entryId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeWishlistEntry(@PathVariable UUID id, @PathVariable UUID entryId, Authentication auth) {
        wishlist.remove(id, entryId, current.user(auth));
    }
    /** Marks an entry acquired, or wanted again ({@code acquired} false); the state it already has changes nothing. */
    @PutMapping("/{id}/wishlist/{entryId}/acquired")
    WishlistEntryView setWishlistAcquired(@PathVariable UUID id, @PathVariable UUID entryId, @Valid @RequestBody AcquiredInput body,
            Authentication auth) {
        return wishlist.setAcquired(id, entryId, body.acquired(), current.user(auth));
    }
    @PostMapping("/invitations/preview")
    InvitePreviewView previewInvite(@Valid @RequestBody InviteInput body, Authentication auth) {
        current.user(auth);
        return clubs.preview(body.token());
    }
    @PostMapping("/join")
    IdView joinClub(@Valid @RequestBody InviteInput body, Authentication auth) {
        return new IdView(clubs.join(body.token(), current.user(auth)));
    }
}
