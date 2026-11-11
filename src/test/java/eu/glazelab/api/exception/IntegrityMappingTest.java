package eu.glazelab.api.exception;

import eu.glazelab.api.dto.CommonDtos.ErrorView;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/** BE-A1: integrity failures answer by SQLState; only what a retry can fix reads "changed meanwhile". */
class IntegrityMappingTest {
    private static ResponseEntity<ErrorView> answer(String state) {
        var wrapped = new RuntimeException("statement", new SQLException("detail", state));
        return new ApiErrorHandler().conflict(new DataIntegrityViolationException("refused", wrapped));
    }

    private static void assertAnswer(String state, int status, String message) {
        var answer = answer(state);
        assertEquals(status, answer.getStatusCode().value(), state);
        assertEquals(message, answer.getBody().error(), state);
    }

    @Test void eachClassOfFailureHasItsOwnAnswer() {
        assertAnswer("23505", 409, ApiErrorHandler.DUPLICATE);
        assertAnswer("23503", 409, ApiErrorHandler.CHANGED_MEANWHILE);
        assertAnswer(null, 409, ApiErrorHandler.CHANGED_MEANWHILE);
        // Values the database refuses: NUL in text (PostgreSQL 22021), too long (22001), out of range, a check.
        for (String state : new String[] {"22021", "22001", "22003", "23514", "23513"})
            assertAnswer(state, 400, ApiErrorHandler.INVALID_INPUT);
    }
}
