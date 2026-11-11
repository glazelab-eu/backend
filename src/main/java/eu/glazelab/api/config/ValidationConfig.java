package eu.glazelab.api.config;

import org.hibernate.validator.HibernateValidatorConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean Validation stops at the first violation: a request answers one message anyway, and a list over its size
 * bound is refused before its elements are validated one by one.
 */
@Configuration
public class ValidationConfig {
    @Bean ValidationConfigurationCustomizer failFast() {
        return configuration -> {
            if (configuration instanceof HibernateValidatorConfiguration hibernate) hibernate.failFast(true);
        };
    }
}
