package eu.glazelab.api.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import eu.glazelab.api.support.ApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Operator log lines: startup summary, slow requests by route template, failed sign-ins, admin photo removal. */
@TestPropertySource(properties = { "glazelab.log.slow-request=0ms", "spring.datasource.url=jdbc:h2:mem:opslog;DB_CLOSE_DELAY=-1" })
class OperationsLogApiTest extends ApiTestBase {
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    @Autowired OperationsLog operations;

    @BeforeEach void attach() { captured.start(); root.addAppender(captured); }
    @AfterEach void detach() { root.detachAppender(captured); }

    private List<String> lines() { return List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList(); }

    @Test void startupSummaryNamesSettingsAndNoSecrets() {
        operations.started(null);
        assertThat(lines()).filteredOn(line -> line.startsWith("Glazelab API started")).singleElement().satisfies(line -> {
            assertThat(line).contains("profiles=", "storage=", "mail=", "email-verification=", "rate-limit=", "schema=", "heap-max=");
            assertThat(line).doesNotContain("password", "secret", "jdbc:");
        });
    }

    @Test void slowRequestsLogTheRouteTemplateOnceAWindow() {
        RestTestClient client = guest();
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 3; i++) client.get().uri("/api/glazes/" + id + "/images?secret=canary").exchange();
        assertThat(lines()).filteredOn(line -> line.startsWith("Slow request: GET /api/glazes/{id}/images")).hasSize(1)
                .allSatisfy(line -> assertThat(line).doesNotContain(id.toString(), "canary"));
    }

    @Test void failedSignInsAreCountedPerClientWithoutTheEmail() {
        String email = email("Guesser");
        RestTestClient client = guest();
        for (int i = 0; i < 6; i++) login(client, email, "wrong-password-" + i).expectStatus().isUnauthorized();
        assertThat(lines()).filteredOn(line -> line.startsWith("Sign-in failures:")).singleElement()
                .satisfies(line -> assertThat(line).contains("failed 5 sign-ins").doesNotContain(email, "wrong-password")
                        .doesNotContain("127.0.0.1", "0:0:0:0", "::1", "localhost")
                        .matches("Sign-in failures: client [0-9a-f]{8} has failed 5 sign-ins within .*"));
    }

    @Test void adminRemovalOfSomeonesPhotoLogsIdsOnly() {
        RestTestClient author = signUp(email("Author"));
        UUID combination = addCombination(author, "Canary title", addGlaze(author, "Maker", "Canary glaze " + shortId(), "C-" + shortId()));
        UUID photo = uploadTile(author, combination, "PUBLIC", null);
        RestTestClient admin = siteAdmin();
        admin.delete().uri("/api/images/" + photo).exchange().expectStatus().isNoContent();
        assertThat(lines()).filteredOn(line -> line.startsWith("Site admin ") && line.contains("removed photo " + photo)).hasSize(1)
                .allSatisfy(line -> assertThat(line).doesNotContain("Canary"));
    }

    @Test void adminTypeChangeOfSomeonesPhotoLogsIdsOnlyAndNothingForTheUploader() {
        RestTestClient author = signUp(email("Retyped"));
        UUID combination = addCombination(author, "Canary title", addGlaze(author, "Maker", "Canary glaze " + shortId(), "C-" + shortId()));
        UUID photo = uploadTile(author, combination, "PUBLIC", null);
        author.put().uri("/api/images/{id}/kind", photo).body(java.util.Map.of("kind", "FINISHED")).exchange().expectStatus().isOk();
        RestTestClient admin = siteAdmin();
        admin.put().uri("/api/images/{id}/kind", photo).body(java.util.Map.of("kind", "FINISHED")).exchange().expectStatus().isOk();
        assertThat(lines()).filteredOn(line -> line.contains("type of photo " + photo)).isEmpty();
        admin.put().uri("/api/images/{id}/kind", photo).body(java.util.Map.of("kind", "TEST_TILE")).exchange().expectStatus().isOk();
        assertThat(lines()).filteredOn(line -> line.contains("type of photo " + photo)).singleElement()
                .satisfies(line -> assertThat(line).startsWith("Site admin ").doesNotContain("Canary", "TEST_TILE", "FINISHED"));
    }

    @Test void adminFocusChangeOfSomeonesPhotoLogsIdsOnlyAndNothingForTheUploader() {
        RestTestClient author = signUp(email("Refocused"));
        UUID combination = addCombination(author, "Canary title", addGlaze(author, "Maker", "Canary glaze " + shortId(), "C-" + shortId()));
        UUID photo = uploadTile(author, combination, "PUBLIC", null);
        author.put().uri("/api/images/{id}/focus", photo).body(java.util.Map.of("focusX", 13, "focusY", 87)).exchange().expectStatus().isOk();
        RestTestClient admin = siteAdmin();
        admin.put().uri("/api/images/{id}/focus", photo).body(java.util.Map.of("focusX", 13, "focusY", 87)).exchange().expectStatus().isOk();
        assertThat(lines()).filteredOn(line -> line.contains("focus of photo " + photo)).isEmpty();
        admin.put().uri("/api/images/{id}/focus", photo).body(java.util.Map.of("focusX", 31, "focusY", 78)).exchange().expectStatus().isOk();
        assertThat(lines()).filteredOn(line -> line.contains("focus of photo " + photo)).singleElement()
                .isEqualTo("Site admin " + me(admin).id() + " changed the focus of photo " + photo);
    }
}
