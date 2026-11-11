package eu.glazelab.api.security;

import eu.glazelab.api.dto.AuthDtos.UserView;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionLookupApiTest extends ApiTestBase {
    @MockitoSpyBean AppUserRepository users;

    @Test void filtersAndControllerShareOneUserLookupPerRequest() {
        var browser = user("lookup");
        var id = me(browser).id();
        String token = csrf(browser);
        clearInvocations(users);
        browser.get().uri("/api/auth/me").exchange().expectStatus().isOk();
        verify(users, times(1)).findById(id);
        clearInvocations(users);
        browser.post().uri("/api/clubs").header(CSRF_HEADER, token)
                .body(Map.of("name", unique("One lookup"))).exchange().expectStatus().isOk();
        verify(users, times(1)).findById(id);
    }

    @Test void nextRequestSeesChangedTermsProfileAndVerification() {
        var browser = user("fresh");
        var id = me(browser).id();
        jdbc.update("UPDATE app_user SET display_name = ?, accepted_terms_version = ? WHERE id = ?", "Fresh name", "old", id);
        UserView changed = me(browser);
        assertEquals("Fresh name", changed.displayName());
        assertEquals("old", changed.acceptedTermsVersion());
        browser.post().uri("/api/clubs").body(Map.of("name", unique("Blocked"))).exchange()
                .expectStatus().isEqualTo(428);
        jdbc.update("UPDATE app_user SET site_admin = TRUE, accepted_terms_version = ? WHERE id = ?",
                AccountService.TERMS_VERSION, id);
        assertTrue(me(browser).siteAdmin());
        createClub(browser, unique("Fresh terms"));
        jdbc.update("UPDATE app_user SET email_verified = FALSE WHERE id = ?", id);
        browser.get().uri("/api/stock").exchange().expectStatus().isUnauthorized();
        browser.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
    }

    @Test void changedSessionVersionEndsTheSessionBeforeCheckingTerms() {
        var browser = user("ended");
        var id = me(browser).id();
        String token = csrf(browser);
        jdbc.update("UPDATE app_user SET session_version = session_version + 1, accepted_terms_version = ? WHERE id = ?", "old", id);
        browser.post().uri("/api/clubs").header(CSRF_HEADER, token)
                .body(Map.of("name", unique("Ended"))).exchange().expectStatus().isUnauthorized();
        browser.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
    }
}
