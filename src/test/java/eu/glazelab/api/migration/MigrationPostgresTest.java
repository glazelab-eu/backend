package eu.glazelab.api.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MigrationTest} on PostgreSQL: every migration meets the production database before a release
 * ({@code scripts/rehearse-postgres.sh}). Each test gets a database of its own, created on the server of
 * {@code GLAZELAB_TEST_POSTGRES_URL} (a disposable server only) and dropped afterwards. The tests' H2 function
 * {@code RANDOM_UUID()} is provided as a wrapper of {@code gen_random_uuid()} in a schema of its own.
 */
@EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_POSTGRES_URL", matches = ".+")
class MigrationPostgresTest extends MigrationTest {
    private static final String URL = System.getenv("GLAZELAB_TEST_POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_PASSWORD", "");
    private static final List<String> CREATED = new ArrayList<>();

    @Override protected Db newDatabase(String name) throws SQLException {
        String database = "migration_" + name.replace('-', '_') + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(URL, USER, PASSWORD); var sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        synchronized (CREATED) { CREATED.add(database); }
        Db db = new Db(URL.replaceFirst("^(jdbc:postgresql://[^/]+/)[^?]*", "$1" + database), USER, PASSWORD);
        // Outside "public", which Flyway wants empty, but on the search path of every later session.
        try (Connection connection = DriverManager.getConnection(db.url(), USER, PASSWORD); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA h2_compat");
            sql.execute("CREATE FUNCTION h2_compat.random_uuid() RETURNS uuid LANGUAGE sql AS 'SELECT gen_random_uuid()'");
            sql.execute("ALTER DATABASE " + database + " SET search_path = public, h2_compat");
        }
        return db;
    }

    @Override protected SingleConnectionDataSource database(String name) throws SQLException {
        Db db = newDatabase(name);
        return new SingleConnectionDataSource(db.url(), db.user(), db.password(), true);
    }

    /**
     * Every migration from an empty database under the production session settings (application-prod.properties): the
     * lock timeout bounds only lock waits, so Flyway's run is not cut.
     */
    @Test void everyMigrationRunsUnderTheProductionLockTimeout() throws Exception {
        var prod = new Properties();
        try (var file = MigrationPostgresTest.class.getResourceAsStream("/application-prod.properties")) { prod.load(file); }
        String options = prod.getProperty("spring.datasource.hikari.data-source-properties.options");
        assertTrue(options != null && options.contains("lock_timeout"), options);
        Db db = newDatabase("prod-session");
        var settings = new Properties();
        settings.setProperty("options", options);
        var source = new SingleConnectionDataSource(db.url(), db.user(), db.password(), true);
        source.setConnectionProperties(settings);
        try {
            Flyway.configure().dataSource(source).load().migrate();
            try (var connection = source.getConnection(); var sql = connection.createStatement();
                    var row = sql.executeQuery("SELECT current_setting('lock_timeout'), current_setting('statement_timeout')")) {
                assertTrue(row.next());
                assertEquals("10s", row.getString(1));
                assertEquals("0", row.getString(2));
            }
        } finally { source.destroy(); }
    }

    @AfterAll static void dropDatabases() throws SQLException {
        try (Connection admin = DriverManager.getConnection(URL, USER, PASSWORD); var sql = admin.createStatement()) {
            for (String database : CREATED) sql.execute("DROP DATABASE IF EXISTS " + database.toLowerCase(Locale.ROOT) + " WITH (FORCE)");
        }
    }
}
