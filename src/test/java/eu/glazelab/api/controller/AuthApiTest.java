package eu.glazelab.api.controller;

import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.EmailAddresses;
import eu.glazelab.api.dto.AuthDtos.AuthOptionsView;
import eu.glazelab.api.dto.AuthDtos.RegistrationView;
import eu.glazelab.api.dto.AuthDtos.UserView;
import eu.glazelab.api.security.SecureTokens;
import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.support.ApiTestBase;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

class AuthApiTest extends ApiTestBase {
    @org.springframework.beans.factory.annotation.Autowired
    eu.glazelab.api.repository.AppUserRepository users;
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.security.crypto.password.PasswordEncoder passwords;

    /**
     * One account per address whatever its letter case or folded spelling (a long s, a dotless i: the lookup key, V37):
     * registration treats another case as the address in use, every writing finds the account and signs in, and the
     * database refuses a second row.
     */
    @Test void everyWritingOfAnAddressIsTheOneAccount() {
        String email = email("Sis"), local = email.substring(0, email.indexOf('@')), domain = email.substring(email.indexOf('@'));
        signUp(email);
        String key = EmailAddresses.key(email);
        assertNull(setupToken(register(guest(), email.toUpperCase(Locale.ROOT), "Other")));
        for (String variant : new String[] {email.toUpperCase(Locale.ROOT), local.replace('s', 'ſ').replace('i', 'ı') + domain, " " + email + " "}) {
            assertTrue(users.existsByEmailKey(EmailAddresses.key(variant)), variant);
            login(guest(), variant, PASSWORD).expectStatus().isOk();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email_key = ?", Integer.class, key));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO app_user (id, email, email_key,"
                + " password_hash, display_name) VALUES (?, ?, ?, 'hash', 'Twin')", UUID.randomUUID(), local.replace('s', 'ſ') + domain, key));
        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(new AppUser(email.toUpperCase(Locale.ROOT), "hash", "Twin")));
    }

    @Test void registrationRecordsNoConsentAndActivationRecordsTheMailboxOwnersWithServerTime() {
        RestTestClient client = guest();
        String email = email("terms");
        // An older client's consent at registration is ignored: whoever registers may not own the mailbox.
        String token = setupToken(client.post().uri("/api/auth/register").body(Map.of("email", email, "displayName", "Terms",
                "termsAccepted", true, "termsVersion", AccountService.TERMS_VERSION, "diagnosticsOptIn", true)).exchange());
        var pending = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow();
        assertNull(pending.getAcceptedTermsVersion());
        assertNull(pending.getTermsAcceptedAt());
        assertFalse(pending.isDiagnosticsOptIn());

        // Activation needs the current terms, after the challenge and the credential checks; a refusal changes nothing.
        var input = new java.util.HashMap<String, Object>(Map.of("token", token, "password", PASSWORD, "displayName", "Owner"));
        assertError(client.post().uri("/api/auth/verification/confirm").body(input).exchange(), 400, AccountService.TERMS_NOT_ACCEPTED);
        input.put("termsAccepted", false);
        input.put("termsVersion", AccountService.TERMS_VERSION);
        assertError(client.post().uri("/api/auth/verification/confirm").body(input).exchange(), 400, AccountService.TERMS_NOT_ACCEPTED);
        input.put("termsAccepted", true);
        input.put("termsVersion", "outdated");
        assertError(client.post().uri("/api/auth/verification/confirm").body(input).exchange(), 400, AccountService.TERMS_NOT_ACCEPTED);
        input.remove("termsVersion");
        assertError(client.post().uri("/api/auth/verification/confirm").body(input).exchange(), 400, AccountService.TERMS_NOT_ACCEPTED);
        assertFalse(users.findById(pending.getId()).orElseThrow().isEmailVerified());
        login(guest(), email, PASSWORD).expectStatus().isUnauthorized();

        String current = body(client.get().uri("/api/auth/options").exchange().expectStatus().isOk(),
                AuthOptionsView.class).termsVersion();
        assertEquals(AccountService.TERMS_VERSION, current);
        input.put("termsVersion", current);
        input.put("diagnosticsOptIn", true);
        // Acceptance time is generated on the server, never trusted from the request.
        input.put("termsAcceptedAt", "2000-01-01T00:00:00");
        var before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        client.post().uri("/api/auth/verification/confirm").body(input).exchange().expectStatus().isOk();
        var account = users.findById(pending.getId()).orElseThrow();
        assertTrue(account.isEmailVerified());
        assertEquals(current, account.getAcceptedTermsVersion());
        assertTrue(account.getTermsAcceptedAt().isAfter(before));
        assertFalse(account.getTermsAcceptedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
        assertTrue(account.isDiagnosticsOptIn());
    }

    @Test void theErrorReportChoiceIsOffUnlessTheActivationTurnsItOn() {
        String email = email("reportsOff");
        String token = setupToken(register(guest(), email, "Off"));
        var input = new java.util.HashMap<String, Object>(activation(token, PASSWORD, "Off"));
        input.remove("diagnosticsOptIn");
        guest().post().uri("/api/auth/verification/confirm").body(input).exchange().expectStatus().isOk();
        var account = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow();
        assertFalse(account.isDiagnosticsOptIn());
        assertEquals(AccountService.TERMS_VERSION, account.getAcceptedTermsVersion());
    }

    @Test void frontendShowsTheCurrentTermsVersion() throws Exception {
        // Absent in the backend-only Docker build context; a mismatch would block every registration.
        var frontend = java.nio.file.Path.of("../frontend/src/lib/api/terms.ts");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(frontend), "frontend is not available");
        assertTrue(java.nio.file.Files.readString(frontend).contains(
                "termsVersion = '" + eu.glazelab.api.service.AccountService.TERMS_VERSION + "';"),
                "Bump frontend/src/lib/api/terms.ts together with AccountService.TERMS_VERSION");
    }

    // Written directly by the security handlers, which must declare UTF-8 rather than the servlet default.
    private static final MediaType SECURITY_JSON = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    @Test void unauthenticatedRequestsGetJsonWithoutCreatingSessions() {
        RestTestClient guest = guest();
        guest.get().uri("/api/stock").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(SECURITY_JSON)
                .expectCookie().doesNotExist("JSESSIONID")
                .expectCookie().doesNotExist(eu.glazelab.api.security.LoginSessions.COOKIE)
                .expectBody().jsonPath("$.error").isEqualTo("Please sign in.");
        guest.get().uri("/api/glazes").exchange().expectStatus().isOk();
        guest.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
    }

    @Test void springLoginPageIsNeverGenerated() {
        guest().get().uri("/login").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(SECURITY_JSON);
    }

    @Test void writesRequireCsrfTokenFromJsonEndpoint() {
        guestWithoutCsrf().post().uri("/api/auth/register").body(Map.of()).exchange().expectStatus().isForbidden();
        guest().get().uri("/api/auth/csrf").exchange().expectCookie().httpOnly("XSRF-TOKEN", true);
        guest().post().uri("/api/glazes").body(Map.of("manufacturerId", UUID.randomUUID(), "name", "Permission", "code", "PC-1")).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test void loginAndLogoutRotateCsrfTokens() {
        RestTestClient client = guest();
        String email = email("security");
        activate(client, setupToken(register(client, email, "Security")), PASSWORD, "Security").expectStatus().isOk();
        login(client, email, "wrong").expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("Invalid email or password");

        String beforeLogin = csrf(client);
        login(client, email, PASSWORD).expectStatus().isOk();
        client.get().uri("/api/auth/me").exchange().expectStatus().isOk();
        client.post().uri("/api/auth/logout").header(CSRF_HEADER, beforeLogin).exchange().expectStatus().isForbidden();
        String fresh = csrf(client);
        assertNotEquals(beforeLogin, fresh);
        client.post().uri("/api/auth/logout").header(CSRF_HEADER, fresh).exchange().expectStatus().isOk();
        client.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
    }

    @Test void operatorContactIsNotFoundWhileNoAddressIsConfigured() {
        assertError(guest().get().uri("/api/auth/operator-contact").exchange(), 404, AuthController.CONTACT_NOT_CONFIGURED);
        assertError(user("NoContact").get().uri("/api/auth/operator-contact").exchange(), 404, AuthController.CONTACT_NOT_CONFIGURED);
    }

    /** No default source URL: a fork never links to another project's code, and the app hides its link. */
    @Test void theOptionsNameNoSourceUrlWhileNoneIsConfigured() {
        assertNull(body(guest().get().uri("/api/auth/options").exchange().expectStatus().isOk(), AuthOptionsView.class).sourceUrl());
    }

    @Test void emailFeaturesAreUnavailableWithoutSmtp() {
        RestTestClient guest = guest();
        assertFalse(body(guest.get().uri("/api/auth/options").exchange(), AuthOptionsView.class).emailEnabled());
        guest.post().uri("/api/auth/password/request").body(Map.of("email", "any@example.test")).exchange()
                .expectStatus().isEqualTo(503);
        guest.post().uri("/api/auth/magic/request").body(Map.of("email", "any@example.test")).exchange()
                .expectStatus().isEqualTo(503);
    }

    @Test void emailsAreCaseInsensitiveAndUnique() {
        String email = email("Mixed");
        RestTestClient first = guest();
        String token = setupToken(register(first, email.toUpperCase(), " Mixed "));
        // The address is taken: the second registration gets no token, whatever the letter case.
        assertNull(setupToken(register(guest(), email, "Again")));
        activate(first, token, PASSWORD, " Mixed ").expectStatus().isOk();
        login(first, email.toUpperCase(), PASSWORD).expectStatus().isOk();
        UserView me = me(first);
        assertEquals(new UserView(me.id(), email, "Mixed", false, AccountService.TERMS_VERSION, null, false, 0, true, true), me);
        // Only public account fields are exposed; the password hash never leaves the server.
        first.get().uri("/api/auth/me").exchange().expectBody()
                .jsonPath("$.length()").isEqualTo(10)
                .jsonPath("$.passwordHash").doesNotExist();
    }

    @Test void registrationValidatesInput() {
        RestTestClient guest = guest();
        register(guest, "not-an-email", "Bad").expectStatus().isBadRequest();
        register(guest, email("blank"), " ").expectStatus().isBadRequest();
        register(guest, email("long"), "n".repeat(101)).expectStatus().isBadRequest();
        // BCrypt only uses 72 bytes, so longer first passwords are rejected rather than silently truncated.
        String email = email("bytes");
        String token = setupToken(register(guest, email, "Bytes"));
        activate(guest, token, "€".repeat(30), "Bytes").expectStatus().isBadRequest();
        activate(guest, token, "short", "Bytes").expectStatus().isBadRequest();
        activate(guest, token, PASSWORD, "Bytes").expectStatus().isOk();
    }

    @Test void mailFreeRegistrationStoresNoUsablePasswordAndAnswersASetupTokenForANewAddressOnly() {
        RestTestClient owner = guest();
        String email = email("Setup");
        // An older client still sends a password: it is ignored, never validated, stored or usable.
        var registration = body(owner.post().uri("/api/auth/register").body(Map.of("termsAccepted", true,
                "termsVersion", AccountService.TERMS_VERSION, "email", email, "displayName", "Setup", "password", "x"))
                .exchange().expectStatus().isOk(), RegistrationView.class);
        assertTrue(registration.verificationRequired());
        String token = registration.setupToken();
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
        var account = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow();
        assertFalse(account.isEmailVerified());
        var deadline = account.getVerificationExpiresAt();
        assertTrue(deadline.isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(23)));
        for (String guess : new String[] {"x", "", PASSWORD, token})
            assertFalse(passwords.matches(guess, account.getPasswordHash()), guess);
        // Stored hashed like every account token, expiring with the pending deadline.
        var stored = jdbc.queryForMap("SELECT purpose, token_hash, expires_at FROM account_token WHERE user_id = ?", account.getId());
        assertEquals("VERIFY_EMAIL", stored.get("purpose"));
        assertEquals(SecureTokens.hash(token), stored.get("token_hash"));
        assertEquals(deadline, ((java.sql.Timestamp) stored.get("expires_at")).toLocalDateTime());
        for (String guess : new String[] {"x", "", PASSWORD}) login(guest(), email, guess).expectStatus().isUnauthorized();

        // Registering the address again (an attacker in local dev, or a retry) gets no token and changes nothing.
        String again = guest().post().uri("/api/auth/register").body(Map.of("termsAccepted", true,
                "termsVersion", AccountService.TERMS_VERSION, "email", email.toUpperCase(), "displayName", "Attacker",
                "password", "attacker-123")).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertEquals("{\"verificationRequired\":true,\"setupToken\":null}", again);
        assertEquals(stored, jdbc.queryForMap("SELECT purpose, token_hash, expires_at FROM account_token WHERE user_id = ?", account.getId()));
        assertEquals("Setup", users.findById(account.getId()).orElseThrow().getDisplayName());
        login(guest(), email, "attacker-123").expectStatus().isUnauthorized();

        // The token answers the activation challenge once; activation bumps the session version and clears tokens.
        owner.post().uri("/api/auth/verification/confirm").body(Map.of("token", token)).exchange()
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo(AccountService.ACTIVATION_REQUIRED);
        activate(owner, token, "first-password-1", "Setup owner").expectStatus().isOk();
        account = users.findById(account.getId()).orElseThrow();
        assertTrue(account.isEmailVerified());
        assertNull(account.getVerificationExpiresAt());
        assertEquals("Setup owner", account.getDisplayName());
        assertEquals(1, account.getSessionVersion());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_token WHERE user_id = ?", Integer.class, account.getId()));
        activate(owner, token, "second-password-2", "Replay").expectStatus().isBadRequest();
        login(owner, email, "second-password-2").expectStatus().isUnauthorized();
        login(owner, email, "first-password-1").expectStatus().isOk();
        assertNull(setupToken(register(guest(), email, "Verified")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_token WHERE user_id = ?", Integer.class, account.getId()));
    }

    @Test void aMailFreeSetupTokenExpiresWithItsPendingAccount() {
        RestTestClient client = guest();
        String email = email("SetupExpiry");
        String token = setupToken(register(client, email, "Late"));
        UUID original = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow().getId();
        jdbc.update("UPDATE app_user SET verification_expires_at = ? WHERE id = ?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), original);
        assertError(activate(client, token, PASSWORD, "Late"), 400, "This unverified account has expired. Please register again.");
        // The expired registration releases its address: the next one is a new account with its own token.
        String replacement = setupToken(register(client, email, "Again"));
        assertNotNull(replacement);
        assertFalse(users.existsById(original));
        activate(client, token, PASSWORD, "Late").expectStatus().isBadRequest();
        activate(client, replacement, PASSWORD, "Again").expectStatus().isOk();
    }

    @Test void malformedAccountTokensAreRejected() {
        RestTestClient guest = guest();
        for (String path : new String[] {"/api/auth/verification/confirm", "/api/auth/magic/confirm"}) {
            guest.post().uri(path).body(Map.of("token", "short")).exchange().expectStatus().isBadRequest();
            guest.post().uri(path).body(Map.of("token", "A".repeat(43))).exchange().expectStatus().isBadRequest();
        }
        guest.post().uri("/api/auth/password/reset").body(Map.of("token", "A".repeat(43), "password", "newsecret123"))
                .exchange().expectStatus().isBadRequest();
    }
}
