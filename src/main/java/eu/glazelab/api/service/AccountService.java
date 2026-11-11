package eu.glazelab.api.service;

import eu.glazelab.api.model.Text;
import eu.glazelab.api.model.AccountToken;
import eu.glazelab.api.model.AccountToken.Purpose;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.EmailAddresses;
import eu.glazelab.api.repository.AccountTokenRepository;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.LoginSessionRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.SecureTokens;
import eu.glazelab.api.security.SignInDelays;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
@RequiredArgsConstructor
public class AccountService {
    public static final String TERMS_VERSION = "2026-11-01.5";
    public static final String EMAIL_NOT_CONFIGURED = "Email is not configured on this server.";
    public static final String TERMS_NOT_ACCEPTED = "Please accept the current terms of service.";
    public static final String ACTIVATION_REQUIRED = "Set a fresh password and your display name to activate this account.";
    public static final String WRONG_PASSWORD = "Incorrect password. Your password was not changed.";
    public static final String SAME_PASSWORD = "Choose a new password that differs from your current one.";
    private final AppUserRepository users;
    private final AccountTokenRepository tokens;
    private final LoginSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final AccountMail mail;
    private final PendingAccounts pending;
    private final EmailBlocklist blocklist;
    private final ApplicationEventPublisher events;
    private final SignInDelays delays;
    private final TransactionTemplate transactions;
    @Value("${glazelab.require-email-verification:false}")
    private boolean requireEmailVerification;
    /**
     * Creates a pending account without a usable password; the activation challenge sets the first one. An address
     * that already has an account gets mail instead (its pending owner a new confirmation link, a verified owner a
     * password-reset link) and the same answer, so registration never tells whether the address is in use. Without
     * SMTP (local development only) the raw setup token of an account created here is the answer, never one for an
     * existing address; a refusal (503 without required mail) comes before anything is written. With SMTP the
     * request thread does the same work for every address (one hash, the blocklist lookup, two unlocked lookups, the
     * insert of a new account) and {@link #mailRegistration} sends after commit, so neither the answer nor its timing
     * depends on a mail being sent. A banned address (backend guide "Bans") is answered like an address in use and gets
     * no account and no mail: the same answer, status and work as any existing address. The account records no terms
     * acceptance and no error-report choice: whoever registers may not own the mailbox, so both come with the activation
     * ({@link Consent}).
     * @return the setup token, or null with mail, when the address was taken or when it is banned
     */
    public String register(String email, String name) {
        if (requireEmailVerification && !mail.enabled())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, EMAIL_NOT_CONFIGURED);
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        // A hash of a discarded random secret: no sign-in can match it, and every path pays for one hash.
        String unusable = passwords.encode(SecureTokens.generate());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Every address is looked up (one indexed statement), so a banned one costs what any other existing one does.
        boolean banned = blocklist.blocked(normalizedEmail);
        pending.expireEmail(normalizedEmail, now);
        // No lock: a mail task holds an existing account's row while it issues a link, and waiting for it would tell.
        if (!users.existsByEmailKey(EmailAddresses.key(normalizedEmail)) && !banned) {
            AppUser account = new AppUser(normalizedEmail, unusable, Text.clean(name, Text.Kind.NAME));
            account.awaitVerification(now.plusHours(24));
            users.saveAndFlush(account);
            // Expires with the account (both 24 hours from now); stored hashed and never logged.
            if (!mail.enabled()) return renew(new AccountToken(account, Purpose.VERIFY_EMAIL), now);
        }
        // Every address alike: the mail goes after commit on the mail executor, so the answer never waits for SMTP.
        if (mail.enabled()) events.publishEvent(new RegistrationMail(normalizedEmail));
        return null;
    }
    /** The mail a registration owes its address, sent once the registration has committed. */
    public record RegistrationMail(String email) {}
    /**
     * Decides here, off the request thread, which link the address gets (a verified owner a reset link, a pending one a
     * confirmation link, a banned address none) and whether the one-minute throttle holds it back; a failure is logged,
     * never answered. The blocklist is read again here, after the registration's commit, so a registration that
     * looked before a concurrent ban committed (and created an account for the address) sends nothing either.
     */
    @Async("mailExecutor")
    @TransactionalEventListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void mailRegistration(RegistrationMail registration) {
        deliver(() -> {
            if (blocklist.blocked(registration.email())) return null;
            return users.lockByEmailKey(EmailAddresses.key(registration.email()))
                    .filter(user -> !user.verificationExpired(LocalDateTime.now(ZoneOffset.UTC)))
                    .map(user -> issue(user, user.isEmailVerified() ? Purpose.RESET_PASSWORD : Purpose.VERIFY_EMAIL))
                    .orElse(null);
        });
    }
    /** Accepting the current version again keeps the first acceptance time. */
    public AppUser acceptTerms(UUID userId, String termsVersion) {
        if (!TERMS_VERSION.equals(termsVersion))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TERMS_NOT_ACCEPTED);
        AppUser account = users.lockById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, CurrentUser.SIGN_IN_REQUIRED));
        if (!TERMS_VERSION.equals(account.getAcceptedTermsVersion()))
            account.acceptTerms(TERMS_VERSION, LocalDateTime.now(ZoneOffset.UTC));
        return account;
    }
    @Async("mailExecutor")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void request(String email, Purpose purpose) {
        // Callers check that mail is configured; the mail is sent after the token is issued, off the request thread.
        // A banned address gets nothing (an account that already used an alias of it included).
        String address = email.trim();
        deliver(() -> {
            if (blocklist.blocked(address)) return null;
            return users.lockByEmailKey(EmailAddresses.key(address))
                    .filter(user -> !user.verificationExpired(LocalDateTime.now(ZoneOffset.UTC)))
                    .filter(user -> purpose != Purpose.VERIFY_EMAIL || !user.isEmailVerified())
                    .map(user -> issue(user, purpose)).orElse(null);
        });
    }
    /** A token committed and owed to its address: what the send needs and what a failed send puts back (null previous: none). */
    private record Issued(UUID userId, String email, Purpose purpose, String token, String tokenHash,
            AccountToken.Previous previous) {}
    /**
     * Issues under the account lock in a transaction of its own and commits, then sends with no transaction and no
     * lock: a slow SMTP server never holds the account row, so the owner's own writes never wait for it (and never
     * meet the production lock timeout). A failed send takes its token back ({@link #takeBack}). Logs class names
     * only, never addresses, tokens or SMTP replies.
     */
    private void deliver(Supplier<Issued> issuing) {
        Issued issued = transactions.execute(status -> issuing.get());
        if (issued == null) return;
        try { mail.send(issued.email(), issued.purpose(), issued.token()); }
        catch (MailException ex) {
            LoggerFactory.getLogger(AccountService.class).warn("Account email delivery failed ({}); check SMTP configuration and availability", failureChain(ex));
            try { transactions.executeWithoutResult(status -> takeBack(issued)); }
            catch (RuntimeException failure) {
                LoggerFactory.getLogger(AccountService.class).warn("Could not withdraw the link of a failed account email ({})", failureChain(failure));
            }
        }
    }
    /**
     * Under the account lock again: the link that never left is replaced by the one it renewed (still valid if it was)
     * or removed, so no throttle entry blocks the Resend. A token that changed meanwhile (consumed or renewed) stays.
     */
    private void takeBack(Issued issued) {
        users.lockById(issued.userId()).ifPresent(user -> tokens.findByUser_IdAndPurpose(user.getId(), issued.purpose())
                .filter(record -> record.getTokenHash().equals(issued.tokenHash()))
                .ifPresent(record -> {
                    if (issued.previous() == null) tokens.delete(record);
                    else record.restore(issued.previous());
                }));
    }
    /** Exception class names only: messages can carry addresses or server replies. */
    private static String failureChain(Throwable ex) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = ex; t != null && chain.length() < 300; t = t.getCause() == t ? null : t.getCause()) {
            if (chain.length() > 0) chain.append(" <- ");
            chain.append(t.getClass().getSimpleName());
        }
        return chain.toString();
    }
    /** A new token for the address unless one went out within the minute; the caller holds the account lock. */
    private Issued issue(AppUser user, Purpose purpose) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AccountToken record = tokens.findByUser_IdAndPurpose(user.getId(), purpose).orElse(null);
        if (record != null && record.getIssuedAt().isAfter(now.minusMinutes(1))) return null;
        AccountToken.Previous previous = record == null ? null : record.previous();
        if (record == null) record = new AccountToken(user, purpose);
        String token = renew(record, now);
        return new Issued(user.getId(), user.getEmail(), purpose, token, record.getTokenHash(), previous);
    }
    /** Gives the record a new token, stores only its hash and answers the raw token. */
    private String renew(AccountToken record, LocalDateTime now) {
        String token = SecureTokens.generate();
        record.renew(SecureTokens.hash(token), now);
        tokens.save(record);
        return token;
    }
    /**
     * What the mailbox owner agrees to when an account link activates a pending account: the current terms (required)
     * and the error-report choice (off unless chosen). Ignored for an account that is already active.
     */
    public record Consent(Boolean termsAccepted, String termsVersion, Boolean diagnosticsOptIn) {}
    public void reset(String token, String password, String displayName, Consent consent) {
        AccountToken record = validToken(token, Purpose.RESET_PASSWORD);
        if (!activateIfPending(record.getUser(), password, displayName, consent))
            record.getUser().changePassword(encode(password));
        tokens.deleteByUser_Id(record.getUser().getId());
        // Every device signs in again with the new password (a pending account has no sessions yet).
        sessions.endAll(record.getUser().getId());
        delays.reset(record.getUser().getEmail());
    }
    /**
     * A signed-in account changes its password: the current one must match (else 400, like account deletion), the new
     * one follows the reset rules and must differ. Under the account lock (the actor account comes first in every lock
     * order) the session version moves on and every stored session of the account ends at once (a sign-in racing
     * the change keeps the old version and ends on its next request), and every outstanding token goes, reset links
     * included; the caller signs the requesting session in again. With SMTP the owner gets a
     * notice after commit, off the request thread; a failed send is logged, never answered.
     * @return the account with its new session version
     */
    public AppUser changePassword(UUID userId, String currentPassword, String newPassword) {
        AppUser account = users.lockById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, CurrentUser.SIGN_IN_REQUIRED));
        if (!passwords.matches(currentPassword, account.getPasswordHash()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, WRONG_PASSWORD);
        if (newPassword.equals(currentPassword)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, SAME_PASSWORD);
        account.changePassword(encode(newPassword));
        tokens.deleteByUser_Id(userId);
        sessions.endAll(userId);
        if (mail.enabled()) events.publishEvent(new PasswordChangedMail(account.getEmail()));
        return account;
    }
    /** The notice a password change owes its owner, sent once the change has committed. */
    public record PasswordChangedMail(String email) {}
    @Async("mailExecutor")
    @TransactionalEventListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void mailPasswordChanged(PasswordChangedMail changed) {
        try { mail.sendPasswordChanged(changed.email()); }
        catch (MailException ex) {
            LoggerFactory.getLogger(AccountService.class).warn("Password change notice delivery failed ({}); check SMTP configuration and availability", failureChain(ex));
        }
    }
    public AppUser magicLogin(String token, String password, String displayName, Consent consent) {
        AccountToken record = validToken(token, Purpose.MAGIC_LOGIN);
        AppUser account = record.getUser();
        if (activateIfPending(account, password, displayName, consent)) tokens.deleteByUser_Id(account.getId());
        else tokens.delete(record);
        delays.reset(account.getEmail());
        return account;
    }
    public void verify(String token, String password, String displayName, Consent consent) {
        AccountToken record = validToken(token, Purpose.VERIFY_EMAIL);
        activateIfPending(record.getUser(), password, displayName, consent);
        tokens.deleteByUser_Id(record.getUser().getId());
        delays.reset(record.getUser().getEmail());
    }
    /**
     * The first password, the display name and the consent of the mailbox owner, in that order of checks: the 409
     * challenge, then 400 for the credentials, then 400 {@link #TERMS_NOT_ACCEPTED}; nothing changes before all pass.
     * Terms acceptance (server time) and the error-report choice replace whatever the row held, so an account that
     * registered while registration still recorded them is covered too.
     */
    private boolean activateIfPending(AppUser account, String password, String displayName, Consent consent) {
        if (account.isEmailVerified()) return false;
        if (password == null || displayName == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, ACTIVATION_REQUIRED);
        String name = Text.clean(displayName, Text.Kind.NAME);
        if (password.isBlank() || password.length() < 8 || name.isEmpty() || name.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check your new password and display name.");
        if (consent == null || !Boolean.TRUE.equals(consent.termsAccepted()) || !TERMS_VERSION.equals(consent.termsVersion()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TERMS_NOT_ACCEPTED);
        account.activate(encode(password), name);
        account.acceptTerms(TERMS_VERSION, LocalDateTime.now(ZoneOffset.UTC));
        account.chooseDiagnostics(Boolean.TRUE.equals(consent.diagnosticsOptIn()));
        return true;
    }
    private AccountToken validToken(String raw, Purpose purpose) {
        String hash = SecureTokens.hash(raw);
        var userId = tokens.findUserIdByTokenHash(hash).orElseThrow(this::invalid);
        // Serialize consumption, replacement and password changes on the account.
        AppUser account = users.lockById(userId).orElseThrow(this::invalid);
        AccountToken record = tokens.findByUser_IdAndPurpose(userId, purpose).orElseThrow(this::invalid);
        if (!record.getTokenHash().equals(hash) || record.getPurpose() != purpose
                || !record.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) throw invalid();
        // A banned address activates, verifies, resets and signs in by link nothing: an account created by a
        // registration that raced the ban, or one that already existed under an alias of the banned mailbox.
        if (blocklist.blocked(account.getEmail())) throw invalid();
        if (account.verificationExpired(LocalDateTime.now(ZoneOffset.UTC)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This unverified account has expired. Please register again.");
        return record;
    }
    private String encode(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes.");
        return passwords.encode(password);
    }
    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "This link is invalid or expired. Request a new email.");
    }
}
