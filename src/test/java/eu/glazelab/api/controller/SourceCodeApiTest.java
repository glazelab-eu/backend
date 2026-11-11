package eu.glazelab.api.controller;

import eu.glazelab.api.dto.AuthDtos.AuthOptionsView;
import eu.glazelab.api.dto.CommonDtos.HealthView;
import eu.glazelab.api.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** The AGPL source offer: the configured source URL rides on the public sign-in options, the build on the health probe. */
@TestPropertySource(properties = "glazelab.source-url= https://code.example.test/glazelab ")
class SourceCodeApiTest extends ApiTestBase {
    @Test void theOptionsNameTheTrimmedSourceUrlToGuestsAndMembers() {
        for (var client : new RestTestClient[] {guest(), user("Source")}) {
            var options = body(client.get().uri("/api/auth/options").exchange().expectStatus().isOk(), AuthOptionsView.class);
            assertEquals("https://code.example.test/glazelab", options.sourceUrl());
        }
    }

    /** Maven writes build-info.properties before the tests, so the label is the project version unless a build label was given. */
    @Test void theHealthProbeNamesTheBuildAndStaysSmall() {
        var response = guest().get().uri("/api/health").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        String json = response.expectBody(String.class).returnResult().getResponseBody();
        assertNotNull(json);
        // Below Nginx's 1 KiB gzip minimum, so the probe stays uncompressed.
        assertTrue(json.length() < 200, json);
        var health = body(guest().get().uri("/api/health").exchange().expectStatus().isOk(), HealthView.class);
        assertTrue(health.ok());
        assertFalse(health.build().isBlank());
        assertNotEquals("unknown", health.build());
    }
}
