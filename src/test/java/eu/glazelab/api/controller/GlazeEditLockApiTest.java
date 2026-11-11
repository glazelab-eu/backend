package eu.glazelab.api.controller;

import eu.glazelab.api.model.Glaze;
import eu.glazelab.api.repository.GlazeRepository;
import eu.glazelab.api.service.GlazeService;
import eu.glazelab.api.support.ApiTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Its own context: a glaze step committed while a code change waits for the glaze lock is seen under it. */
class GlazeEditLockApiTest extends ApiTestBase {
    @MockitoSpyBean GlazeRepository glazes;
    @Autowired EntityManager entities;

    @Test void aGlazeStepAddedBeforeTheLockKeepsTheCode() {
        var ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Raced"), "Blue", "PC-20");
        UUID combination = created(ada.post().uri("/api/combinations").body(Map.of("title", "Firing",
                "steps", List.of(Map.of("type", "CUSTOM", "label", "Bisque")))).exchange());
        // Like a combination edit that locked the glaze, added its step and committed while this request waited.
        doAnswer(call -> {
            jdbc.update("INSERT INTO combination_step (id, combination_id, position, step_type, glaze_id, coats)"
                    + " VALUES (?, ?, 2, 'GLAZE', ?, 1)", UUID.randomUUID(), combination, glaze);
            return Optional.of(entities.find(Glaze.class, glaze, LockModeType.PESSIMISTIC_WRITE));
        }).when(glazes).lockById(glaze);
        try {
            assertError(user("Bob").patch().uri("/api/glazes/{id}", glaze).body(Map.of("code", "PC-21")).exchange(),
                    403, GlazeService.CODE_LOCKED);
        } finally { reset(glazes); }
        assertEquals("PC-20", jdbc.queryForObject("SELECT code FROM glaze WHERE id = ?", String.class, glaze));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM glaze_edit WHERE glaze_id = ?", Integer.class, glaze));
    }
}
