package eu.glazelab.api.controller;

import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.Glaze;
import eu.glazelab.api.repository.GlazeRepository;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Every API failure uses the frontend's {"error": message} shape without implementation details. */
class ErrorResponseApiTest extends ApiTestBase {
    @MockitoSpyBean GlazeRepository glazes;

    private static void assertError(int status, ResponseSpec response) {
        Map<?, ?> body = response.expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(Map.class).returnResult().getResponseBody();
        assertNotNull(body);
        assertEquals(Set.of("error"), body.keySet());
        String message = assertInstanceOf(String.class, body.get("error"));
        assertFalse(message.isBlank());
        assertNotEquals(HttpStatus.valueOf(status).getReasonPhrase(), message);
        for (String detail : new String[] {"Exception", "java.", "jackson", "org.", "SQL", "confidential"})
            assertFalse(message.contains(detail), "Leaks '" + detail + "': " + body);
    }

    private static ResponseSpec json(RestTestClient client, String path, String json) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(json).exchange();
    }

    @Test void malformedJsonIsABadRequest() {
        RestTestClient ada = user("Ada");
        assertError(400, json(ada, "/api/glazes", "{\"name\":"));
        assertError(400, json(ada, "/api/glazes", "[]"));
    }

    @Test void invalidIdentifiersAreBadRequests() {
        RestTestClient ada = user("Ada");
        assertError(400, json(ada, "/api/stock", "{\"glazeId\":\"not-a-uuid\",\"quantityG\":1,\"ownerType\":\"personal\"}"));
        assertError(400, ada.get().uri("/api/images/not-a-uuid").exchange());
        assertError(400, ada.put().uri("/api/favorites/glazes/not-a-uuid").exchange());
        assertError(400, json(ada, "/api/combinations", "{\"title\":\"T\",\"steps\":[{\"type\":\"confidential-type\",\"label\":\"L\"}]}"));
        assertError(400, ada.get().uri("/api/admin/image-deletions?page=abc").exchange());
    }

    @Test void invalidEnumValuesHaveASafeMessage() {
        RestTestClient ada = user("Ada");
        UUID club = createClub(ada, unique("Bad role"));
        ResponseSpec role = ada.put().uri("/api/clubs/{id}/members/{userId}", club, me(ada).id())
                .body(Map.of("role", "confidential-role")).exchange();
        assertError(400, role);
        role.expectBody().jsonPath("$.error").isEqualTo("Please check the form fields and try again.");
    }

    @Test void validationNamesTheFirstInvalidField() {
        ResponseSpec response = user("Ada").post().uri("/api/glazes")
                .body(Map.of("manufacturerId", UUID.randomUUID(), "name", "", "code", "PC-20")).exchange();
        assertError(400, response);
        response.expectBody().jsonPath("$.error").isEqualTo("name: must not be blank");
    }

    @Test void unsupportedRoutesAndMethodsKeepTheErrorShape() {
        RestTestClient ada = user("Ada");
        assertError(404, ada.get().uri("/api/does-not-exist").exchange());
        ResponseSpec method = ada.delete().uri("/api/glazes").exchange();
        assertError(405, method);
        method.expectHeader().value("Allow", value -> assertTrue(value.contains("GET")));
        assertError(415, ada.post().uri("/api/glazes").contentType(MediaType.TEXT_PLAIN).body("name=x").exchange());
        assertError(406, ada.get().uri("/api/glazes").accept(MediaType.APPLICATION_XML).exchange());
    }

    @Test void unexpectedFailuresKeepDetailsInServerLogsOnly() {
        doThrow(new IllegalStateException("confidential implementation detail"))
                .when(glazes).findAllByOrderByManufacturer_NameAscNameAsc();
        try {
            assertError(500, guest().get().uri("/api/glazes").exchange());
        } finally {
            reset(glazes);
        }
    }

    @Test void missingUploadPartsAreBadRequests() {
        RestTestClient ada = user("Ada");
        UUID combo = addCombination(ada, unique("Missing file"), addGlaze(ada, unique("Errors"), "Blue", "PC-20"));
        assertError(400, upload(ada, combo, Map.of("kind", "TEST_TILE"), TestImages.png(24, 24)));
        assertError(400, ada.post().uri("/api/combinations/{id}/images?visibility=PUBLIC&kind=TEST_TILE", combo)
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=empty-form"))
                .body("--empty-form--\r\n").exchange());
    }

    private void failListingWith(Throwable failure, ResponseSpec[] answer) {
        doThrow(failure).when(glazes).findAllByOrderByManufacturer_NameAscNameAsc();
        try {
            answer[0] = guest().get().uri("/api/glazes").exchange();
        } finally {
            reset(glazes);
        }
    }

    @Test void uniqueViolationsAnswerThatTheRecordExists() {
        ResponseSpec[] answer = new ResponseSpec[1];
        failListingWith(new DataIntegrityViolationException("clash", new SQLException("unique", "23505")), answer);
        assertError(409, answer[0]);
        answer[0].expectBody().jsonPath("$.error").isEqualTo(ApiErrorHandler.DUPLICATE);
    }

    @Test void otherIntegrityFailuresAnswerAChangedMeanwhileConflict() {
        ResponseSpec[] answer = new ResponseSpec[1];
        failListingWith(new DataIntegrityViolationException("fk", new SQLException("foreign key", "23503")), answer);
        assertError(409, answer[0]);
        answer[0].expectBody().jsonPath("$.error").isEqualTo(ApiErrorHandler.CHANGED_MEANWHILE);
        failListingWith(new DataIntegrityViolationException("no state"), answer);
        answer[0].expectBody().jsonPath("$.error").isEqualTo(ApiErrorHandler.CHANGED_MEANWHILE);
    }

    @Test void stockForAGlazeDeletedMeanwhileIsAChangedMeanwhileConflict() {
        RestTestClient ada = user("Ada");
        UUID id = addGlaze(ada, unique("Deleted"), "Blue", "PC-20");
        Glaze glaze = glazes.findById(id).orElseThrow();
        // The glaze disappears (own connection, committed) after the request has read it.
        doAnswer(call -> {
            jdbc.update("delete from glaze where id = ?", id);
            return Optional.of(glaze);
        }).when(glazes).findById(id);
        try {
            ResponseSpec response = ada.post().uri("/api/stock")
                    .body(Map.of("glazeId", id, "quantityG", 100, "ownerType", "personal")).exchange();
            assertError(409, response);
            response.expectBody().jsonPath("$.error").isEqualTo(ApiErrorHandler.CHANGED_MEANWHILE);
        } finally {
            reset(glazes);
        }
    }
}
