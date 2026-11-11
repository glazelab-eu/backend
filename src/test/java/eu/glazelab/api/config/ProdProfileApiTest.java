package eu.glazelab.api.config;

import eu.glazelab.api.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The prod profile, activated through the legacy "postgres" alias; H2 stands in for PostgreSQL. */
@ActiveProfiles("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:prod;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=test",
        "glazelab.ban.secret=prod-test-ban-secret-5e1b7c9a3d2f4e6a8b0c",
        "spring.mail.host=",
        "glazelab.images.path=target/test-images" })
class ProdProfileApiTest extends ApiTestBase {
    @Autowired Environment environment;

    @Test void postgresIsAnAliasForProd() {
        assertThat(environment.acceptsProfiles(Profiles.of("prod"))).isTrue();
        assertThat(environment.acceptsProfiles(Profiles.of("dev"))).isFalse();
    }

    @Test void apiDocumentationIsOff() {
        guest().get().uri("/api/openapi").exchange().expectStatus().isNotFound();
        guest().get().uri("/swagger-ui/index.html").exchange().expectStatus().isUnauthorized();
        guest().get().uri("/v3/api-docs").exchange().expectStatus().isUnauthorized();
        guest().get().uri("/api/health").exchange().expectStatus().isOk();
    }

    @Test void trustsForwardedProtoFromTheLocalProxy() {
        // The test client connects from 127.0.0.1, an internal proxy address for Tomcat's RemoteIpValve.
        guest().get().uri("/api/auth/csrf").header("X-Forwarded-Proto", "https").exchange()
                .expectHeader().exists("Strict-Transport-Security")
                .expectHeader().value("Set-Cookie", cookie -> assertThat(cookie).contains("Secure"));
        guest().get().uri("/api/auth/csrf").exchange()
                .expectHeader().doesNotExist("Strict-Transport-Security");
    }
}
