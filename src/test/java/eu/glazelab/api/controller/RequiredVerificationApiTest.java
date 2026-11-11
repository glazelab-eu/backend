package eu.glazelab.api.controller;

import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The production setting without SMTP: no mail-free signup, no account and no setup token. */
@TestPropertySource(properties = "glazelab.require-email-verification=true")
class RequiredVerificationApiTest extends ApiTestBase {
    @Test void registrationCannotBypassRequiredVerificationWhenMailIsUnavailable() {
        String email = email("NoMail");
        guest().post().uri("/api/auth/register").body(Map.of("email", email, "password", PASSWORD,
                "displayName", "No mail", "termsAccepted", true, "termsVersion", AccountService.TERMS_VERSION))
                .exchange().expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.error").isEqualTo("Email is not configured on this server.")
                .jsonPath("$.setupToken").doesNotExist();
        // Refused before anything is written: no account, so no token (tokens reference their account).
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email));
    }
}
