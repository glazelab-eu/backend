package eu.glazelab.api.controller;

import eu.glazelab.api.model.EmailAddresses;
import eu.glazelab.api.dto.AuthDtos.UserView;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.security.TermsFilter;
import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.support.ApiTestBase;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

class TermsApiTest extends ApiTestBase {
    @Autowired AppUserRepository users;

    /** Signs up and then sets the account's accepted version as a pre-V9 (null) or older account would have it. */
    private RestTestClient withTerms(String email, String version) {
        RestTestClient client = signUp(email);
        jdbc.update("UPDATE app_user SET accepted_terms_version = ?, terms_accepted_at = ? WHERE email = ?",
                version, version == null ? null : LocalDateTime.of(2026, 1, 1, 0, 0), email);
        return client;
    }

    private static Map<String, Object> current() {
        return Map.of("termsVersion", AccountService.TERMS_VERSION);
    }

    @ParameterizedTest @ValueSource(strings = {"", "2026-01-01"})
    void outdatedAccountsCannotWriteUntilTheyAcceptTheCurrentTerms(String old) {
        String version = old.isEmpty() ? null : old;
        String email = email("outdated");
        RestTestClient client = withTerms(email, version);
        assertEquals(version, me(client).acceptedTermsVersion());

        // Reads keep working; writes are refused with 428 and a translatable message.
        glazes(client);
        client.get().uri("/api/stock").exchange().expectStatus().isOk();
        client.post().uri("/api/clubs").body(Map.of("name", unique("Blocked"))).exchange()
                .expectStatus().isEqualTo(428)
                .expectBody().jsonPath("$.error").isEqualTo(TermsFilter.TERMS_REQUIRED);
        client.put().uri("/api/favorites/glazes/" + java.util.UUID.randomUUID()).exchange().expectStatus().isEqualTo(428);
        assertEquals(version, users.findByEmailKey(EmailAddresses.key(email)).orElseThrow().getAcceptedTermsVersion());

        // Only the current version is accepted; the time comes from the server.
        client.post().uri("/api/auth/terms").body(Map.of("termsVersion", "2000-01-01")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Please accept the current terms of service.");
        var before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        UserView accepted = body(client.post().uri("/api/auth/terms").body(current()).exchange().expectStatus().isOk(), UserView.class);
        assertEquals(AccountService.TERMS_VERSION, accepted.acceptedTermsVersion());
        assertEquals(accepted, me(client));
        var account = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow();
        assertEquals(AccountService.TERMS_VERSION, account.getAcceptedTermsVersion());
        assertTrue(account.getTermsAcceptedAt().isAfter(before));

        createClub(client, unique("Allowed"));
        // Accepting again keeps the first acceptance time.
        client.post().uri("/api/auth/terms").body(current()).exchange().expectStatus().isOk();
        assertEquals(account.getTermsAcceptedAt(), users.findByEmailKey(EmailAddresses.key(email)).orElseThrow().getTermsAcceptedAt());
    }

    @Test void currentAccountsWriteWithoutAcceptingAgain() {
        RestTestClient client = user("current");
        assertEquals(AccountService.TERMS_VERSION, me(client).acceptedTermsVersion());
        createClub(client, unique("Current"));
    }

    @Test void acceptingNeedsCsrfAndASignedInAccount() {
        String email = email("csrf");
        RestTestClient session = withTerms(email, null);
        session.post().uri("/api/auth/terms").header(CSRF_HEADER, "wrong").body(current()).exchange()
                .expectStatus().isForbidden();
        assertNull(users.findByEmailKey(EmailAddresses.key(email)).orElseThrow().getAcceptedTermsVersion());
        guest().post().uri("/api/auth/terms").body(current()).exchange().expectStatus().isUnauthorized();
        guestWithoutCsrf().post().uri("/api/auth/terms").body(current()).exchange().expectStatus().isForbidden();
    }

    @Test void outdatedAccountsCanStillSignOut() {
        RestTestClient client = withTerms(email("signout"), "2026-01-01");
        client.post().uri("/api/auth/logout").exchange().expectStatus().isOk();
        client.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
    }
}
