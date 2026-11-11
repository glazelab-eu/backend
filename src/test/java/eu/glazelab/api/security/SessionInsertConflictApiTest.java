package eu.glazelab.api.security;

import eu.glazelab.api.model.LoginSession;
import eu.glazelab.api.repository.LoginSessionRepository;
import eu.glazelab.api.support.ApiTestBase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 34-3: a session insert that fails its foreign key because the account went between the password check and the
 * insert (an account deletion or a ban; the real race runs on PostgreSQL in {@code BanConcurrencyApiTest}). Its own
 * context injects the failure. A sign-in answers exactly like a wrong password, never a 500, and sets no session.
 */
class SessionInsertConflictApiTest extends ApiTestBase {
    @MockitoSpyBean LoginSessionRepository sessions;

    @AfterEach void restore() { reset(sessions); }

    private void accountGoneOnNextInsert() {
        doThrow(new DataIntegrityViolationException("Simulated foreign key failure: the account was removed"))
                .when(sessions).save(any(LoginSession.class));
    }

    private static boolean setsSession(EntityExchangeResult<String> result) {
        return result.getResponseHeaders().getOrEmpty("Set-Cookie").stream()
                .anyMatch(cookie -> cookie.startsWith(LoginSessions.COOKIE + "=") && !cookie.startsWith(LoginSessions.COOKIE + "=;"));
    }

    @Test void aSignInWhoseAccountWentAnswersLikeAWrongPassword() {
        String email = email("Gone");
        signUp(email);
        var wrong = login(guest(), email, "wrong-password-1").expectBody(String.class).returnResult();

        accountGoneOnNextInsert();
        var browser = guest();
        var raced = login(browser, email, PASSWORD).expectBody(String.class).returnResult();
        assertEquals(401, raced.getStatus().value());
        assertEquals(wrong.getResponseBody(), raced.getResponseBody());
        assertEquals(wrong.getResponseHeaders().getContentType(), raced.getResponseHeaders().getContentType());
        assertFalse(setsSession(raced), raced.getResponseHeaders().toString());
        browser.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
    }

    @Test void aPasswordChangeWhoseAccountWentAnswersSignInRequired() {
        String email = email("GoneChange");
        var owner = signUp(email);
        UUID id = me(owner).id();
        accountGoneOnNextInsert();
        var raced = owner.post().uri("/api/auth/password/change")
                .body(Map.of("currentPassword", PASSWORD, "newPassword", "another-secret-2")).exchange()
                .expectBody(String.class).returnResult();
        assertEquals(401, raced.getStatus().value());
        assertTrue(raced.getResponseBody().contains(CurrentUser.SIGN_IN_REQUIRED), raced.getResponseBody());
        assertFalse(setsSession(raced), raced.getResponseHeaders().toString());
        assertEquals(0, jdbc.queryForObject("select count(*) from login_session where user_id = ?", Integer.class, id));
    }
}
