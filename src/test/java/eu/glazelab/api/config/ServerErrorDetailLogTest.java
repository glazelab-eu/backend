package eu.glazelab.api.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.repository.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL's unique-violation detail ("Key (email)=(...) already exists") must reach neither the logs nor the exception
 * message. The datasource setting under test comes from {@code application-prod.properties} (loaded as a test property
 * source); only the connection, the images directory and the prod settings this test cannot satisfy are overridden.
 * Opt in against a disposable database only.
 */
@EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {
        "glazelab.require-email-verification=false", "server.servlet.session.cookie.secure=false",
        "spring.mail.host=", "glazelab.rate-limit.enabled=false" })
@TestPropertySource(locations = "classpath:application-prod.properties")
class ServerErrorDetailLogTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GLAZELAB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_PASSWORD", ""));
        properties.add("glazelab.images.path", () -> System.getenv("GLAZELAB_TEST_POSTGRES_IMAGES"));
    }
    @Autowired AppUserRepository users;
    @Autowired TransactionTemplate transactions;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    /**
     * The prod pool states its size, and every session waits at most the lock timeout for a row: a wait behind a held
     * lock fails with SQLState 55P03 after about 10 s instead of queueing; statements themselves have no time limit.
     */
    @Test void theProdPoolIsSizedAndALockWaitEndsAfterTheLockTimeout() throws Exception {
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(10);
        assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("10s");
        assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("0");
        UUID id = transactions.execute(tx -> users.saveAndFlush(new AppUser("lock-" + UUID.randomUUID() + "@example.test", "unused", "Lock")).getId());
        var waited = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        long[] took = new long[1];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForList("select id from app_user where id = ? for no key update", UUID.class, id);
            var waiter = new Thread(() -> {
                long start = System.nanoTime();
                try { transactions.executeWithoutResult(other -> users.lockById(id)); }
                catch (Throwable error) { waited.set(error); }
                took[0] = (System.nanoTime() - start) / 1_000_000;
            });
            waiter.start();
            try { waiter.join(30_000); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
        });
        assertThat(waited.get()).isNotNull();
        boolean lockTimeout = false;
        for (Throwable cause = waited.get(); cause != null; cause = cause.getCause())
            if (cause instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) lockTimeout = true;
        assertThat(lockTimeout).as("lock_not_available").isTrue();
        assertThat(took[0]).isBetween(9_000L, 20_000L);
    }

    @Test void uniqueViolationLogsAndThrowsNoRowValue() {
        String canary = "leak-canary-" + UUID.randomUUID() + "@example.test";
        transactions.executeWithoutResult(tx -> users.saveAndFlush(new AppUser(canary, "unused", "First")));
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try {
            assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> users.saveAndFlush(new AppUser(canary, "unused", "Second"))))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> {
                        assertThat(ApiErrorHandler.isUniqueViolation(error)).as("SQLState 23505 survives").isTrue();
                        for (Throwable cause = error; cause != null; cause = cause.getCause())
                            assertThat(String.valueOf(cause.getMessage())).doesNotContain(canary);
                    });
        } finally {
            root.detachAppender(captured);
        }
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            lines.add(event.getFormattedMessage());
            for (IThrowableProxy error = event.getThrowableProxy(); error != null; error = error.getCause()) lines.add(error.getMessage());
        }
        assertThat(captured.list).as("Hibernate logs the failure, so the test sees the logging path").isNotEmpty();
        assertThat(lines).noneMatch(line -> line != null && line.contains(canary));
    }
}
