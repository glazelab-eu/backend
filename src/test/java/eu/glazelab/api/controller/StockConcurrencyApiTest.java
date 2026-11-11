package eu.glazelab.api.controller;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole {@link StockJarApiTest} on PostgreSQL: concurrent jar additions queue on the real owner row lock (account
 * row {@code FOR NO KEY UPDATE}, or the club row) and every one counts. Opt in against a disposable database only,
 * like {@code CombinationConcurrencyApiTest}.
 */
@EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_POSTGRES_URL", matches = ".+")
class StockConcurrencyApiTest extends StockJarApiTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GLAZELAB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_PASSWORD", ""));
        properties.add("glazelab.images.path", () -> System.getenv("GLAZELAB_TEST_POSTGRES_IMAGES"));
    }
}
