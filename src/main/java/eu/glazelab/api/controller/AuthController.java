package eu.glazelab.api.controller;

import eu.glazelab.api.model.Text.Plain;
import static eu.glazelab.api.model.Text.Kind.NAME;

import eu.glazelab.api.config.SourceCode;
import eu.glazelab.api.dto.AuthDtos.AuthOptionsView;
import eu.glazelab.api.dto.AuthDtos.CsrfView;
import eu.glazelab.api.dto.AuthDtos.OperatorContactView;
import eu.glazelab.api.dto.AuthDtos.RegistrationView;
import eu.glazelab.api.dto.AuthDtos.UserView;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.service.AccountDeletionService;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.service.AvatarService;
import eu.glazelab.api.service.FeedService;
import eu.glazelab.api.service.AccountMail;
import eu.glazelab.api.model.AccountToken.Purpose;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.SessionLogin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    public static final String CONTACT_NOT_CONFIGURED = "No contact address is configured.";
    /**
     * No password and no consent: the first password, the terms acceptance and the error-report choice come with the
     * activation (whoever registers may not own the mailbox). Older clients' `password`, `termsAccepted`, `termsVersion`
     * and `diagnosticsOptIn` are ignored.
     */
    public record Register(@Email @Plain @NotBlank @Size(max=255) String email,
                    @NotBlank @Plain(NAME) @Size(max=100) String displayName) {}
    public record TermsInput(@NotBlank @Size(max=32) String termsVersion) {}
    public record DeleteAccountInput(@NotBlank @Size(max=128) String password) {}
    public record EmailInput(@Email @Plain @NotBlank @Size(max=255) String email) {}
    /** Activation (a pending account): password, display name and the current terms, else a 409 challenge or 400. */
    public record ConfirmationInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
            @Schema(nullable = true) @Size(min=8, max=128) String password,
            @Schema(nullable = true) @Plain(NAME) @Size(min=1, max=100) String displayName,
            @Schema(nullable = true, description = "Activation only: the mailbox owner accepts the terms") Boolean termsAccepted,
            @Schema(nullable = true, description = "Activation only: the accepted terms version") @Size(max=32) String termsVersion,
            @Schema(nullable = true, description = "Activation only: send error reports (default false)") Boolean diagnosticsOptIn) {}
    public record PasswordChangeInput(@NotBlank @Size(max=128) String currentPassword,
            @NotBlank @Size(min=8, max=128) String newPassword) {}
    public record ResetInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Size(min=8, max=128) String password,
            @Schema(nullable = true) @Plain(NAME) @Size(min=1, max=100) String displayName,
            @Schema(nullable = true, description = "Activation only: the mailbox owner accepts the terms") Boolean termsAccepted,
            @Schema(nullable = true, description = "Activation only: the accepted terms version") @Size(max=32) String termsVersion,
            @Schema(nullable = true, description = "Activation only: send error reports (default false)") Boolean diagnosticsOptIn) {}
    private final AvatarService avatars;
    private final AccountService accounts;
    private final AccountMail mail;
    private final CurrentUser current;
    private final AccountDeletionService deletion;
    private final SessionLogin sessions;
    private final FeedService feed;
    private final SourceCode source;
    @Value("${glazelab.operator-email:}")
    private String operatorEmail;

    @GetMapping("/options") AuthOptionsView options() {
        return new AuthOptionsView(mail.enabled(), AccountService.TERMS_VERSION, source.url());
    }
    /**
     * The operator address, only when a person asks for it (the client fetches it on a click): never in
     * {@code /options} or a page, never cached, and counted in its own tight per-IP budget (RateLimiter.Kind.CONTACT).
     */
    @GetMapping("/operator-contact") ResponseEntity<OperatorContactView> operatorContact() {
        if (operatorEmail.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, CONTACT_NOT_CONFIGURED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new OperatorContactView(operatorEmail.trim()));
    }
    /**
     * Tells the browser to drop its HTTP cache of this origin, so a shared device does not keep the club and private photos
     * it may otherwise keep for a week (30-1). The app asks after a sign-out and an account deletion, without waiting
     * (LOGOUT-2): the browser may hold this answer for seconds while it empties its cache, which the sign-out answer itself
     * must never do. {@code "cache"} only: never {@code "storage"}, {@code "cookies"} or {@code "*"}, which would wipe the
     * app's offline data, the service worker and its {@code pendingLogout} state. A GET, so it needs no CSRF token (sign-out
     * has just replaced it): it reads nothing, writes nothing, sets no cookie and reflects nothing. The header goes only to
     * the app's own requests ({@code Sec-Fetch-Site} {@code same-origin} or {@code none}) and to browsers that send no
     * {@code Sec-Fetch-Site}: another site embedding this URL (an image, a link) gets the same 204 without it, so it cannot
     * empty a visitor's cache of this origin. {@code no-store} and no {@code Vary}: no cache may hand the answer without
     * the header to the app. Not rate limited ({@code RateLimiter.kind}). Browsers honour it only on secure origins.
     */
    @GetMapping("/clear-cache") @ResponseStatus(HttpStatus.NO_CONTENT)
    void clearBrowserCache(@Parameter(hidden = true) @RequestHeader(name = "Sec-Fetch-Site", required = false) String site,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        if (site == null || site.equals("same-origin") || site.equals("none")) response.setHeader(CLEAR_SITE_DATA, CACHE_ONLY);
    }
    public static final String CLEAR_SITE_DATA = "Clear-Site-Data";
    public static final String CACHE_ONLY = "\"cache\"";
    /** Password confirmation and CSRF are required, including when newer terms have not been accepted. */
    @PostMapping("/delete") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAccount(Authentication auth, @Valid @RequestBody DeleteAccountInput body,
            HttpServletRequest request, HttpServletResponse response) {
        deletion.delete(current.user(auth).getId(), body.password());
        // Every stored session of the account went with its row (FK cascade); this one also loses its cookie.
        sessions.signOut(request, response);
    }
    @PostMapping("/password/request") void requestPassword(@Valid @RequestBody EmailInput body) {
        requireEmail();
        accounts.request(body.email(), Purpose.RESET_PASSWORD);
    }
    /**
     * Current password and CSRF are required, also when newer terms have not been accepted (like deletion). Every other
     * session ends; this one is signed in again with the new session version, which also renews its session ID and
     * CSRF token.
     */
    @PostMapping("/password/change") void changePassword(Authentication auth, @Valid @RequestBody PasswordChangeInput body,
            HttpServletRequest request, HttpServletResponse response) {
        sessions.signIn(accounts.changePassword(current.user(auth).getId(), body.currentPassword(), body.newPassword()), request, response);
    }
    @PostMapping("/password/reset") void resetPassword(@Valid @RequestBody ResetInput body) {
        accounts.reset(body.token(), body.password(), body.displayName(),
                new AccountService.Consent(body.termsAccepted(), body.termsVersion(), body.diagnosticsOptIn()));
    }
    @PostMapping("/verification/request") void requestVerification(@Valid @RequestBody EmailInput body) {
        requireEmail();
        accounts.request(body.email(), Purpose.VERIFY_EMAIL);
    }
    @PostMapping("/verification/confirm") void verifyEmail(@Valid @RequestBody ConfirmationInput body) {
        accounts.verify(body.token(), body.password(), body.displayName(),
                new AccountService.Consent(body.termsAccepted(), body.termsVersion(), body.diagnosticsOptIn()));
    }
    @GetMapping("/csrf") CsrfView csrf(@Parameter(hidden = true) CsrfToken token) { return new CsrfView(token.getToken()); }
    @PostMapping("/register") RegistrationView register(@Valid @RequestBody Register body) {
        try { return registration(body); }
        catch (DataIntegrityViolationException ex) {
            // A concurrent registration inserted the same new address first: once more, it is an existing address
            // and answers like one, never the 409 that would set this race apart.
            if (!ApiErrorHandler.isUniqueViolation(ex)) throw ex;
            return registration(body);
        }
    }
    private RegistrationView registration(Register body) {
        return new RegistrationView(true, accounts.register(body.email(), body.displayName()));
    }
    private void requireEmail() {
        if (!mail.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, AccountService.EMAIL_NOT_CONFIGURED);
    }
    @GetMapping("/me") UserView me(Authentication auth) {
        AppUser user = current.optionalUser(auth);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, CurrentUser.SIGN_IN_REQUIRED);
        return UserView.of(user, avatars.idOf(user.getId()), feed.unread(user));
    }
    /** Records that the signed-in account accepts the current terms, with the server's time. */
    @PostMapping("/terms") UserView acceptTerms(Authentication auth, @Valid @RequestBody TermsInput body) {
        AppUser user = accounts.acceptTerms(current.user(auth).getId(), body.termsVersion());
        return UserView.of(user, avatars.idOf(user.getId()), feed.unread(user));
    }
}
