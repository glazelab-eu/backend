package eu.glazelab.api.controller;

import eu.glazelab.api.model.EmailAddresses;
import eu.glazelab.api.model.AccountToken;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.LoginSessions;
import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** `POST /api/auth/password/change` without SMTP (the mail notice is in `AccountEmailApiTest`, the budget in `RateLimitApiTest`). */
class PasswordChangeApiTest extends ApiTestBase {
    private static final String NEW_PASSWORD = "changedpass456";
    @MockitoSpyBean AppUserRepository users;

    private static ResponseSpec change(RestTestClient client, String current, String next) {
        return client.post().uri("/api/auth/password/change").body(Map.of("currentPassword", current, "newPassword", next)).exchange();
    }

    @Test void changesThePasswordKeepsThisSessionAndEndsEveryOtherOne() {
        String email = email("Changer");
        var other = signUp(email);
        var owner = guest();
        String previousSession = login(owner, email, PASSWORD).expectStatus().isOk()
                .returnResult().getResponseCookies().getFirst(LoginSessions.COOKIE).getValue();
        var id = me(owner).id();
        int version = users.findById(id).orElseThrow().getSessionVersion();
        String previousCsrf = csrf(owner);

        change(owner, PASSWORD, NEW_PASSWORD).expectStatus().isOk()
                // A new session ID and CSRF token, as at any sign-in.
                .expectCookie().value(LoginSessions.COOKIE, session -> assertNotEquals(previousSession, session));

        assertEquals(id, me(owner).id());
        assertEquals(version + 1, users.findById(id).orElseThrow().getSessionVersion());
        assertError(other.get().uri("/api/auth/me").exchange(), 401, CurrentUser.SIGN_IN_REQUIRED);
        login(guest(), email, PASSWORD).expectStatus().isUnauthorized();
        login(guest(), email, NEW_PASSWORD).expectStatus().isOk();
        // The old CSRF token is gone; the renewed session writes with a fresh one, as the client fetches after the change.
        owner.post().uri("/api/clubs").header(CSRF_HEADER, previousCsrf).body(Map.of("name", unique("Stale")))
                .exchange().expectStatus().isForbidden();
        createClub(owner, unique("After change"));
        me(owner);
    }

    /**
     * A request of the same session that loaded its (old-version) sign-in before the change but checks the session
     * version after it must not end the session the change has just renewed: it ends only its own, old token's row and
     * leaves the cookie alone, which the change's answer has replaced.
     */
    @Test void aRequestInFlightDuringTheChangeKeepsTheRenewedSession() throws Exception {
        String email = email("Race");
        var owner = signUp(email);
        UUID id = me(owner).id();
        var paused = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            if (id.equals(call.getArgument(0)) && armed.getAndSet(false)) {
                paused.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            // The same lookup without the spy's abstract findById.
            return users.findByEmailKey(EmailAddresses.key(email)).filter(account -> account.getId().equals(call.getArgument(0)));
        }).when(users).findById(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var late = executor.submit(() -> owner.get().uri("/api/auth/me").exchange().returnResult().getStatus().value());
            assertTrue(paused.await(10, TimeUnit.SECONDS));
            change(owner, PASSWORD, NEW_PASSWORD).expectStatus().isOk();
            release.countDown();
            late.get(15, TimeUnit.SECONDS);
        } finally { reset(users); }
        assertEquals(id, me(owner).id());
        createClub(owner, unique("After the race"));
    }

    @Test void aWrongCurrentPasswordChangesNothing() {
        String email = email("Wrong");
        var owner = signUp(email);
        var other = guest();
        login(other, email, PASSWORD).expectStatus().isOk();
        assertError(change(owner, "not-the-password", NEW_PASSWORD), 400, AccountService.WRONG_PASSWORD);
        // Even when the new password happens to equal the wrong guess, the answer is about the current password.
        assertError(change(owner, NEW_PASSWORD, NEW_PASSWORD), 400, AccountService.WRONG_PASSWORD);
        // Longer than bcrypt's 72 bytes: compared (and refused) like any wrong guess, never a 500.
        assertError(change(owner, "ü".repeat(128), NEW_PASSWORD), 400, AccountService.WRONG_PASSWORD);
        me(owner);
        me(other);
        login(guest(), email, PASSWORD).expectStatus().isOk();
        login(guest(), email, NEW_PASSWORD).expectStatus().isUnauthorized();
    }

    @Test void theNewPasswordFollowsTheResetRulesAndMustDiffer() {
        String email = email("Rules");
        var owner = signUp(email);
        assertError(change(owner, PASSWORD, PASSWORD), 400, AccountService.SAME_PASSWORD);
        change(owner, PASSWORD, "short").expectStatus().isBadRequest();
        change(owner, PASSWORD, "        ").expectStatus().isBadRequest();
        change(owner, PASSWORD, "x".repeat(129)).expectStatus().isBadRequest();
        // 128 characters but more than 72 UTF-8 bytes: bcrypt would cut it.
        assertError(change(owner, PASSWORD, "ü".repeat(40)), 400, "Password must be at most 72 UTF-8 bytes.");
        owner.post().uri("/api/auth/password/change").body(Map.of("newPassword", NEW_PASSWORD)).exchange().expectStatus().isBadRequest();
        me(owner);
        login(guest(), email, PASSWORD).expectStatus().isOk();
    }

    @Test void needsASignedInSessionAndCsrf() {
        String email = email("Csrf");
        var owner = signUp(email);
        assertError(change(guest(), PASSWORD, NEW_PASSWORD), 401, CurrentUser.SIGN_IN_REQUIRED);
        var noCsrf = guestWithoutCsrf();
        login(noCsrf, csrf(noCsrf), email, PASSWORD).expectStatus().isOk();
        change(noCsrf, PASSWORD, NEW_PASSWORD).expectStatus().isForbidden();
        noCsrf.post().uri("/api/auth/password/change").header(CSRF_HEADER, "wrong")
                .body(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)).exchange().expectStatus().isForbidden();
        owner.get().uri("/api/auth/password/change").exchange().expectStatus().isEqualTo(405);
        login(guest(), email, PASSWORD).expectStatus().isOk();
    }

    @Test void removesOutstandingResetLinksAndOtherTokens() {
        String email = email("Tokens");
        var owner = signUp(email);
        UUID id = me(owner).id();
        String reset = accountToken(id, AccountToken.Purpose.RESET_PASSWORD);
        accountToken(id, AccountToken.Purpose.MAGIC_LOGIN);
        change(owner, PASSWORD, NEW_PASSWORD).expectStatus().isOk();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_token WHERE user_id = ?", Integer.class, id));
        guest().post().uri("/api/auth/password/reset").body(Map.of("token", reset, "password", "attackerpass1"))
                .exchange().expectStatus().isBadRequest();
        login(guest(), email, NEW_PASSWORD).expectStatus().isOk();
    }

    @Test void worksWhileNewerTermsWaitLikeAccountDeletion() {
        String email = email("Terms");
        var owner = signUp(email);
        jdbc.update("UPDATE app_user SET accepted_terms_version = NULL, terms_accepted_at = NULL WHERE email = ?", email);
        owner.post().uri("/api/clubs").body(Map.of("name", unique("Blocked"))).exchange().expectStatus().isEqualTo(428);
        change(owner, PASSWORD, NEW_PASSWORD).expectStatus().isOk();
        login(guest(), email, NEW_PASSWORD).expectStatus().isOk();
    }
}
