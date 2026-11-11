package eu.glazelab.api.controller;

import eu.glazelab.api.service.StockService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** The stock part of a new glaze fails after the glaze was inserted: nothing of the request may remain. */
class GlazeStockRollbackApiTest extends ApiTestBase {
    @MockitoSpyBean StockService stockService;

    @Test void aFailingStockSaveRollsBackTheGlazeAndItsNewManufacturer() {
        RestTestClient ada = user("Ada");
        String brand = unique("Rollback");
        Map<String, Object> body = Map.of("newManufacturer", Map.of("name", brand, "shortId", shortId()),
                "name", "Blue", "code", "RB1", "stock", Map.of("quantityG", 300));
        doThrow(new IllegalStateException("stock failed")).when(stockService).save(any(), any(), anyInt(), any());
        try {
            ada.post().uri("/api/glazes").body(body).exchange().expectStatus().is5xxServerError();
        } finally {
            reset(stockService);
        }
        assertTrue(glazes(guest()).stream().noneMatch(g -> g.manufacturer().name().equals(brand)),
                "neither the glaze nor its new manufacturer survives");
        assertEquals(0, body(ada.get().uri("/api/stock").exchange().expectStatus().isOk(), java.util.List.class).size());
        // The same request succeeds once stock works again.
        ada.post().uri("/api/glazes").body(body).exchange().expectStatus().isOk();
        assertEquals(1, glazes(guest()).stream().filter(g -> g.manufacturer().name().equals(brand)).count());
    }
}
