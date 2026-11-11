package eu.glazelab.api.controller;

import eu.glazelab.api.model.Text;
import eu.glazelab.api.model.Text.Plain;
import static eu.glazelab.api.model.Text.Kind.MULTI_LINE;

import eu.glazelab.api.dto.BanDtos.BanPageView;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.AccountDeletionService;
import eu.glazelab.api.service.EmailBlocklist;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account bans (backend guide "Bans"), site admins only: 401 for guests and 403 for everyone else before anything
 * about the target is read, writes with CSRF and the current terms like every admin write.
 */
@RestController @RequestMapping("/api/admin") @RequiredArgsConstructor
public class BanController {
    /**
     * {@code reason} (required, stripped, at most 500 characters) is kept with the blocklist entry and the log, for the
     * site admins only; {@code blockPictures} (default false) also blocks the account's reported profile pictures.
     */
    public record BanInput(@NotBlank @Plain(MULTI_LINE) @Size(max = 500) String reason, @Schema(nullable = true) Boolean blockPictures) {}

    private final CurrentUser current;
    private final AccountDeletionService accounts;
    private final EmailBlocklist blocklist;

    /**
     * Removes the account exactly like an account deletion and blocks its address from registering again (204). 409
     * for the caller's own account and for site admins, 404 for an unknown account.
     */
    @PostMapping("/users/{id}/ban") @ResponseStatus(HttpStatus.NO_CONTENT)
    void ban(@PathVariable UUID id, @Valid @RequestBody BanInput body, Authentication auth) {
        UUID admin = current.siteAdmin(auth).getId();
        accounts.ban(admin, id, Text.clean(body.reason(), Text.Kind.MULTI_LINE), Boolean.TRUE.equals(body.blockPictures()));
    }

    /** The blocklist, newest first, in pages of {@link ImageDeletionController#PAGE_SIZE}; never an address. */
    @GetMapping("/bans")
    BanPageView bans(@RequestParam(defaultValue = "0") int page, Authentication auth) {
        current.siteAdmin(auth);
        return blocklist.page(PageRequest.of(ImageDeletionController.page(page), ImageDeletionController.PAGE_SIZE,
                Sort.by("bannedAt", "id").descending()));
    }

    /** Unban (204): the address may register again; 404 for an unknown entry. */
    @DeleteMapping("/bans/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void unban(@PathVariable UUID id, Authentication auth) {
        blocklist.unban(id, current.siteAdmin(auth).getId());
    }
}
