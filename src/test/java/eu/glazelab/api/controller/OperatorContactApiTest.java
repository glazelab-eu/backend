package eu.glazelab.api.controller;

import eu.glazelab.api.dto.AuthDtos.OperatorContactView;
import eu.glazelab.api.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** The operator address leaves the server only through its own endpoint, on request. */
@TestPropertySource(properties = "glazelab.operator-email= operator@example.test ")
class OperatorContactApiTest extends ApiTestBase {
    @Test void answersTheTrimmedAddressToGuestsAndMembersWithoutCaching() {
        for (var client : new RestTestClient[] {guest(), user("Contact")}) {
            var response = client.get().uri("/api/auth/operator-contact").exchange().expectStatus().isOk()
                    .expectHeader().cacheControl(CacheControl.noStore());
            assertEquals("operator@example.test", body(response, OperatorContactView.class).email());
        }
    }

    @Test void theAuthOptionsNeverCarryTheAddress() {
        String json = guest().get().uri("/api/auth/options").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertNotNull(json);
        assertFalse(json.contains("operator"), json);
        assertFalse(json.contains("@"), json);
        guest().get().uri("/api/auth/options").exchange().expectBody().jsonPath("$.operatorEmail").doesNotExist();
    }
}
