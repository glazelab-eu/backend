package eu.glazelab.api.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Prod has no default database password. Boot's property binder leaves an unresolvable
 * {@code ${GLAZELAB_DB_PASSWORD}} as literal text, so the datasource would otherwise be tried with that
 * text; this check binds the property the same lenient way before any bean exists and fails the start with
 * the variable's name. Strict resolution would reject (and echo) a password containing {@code ${...}}.
 */
@Component
@Profile("prod")
class RequiredDatabasePassword implements BeanFactoryPostProcessor, EnvironmentAware {
    private Environment environment;

    @Override public void setEnvironment(Environment environment) { this.environment = environment; }

    @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String password = Binder.get(environment).bind("spring.datasource.password", String.class).orElse("");
        if (password.isBlank() || password.equals("${GLAZELAB_DB_PASSWORD}")) {
            // Never include the value: it is a secret.
            throw new IllegalStateException("GLAZELAB_DB_PASSWORD must be set to a non-empty value in the prod profile");
        }
    }
}
