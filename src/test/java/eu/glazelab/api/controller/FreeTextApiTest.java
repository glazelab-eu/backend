package eu.glazelab.api.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.Text;
import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BE-3, BE-10: control and format characters are refused in free text with a 400 before any lookup or write, the same
 * on H2 and PostgreSQL ({@code FreeTextPostgresApiTest} runs this class there, where NUL cannot be stored).
 */
class FreeTextApiTest extends ApiTestBase {
    private static final String NUL = "\u0000";
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach void attach() { captured.start(); root.addAppender(captured); }
    @AfterEach void detach() { root.detachAppender(captured); }

    private List<ILoggingEvent> errors() {
        return List.copyOf(captured.list).stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).toList();
    }

    /** Whether the database itself refuses NUL in a query (PostgreSQL), so the backstop answers 400. */
    protected boolean databaseRefusesNul() { return false; }

    private static ResponseSpec registration(RestTestClient client, String email, String displayName) {
        return client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("termsAccepted", true, "termsVersion", AccountService.TERMS_VERSION, "email", email,
                        "displayName", displayName))
                .exchange();
    }

    @Test void aRegistrationWithANulNameAnswersAlikeForNewAndExistingAddresses() {
        String taken = email("Taken");
        signUp(taken);
        String existing = statusAndBody(registration(guest(), taken, "Na" + NUL + "me"));
        String fresh = statusAndBody(registration(guest(), email("Fresh"), "Na" + NUL + "me"));
        assertEquals("400 {\"error\":\"" + Text.INVALID_NAME + "\"}", existing);
        assertEquals(existing, fresh);
        assertEquals(List.of(), errors());
    }

    @Test void invisibleAndBidiNamesAreRefused() {
        for (String name : new String[] {"​", "⁠⁠", "Ada‮", "⁦Ada⁩", "Ada\nLovelace"})
            assertError(registration(guest(), email("Bidi"), name), 400, Text.INVALID_NAME);
        String token = setupToken(registration(guest(), email("Active"), "Active"));
        assertError(activate(guest(), token, PASSWORD, "‮evil"), 400, Text.INVALID_NAME);
        activate(guest(), token, PASSWORD, " Active one ").expectStatus().isOk();
    }

    @Test void aSignInWithANulAddressIsAPlainFailureWithoutAStackTrace() {
        assertError(login(guest(), "a" + NUL + "@example.com", PASSWORD), 401, "Invalid email or password");
        assertEquals(List.of(), errors());
    }

    @Test void everyFreeTextFieldRefusesControlCharacters() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Text"), "Shino", "PC-60");
        Map<String, Object> step = Map.of("glazeId", glaze, "coats", 1);
        assertError(ada.post().uri("/api/combinations").body(Map.of("title", "Ti" + NUL + "tle", "steps", List.of(step))).exchange(),
                400, Text.INVALID_TEXT);
        assertError(ada.post().uri("/api/combinations").body(Map.of("title", "Title", "steps", List.of(step),
                "description", "a" + NUL)).exchange(), 400, Text.INVALID_TEXT);
        assertError(ada.post().uri("/api/combinations").body(Map.of("title", "Title", "steps",
                List.of(Map.of("type", "CUSTOM", "label", "Bisque" + NUL)))).exchange(), 400, Text.INVALID_TEXT);
        assertError(ada.post().uri("/api/combinations").body(Map.of("title", "Title", "steps", List.of(step),
                "tags", List.of("ok", "b" + NUL))).exchange(), 400, Text.INVALID_TEXT);
        assertError(ada.post().uri("/api/combinations").body(Map.of("title", "Title", "steps", List.of(step),
                "kiln", "K" + NUL)).exchange(), 400, Text.INVALID_TEXT);
        assertError(ada.post().uri("/api/clubs").body(Map.of("name", "Club" + NUL)).exchange(), 400, Text.INVALID_NAME);
        UUID club = createClub(ada, unique("Club"));
        assertError(ada.put().uri("/api/clubs/{id}", club).body(Map.of("name", "Club", "houseRules", "x" + NUL,
                "invitesEnabled", true)).exchange(), 400, Text.INVALID_TEXT);
        assertError(ada.patch().uri("/api/glazes/{id}", glaze).body(Map.of("notes", "n" + NUL)).exchange(), 400, Text.INVALID_TEXT);
        assertError(ada.patch().uri("/api/glazes/{id}/tags", glaze).body(Map.of("add", List.of("t" + NUL))).exchange(),
                400, Text.INVALID_TEXT);
        UUID combination = addCombination(ada, unique("Text"), glaze);
        assertError(upload(ada, combination, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE", "caption", "c" + NUL), PNG),
                400, Text.INVALID_TEXT);
        assertError(sendFeedback(ada, "Hello" + NUL, Map.of()), 400, Text.INVALID_TEXT);
        assertEquals(List.of(), errors());
    }

    @Test void multiLineTextKeepsLineBreaksAndTabs() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Lines"), "Shino", "PC-61");
        UUID id = created(ada.post().uri("/api/combinations").body(Map.of("title", " Lines ",
                "description", "One\n\tTwo", "steps", List.of(Map.of("glazeId", glaze, "coats", 1, "notes", "a\nb")))).exchange());
        var view = combination(ada, id).orElseThrow();
        assertEquals("Lines", view.title());
        assertEquals("One\n\tTwo", view.description());
        assertEquals("a\nb", view.steps().get(0).notes());
        sendFeedback(ada, "Line one\r\nLine two\tend", Map.of()).expectStatus().isOk();
    }

    @Test void aValueTheDatabaseRefusesIsInvalidInputNotAConflict() {
        // An admin search is not free text: it reaches the database, and PostgreSQL refuses NUL (SQLState 22021).
        var answer = siteAdmin().get().uri("/api/admin/users?q={q}", "a" + NUL).exchange();
        if (databaseRefusesNul()) assertError(answer, 400, ApiErrorHandler.INVALID_INPUT);
        else answer.expectStatus().isOk();
        assertEquals(List.of(), errors());
    }
}
