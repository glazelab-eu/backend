package eu.glazelab.api.controller;

import eu.glazelab.api.dto.BuddyDtos.BuddiesView;
import eu.glazelab.api.dto.BuddyDtos.BuddyGlazeView;
import eu.glazelab.api.dto.BuddyDtos.BuddyLinkInput;
import eu.glazelab.api.dto.BuddyDtos.BuddyLinkPreviewView;
import eu.glazelab.api.dto.BuddyDtos.BuddyLinkView;
import eu.glazelab.api.dto.BuddyDtos.BuddyMemberInput;
import eu.glazelab.api.dto.BuddyDtos.BuddyRequestView;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.BuddyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Glaze buddies, signed-in only; the rules are in {@link BuddyService} and the backend guide "Buddies". */
@RestController
@RequestMapping("/api/buddies")
@RequiredArgsConstructor
public class BuddyController {
    private final BuddyService buddies;
    private final CurrentUser current;

    @GetMapping
    BuddiesView buddies(Authentication auth) { return buddies.list(current.user(auth)); }

    /** Which glazes accepted buddies have on their personal shelf, with those buddies' names; never amounts. */
    @GetMapping("/availability")
    List<BuddyGlazeView> buddyAvailability(Authentication auth) { return buddies.availability(current.user(auth)); }

    /** Creates or replaces the caller's buddy link; the token is answered only here. */
    @PostMapping("/link")
    BuddyLinkView replaceBuddyLink(Authentication auth) { return buddies.replaceLink(current.user(auth)); }

    @PostMapping("/link/preview")
    BuddyLinkPreviewView previewBuddyLink(@Valid @RequestBody BuddyLinkInput body, Authentication auth) {
        return buddies.preview(body.token(), current.user(auth));
    }

    @PostMapping("/link/request")
    BuddyRequestView requestBuddyByLink(@Valid @RequestBody BuddyLinkInput body, Authentication auth) {
        return buddies.requestByLink(body.token(), current.user(auth));
    }

    @PostMapping("/requests")
    BuddyRequestView requestBuddyInClub(@Valid @RequestBody BuddyMemberInput body, Authentication auth) {
        return buddies.requestMember(body.clubId(), body.userId(), current.user(auth));
    }

    @PostMapping("/{userId}/accept") @ResponseStatus(HttpStatus.NO_CONTENT)
    void acceptBuddy(@PathVariable UUID userId, Authentication auth) { buddies.accept(userId, current.user(auth)); }

    @PostMapping("/{userId}/decline") @ResponseStatus(HttpStatus.NO_CONTENT)
    void declineBuddy(@PathVariable UUID userId, Authentication auth) { buddies.decline(userId, current.user(auth)); }

    @PostMapping("/{userId}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancelBuddyRequest(@PathVariable UUID userId, Authentication auth) { buddies.cancel(userId, current.user(auth)); }

    @DeleteMapping("/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeBuddy(@PathVariable UUID userId, Authentication auth) { buddies.remove(userId, current.user(auth)); }
}
