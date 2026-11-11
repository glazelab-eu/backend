package eu.glazelab.api.controller;

import eu.glazelab.api.model.Manufacturer;
import eu.glazelab.api.repository.ManufacturerRepository;
import eu.glazelab.api.support.ApiTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Its own context: the last-glaze check of a removal runs under the manufacturer lock, so a removal or creation
 * that committed while this one waited for that lock is seen.
 */
class GlazeRemovalLockApiTest extends ApiTestBase {
    @MockitoSpyBean ManufacturerRepository manufacturers;
    @Autowired EntityManager entities;

    private UUID manufacturerOf(UUID glaze) {
        return jdbc.queryForObject("SELECT manufacturer_id FROM glaze WHERE id = ?", UUID.class, glaze);
    }

    private int manufacturerRows(UUID manufacturer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM manufacturer WHERE id = ?", Integer.class, manufacturer);
    }

    @Test void theSecondOfTwoConcurrentLastRemovalsDeletesTheManufacturer() {
        var ada = user("Ada");
        String maker = unique("Pair");
        UUID first = addGlaze(ada, maker, "Blue", "PC-20");
        UUID second = addGlaze(ada, maker, "Green", "PC-21");
        UUID manufacturer = manufacturerOf(first);
        assertEquals(manufacturer, manufacturerOf(second));
        // Like the removal of the other glaze that held the manufacturer lock and committed while this one waited.
        doAnswer(call -> {
            jdbc.update("DELETE FROM glaze WHERE id = ?", second);
            return Optional.of(entities.find(Manufacturer.class, manufacturer, LockModeType.PESSIMISTIC_WRITE));
        }).when(manufacturers).lockById(manufacturer);
        try {
            siteAdmin().delete().uri("/api/glazes/{id}", first).exchange().expectStatus().isNoContent();
        } finally { reset(manufacturers); }
        assertEquals(0, manufacturerRows(manufacturer));
    }

    @Test void aGlazeAddedWhileTheRemovalWaitedKeepsTheManufacturer() {
        var ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Kept"), "Blue", "PC-20");
        UUID late = addGlaze(ada, unique("Elsewhere"), "Green", "PC-30");
        UUID manufacturer = manufacturerOf(glaze);
        // Like a creation under this manufacturer that committed while the removal waited for the lock.
        doAnswer(call -> {
            jdbc.update("UPDATE glaze SET manufacturer_id = ? WHERE id = ?", manufacturer, late);
            return Optional.of(entities.find(Manufacturer.class, manufacturer, LockModeType.PESSIMISTIC_WRITE));
        }).when(manufacturers).lockById(manufacturer);
        try {
            siteAdmin().delete().uri("/api/glazes/{id}", glaze).exchange().expectStatus().isNoContent();
        } finally { reset(manufacturers); }
        assertEquals(1, manufacturerRows(manufacturer));
    }
}
