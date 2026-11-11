package eu.glazelab.api.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The prod profile has no default database password: a missing secret fails the start, naming the variable. */
class RequiredDatabasePasswordTest {
    private void check(Map<String, Object> variables) throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        environment.getPropertySources().addLast(new ResourcePropertySource(new ClassPathResource("application-prod.properties")));
        var check = new RequiredDatabasePassword();
        check.setEnvironment(environment);
        check.postProcessBeanFactory(null);
    }

    @Test void missingPasswordFailsNamingTheVariable() {
        assertThatThrownBy(() -> check(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GLAZELAB_DB_PASSWORD");
    }

    @Test void emptyPasswordFails() {
        assertThatThrownBy(() -> check(Map.of("GLAZELAB_DB_PASSWORD", " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GLAZELAB_DB_PASSWORD");
    }

    @Test void presentPasswordPasses() {
        assertThatCode(() -> check(Map.of("GLAZELAB_DB_PASSWORD", "s3cret"))).doesNotThrowAnyException();
    }

    @Test void passwordWithPlaceholderSyntaxPassesWithoutEcho() {
        // Boot binds such a value literally; the check must neither reject nor print it.
        assertThatCode(() -> check(Map.of("GLAZELAB_DB_PASSWORD", "pa${ss}word"))).doesNotThrowAnyException();
        assertThatCode(() -> check(Map.of("GLAZELAB_DB_PASSWORD", "x$y${z"))).doesNotThrowAnyException();
    }
}
