package eu.glazelab.api.service;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link AccountMailLockApiTest} on PostgreSQL under the production lock timeout (10 s, application-prod.properties): a
 * send that takes 15 seconds would make the owner's write fail there if the mail task held the account row. Opt in
 * against a disposable database only, like {@code CombinationConcurrencyApiTest}.
 */
@EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_POSTGRES_URL", matches = ".+")
class AccountMailLockPostgresApiTest extends AccountMailLockApiTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GLAZELAB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_PASSWORD", ""));
        properties.add("spring.datasource.hikari.data-source-properties.options", () -> "-c lock_timeout=10s");
        properties.add("glazelab.images.path", () -> System.getenv("GLAZELAB_TEST_POSTGRES_IMAGES"));
    }
}
