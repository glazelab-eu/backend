package eu.glazelab.api.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every migration from an empty database and with rows of the version before it (backend guide "Schema"). On H2 here;
 * {@code MigrationPostgresTest} runs the same checks on PostgreSQL ({@code scripts/rehearse-postgres.sh}).
 */
class MigrationTest {
    /** Where a test's fresh database lives: a JDBC URL and its credentials. */
    protected record Db(String url, String user, String password) {}

    /** A fresh, empty database of its own for one test (H2 in memory, kept until the JVM ends). */
    protected Db newDatabase(String name) throws SQLException {
        return new Db("jdbc:h2:mem:" + name + "-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    /**
     * One session on a fresh database: H2 binds IN-list check constraints to the creating session. The H2 database ends
     * with {@code destroy()}.
     */
    protected SingleConnectionDataSource database(String name) throws SQLException {
        return new SingleConnectionDataSource("jdbc:h2:mem:" + name + "-" + UUID.randomUUID(), "sa", "", true);
    }

    /**
     * The constraints that migrations name; PostgreSQL 18 lists every NOT NULL as a constraint named
     * {@code <table>_<column>_not_null} too, which H2 does not.
     */
    private static final String CONSTRAINTS = "(SELECT * FROM information_schema.table_constraints"
            + " WHERE RIGHT(constraint_name, 9) <> '_not_null') named";

    /** The lower-case index names of one table (by its lower-case name), from the driver's metadata. */
    private static Set<String> indexes(Connection connection, String table) throws SQLException {
        var meta = connection.getMetaData();
        boolean lower = meta.storesLowerCaseIdentifiers();
        Set<String> names = new HashSet<>();
        try (var rows = meta.getIndexInfo(null, lower ? "public" : "PUBLIC", lower ? table : table.toUpperCase(Locale.ROOT), false, false)) {
            while (rows.next()) if (rows.getString("INDEX_NAME") != null) names.add(rows.getString("INDEX_NAME").toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /** The metadata of one column, by its upper-case name (PostgreSQL stores unquoted names in lower case). */
    private static java.sql.ResultSet columns(Connection connection, String table, String column) throws SQLException {
        var meta = connection.getMetaData();
        boolean lower = meta.storesLowerCaseIdentifiers();
        return meta.getColumns(null, lower ? "public" : "PUBLIC", lower ? table.toLowerCase(Locale.ROOT) : table,
                lower ? column.toLowerCase(Locale.ROOT) : column);
    }
    @Test void packagingMigrationPreservesPhotosAndEnforcesKindAndTarget() throws Exception {
        var source = database("packaging");
        Flyway.configure().dataSource(source).target("19").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE image ADD CONSTRAINT packaging_test_kind_required CHECK (kind IS NOT NULL)");
            UUID user = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID(), combo = UUID.randomUUID(), photo = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + user + "','packaging@example.test','hash','Maker')");
            sql.executeUpdate("INSERT INTO manufacturer (id,short_id,name,name_key) VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            sql.executeUpdate("INSERT INTO glaze (id,manufacturer_id,name,code) VALUES ('" + glaze + "','" + maker + "','Blue','PC-20')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at) VALUES ('" + combo + "','Layers','" + user + "',CURRENT_TIMESTAMP)");
            sql.executeUpdate(imageInsert(photo, "'" + combo + "'", "NULL", user));
            java.sql.Timestamp created;
            try (var row = sql.executeQuery("SELECT created_at FROM image WHERE id = '" + photo + "'")) { row.next(); created = row.getTimestamp(1); }
            Flyway.configure().dataSource(source).load().migrate();
            try (var row = sql.executeQuery("SELECT storage_key,created_at,kind FROM image WHERE id = '" + photo + "'")) {
                assertTrue(row.next()); assertEquals(photo + ".jpg", row.getString(1));
                assertEquals(created, row.getTimestamp(2)); assertEquals("TEST_TILE", row.getString(3));
            }
            UUID jar = UUID.randomUUID();
            sql.executeUpdate(imageInsert(jar, "NULL", "'" + glaze + "'", user).replace("'TEST_TILE'", "'PACKAGING'"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE image SET kind='PACKAGING' WHERE id='" + photo + "'"));
            for (String update : new String[]{"kind='JAR'", "visibility='UNKNOWN'", "visibility='CLUB'", "glaze_id=NULL"})
                assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE image SET " + update + " WHERE id='" + jar + "'"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name)='image'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("ck_image_kind", "ck_image_packaging_target", "ck_image_visibility", "ck_image_single_target", "packaging_test_kind_required")), constraints.toString());
        } finally { source.destroy(); }
    }

    @Test void pendingAccountMigrationGivesExistingUnverifiedAccountsAWindowWithoutChangingVerifiedOnes() throws Exception {
        var source = database("pending");
        Flyway.configure().dataSource(source).target("15").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name,email_verified) VALUES"
                    + " (RANDOM_UUID(),'pending@example.test','hash','Pending',FALSE),"
                    + " (RANDOM_UUID(),'active@example.test','hash','Active',TRUE)");
            var before = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(23);
            Flyway.configure().dataSource(source).load().migrate();
            try (var rows = sql.executeQuery("SELECT email_verified,verification_expires_at FROM app_user")) {
                while (rows.next()) {
                    if (rows.getBoolean(1)) assertNull(rows.getTimestamp(2));
                    else assertTrue(rows.getTimestamp(2).toLocalDateTime().isAfter(before));
                }
            }
        } finally { source.destroy(); }
    }

    @Test void glazeCreationDatesAreBackfilledWithTheMigrationTimeAndRequired() throws Exception {
        var source = database("glaze-dates");
        Flyway.configure().dataSource(source).target("16").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID maker = UUID.randomUUID(), glaze = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO manufacturer (id,short_id,name,name_key) VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            sql.executeUpdate("INSERT INTO glaze (id,manufacturer_id,name,code) VALUES ('" + glaze + "','" + maker + "','Blue','PC-20')");
            var before = java.time.Instant.now().minusSeconds(5);
            Flyway.configure().dataSource(source).load().migrate();
            try (var row = sql.executeQuery("SELECT created_at FROM glaze WHERE id = '" + glaze + "'")) {
                assertTrue(row.next());
                var created = row.getObject(1, java.time.OffsetDateTime.class).toInstant();
                assertTrue(created.isAfter(before) && created.isBefore(java.time.Instant.now().plusSeconds(5)));
            }
            try (var column = columns(connection, "GLAZE", "CREATED_AT")) {
                assertTrue(column.next());
                assertEquals(java.sql.DatabaseMetaData.columnNoNulls, column.getInt("NULLABLE"));
            }
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE glaze SET created_at = NULL"));
        } finally { source.destroy(); }
    }

    @Test void existingStepsBecomeGlazeStepsInOrderAndTheShapeIsEnforcedByType() throws Exception {
        var source = database("steps");
        Flyway.configure().dataSource(source).target("17").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID(), combo = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + user + "','steps@example.test','hash','Steps')");
            sql.executeUpdate("INSERT INTO manufacturer (id,short_id,name,name_key) VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            sql.executeUpdate("INSERT INTO glaze (id,manufacturer_id,name,code) VALUES ('" + glaze + "','" + maker + "','Blue','PC-20')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at) VALUES ('" + combo + "','Layers','" + user + "',CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO combination_step (id,combination_id,position,glaze_id,coats,method,notes) VALUES"
                    + " (RANDOM_UUID(),'" + combo + "',1,'" + glaze + "',2,'Brush','First'),"
                    + " (RANDOM_UUID(),'" + combo + "',2,'" + glaze + "',1,NULL,NULL)");
            Flyway.configure().dataSource(source).load().migrate();
            try (var rows = sql.executeQuery("SELECT position,step_type,glaze_id,coats,label,method,notes FROM combination_step ORDER BY position")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1)); assertEquals("GLAZE", rows.getString(2));
                assertEquals(glaze, rows.getObject(3, UUID.class)); assertEquals(2, rows.getInt(4));
                assertNull(rows.getString(5)); assertEquals("Brush", rows.getString(6)); assertEquals("First", rows.getString(7));
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1)); assertEquals("GLAZE", rows.getString(2));
            }
            String insert = "INSERT INTO combination_step (id,combination_id,position,step_type,glaze_id,coats,label,method,notes) VALUES (RANDOM_UUID(),'" + combo + "',";
            assertEquals(1, sql.executeUpdate(insert + "3,'CUSTOM',NULL,NULL,'Bisque firing',NULL,'Cone 04')"));
            assertEquals(1, sql.executeUpdate(insert + "4,'GLAZE','" + glaze + "',1,NULL,'Dip',NULL)"));
            String[] rejected = {
                "5,'CUSTOM','" + glaze + "',NULL,'Firing',NULL,NULL)",     // glaze on a custom step
                "5,'CUSTOM',NULL,1,'Firing',NULL,NULL)",                   // coats on a custom step
                "5,'CUSTOM',NULL,NULL,'Firing','Brush',NULL)",             // method on a custom step
                "5,'CUSTOM',NULL,NULL,NULL,NULL,NULL)",                    // no label
                "5,'GLAZE',NULL,1,NULL,NULL,NULL)",                        // no glaze
                "5,'GLAZE','" + glaze + "',NULL,NULL,NULL,NULL)",          // no coats
                "5,'GLAZE','" + glaze + "',1,'Label',NULL,NULL)",          // label on a glaze step
                "5,'KILN',NULL,NULL,'Firing',NULL,NULL)",                  // unknown type
                "5,'GLAZE','" + glaze + "',0,NULL,NULL,NULL)",             // coats must stay positive
            };
            for (String values : rejected) assertThrows(SQLException.class, () -> sql.executeUpdate(insert + values), values);
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO combination_step (id,combination_id,position,glaze_id,coats) VALUES (RANDOM_UUID(),'" + combo + "',6,'" + glaze + "',1)"),
                    "A step type is required");
        } finally { source.destroy(); }
    }

    @Test void sharedAuthorMigrationPreservesRowsAndForeignKeysWhileAllowingAnonymousContent() throws Exception {
        var source = database("anonymous");
        Flyway.configure().dataSource(source).target("13").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), combo = UUID.randomUUID(), club = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + user + "','old@example.test','hash','Old')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at) VALUES ('" + combo + "','Layers','" + user + "',CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO club (id,name,owner_id) VALUES ('" + club + "','Club','" + user + "')");
            Flyway.configure().dataSource(source).load().migrate();
            try (var row = sql.executeQuery("SELECT created_by FROM combination WHERE id = '" + combo + "'")) {
                assertTrue(row.next()); assertEquals(user, row.getObject(1, UUID.class));
            }
            assertEquals(1, sql.executeUpdate("UPDATE combination SET created_by = NULL WHERE id = '" + combo + "'"));
            assertEquals(1, sql.executeUpdate("UPDATE club SET owner_id = NULL WHERE id = '" + club + "'"));
            assertEquals(1, sql.executeUpdate("DELETE FROM app_user WHERE id = '" + user + "'"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE combination SET created_by = '" + UUID.randomUUID() + "'"));
            for (String field : new String[]{"GLAZE.CREATED_BY", "COMBINATION.CREATED_BY", "IMAGE.UPLOADED_BY", "CLUB.OWNER_ID"}) {
                var parts = field.split("\\.");
                try (var column = columns(connection, parts[0], parts[1])) {
                    assertTrue(column.next());
                    assertEquals(java.sql.DatabaseMetaData.columnNullable, column.getInt("NULLABLE"));
                }
            }
        } finally { source.destroy(); }
    }

    @Test void termsMigrationPreservesLegacyAccountsWithoutInventingAcceptance() throws Exception {
        var source = database("terms");
        Flyway.configure().dataSource(source).target("8").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES (RANDOM_UUID(),"
                    + "'legacy@example.test','hash','Legacy')");
            Flyway.configure().dataSource(source).load().migrate();
            try (var rows = sql.executeQuery("SELECT accepted_terms_version, terms_accepted_at FROM app_user")) {
                assertTrue(rows.next());
                assertNull(rows.getString(1));
                assertNull(rows.getTimestamp(2));
            }
            assertThrows(SQLException.class, () -> sql.executeUpdate(
                    "UPDATE app_user SET accepted_terms_version = '2026-10-27'"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(
                    "UPDATE app_user SET terms_accepted_at = CURRENT_TIMESTAMP"));
            assertEquals(1, sql.executeUpdate("UPDATE app_user SET accepted_terms_version = '2026-10-27',"
                    + " terms_accepted_at = CURRENT_TIMESTAMP"));
        } finally {
            source.destroy();
        }
    }

    @Test void upgradePreservesLegacyIdsAndEnforcesCatalogUniqueness() throws Exception {
        Db db = newDatabase("migration");
        Flyway.configure().dataSource(db.url(), db.user(), db.password()).target("1").load().migrate();
        UUID userId = UUID.randomUUID();
        UUID glazeId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(db.url(), db.user(), db.password());
                var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user VALUES ('" + userId + "','maker@example.test','hash','Maker')");
            sql.executeUpdate("INSERT INTO glaze VALUES ('" + glazeId + "','Amaco','Blue',' pc-20 ',NULL,'" + userId + "')");
            sql.executeUpdate("INSERT INTO glaze VALUES ('" + UUID.randomUUID() + "','Legacy','Homemade',NULL,NULL,'" + userId + "')");
            sql.executeUpdate("INSERT INTO club VALUES ('" + clubId + "','Legacy club','" + userId + "')");
            sql.executeUpdate("INSERT INTO membership VALUES ('" + clubId + "','" + userId + "')");
            // V10 replaces the brand-based catalog key; manufacturersReplaceBrandsAndCodesBecomeMandatory covers it.
            Flyway.configure().dataSource(db.url(), db.user(), db.password()).target("9").load().migrate();
            try (var result = sql.executeQuery("SELECT role FROM membership WHERE club_id='" + clubId + "'")) {
                assertTrue(result.next());
                assertEquals("ADMIN", result.getString("role"));
            }
            try (var result = sql.executeQuery("SELECT house_rules, invites_enabled, invite_hash FROM club")) {
                assertTrue(result.next());
                assertEquals("", result.getString("house_rules"));
                assertFalse(result.getBoolean("invites_enabled"));
                assertNull(result.getString("invite_hash"));
            }
            try (var result = sql.executeQuery("SELECT id, code, catalog_key FROM glaze WHERE id='" + glazeId + "'")) {
                assertTrue(result.next());
                assertEquals(glazeId, UUID.fromString(result.getString("id")));
                assertEquals("PC-20", result.getString("code"));
                assertEquals("amaco:PC-20", result.getString("catalog_key"));
            }
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO glaze "
                    + "(id, brand, name, code, created_by, catalog_key) VALUES ('" + UUID.randomUUID()
                    + "','amaco','Another name','PC-20','" + userId + "','amaco:PC-20')"));
            try (var result = sql.executeQuery("SELECT count(*) FROM glaze")) {
                result.next();
                assertEquals(2, result.getInt(1));
            }
        }
    }

    @Test void uuidConversionPreservesKeysRelationshipsAndMemberships() throws Exception {
        // H2 binds IN-list check constraints to the creating session, so keep one session open.
        var dataSource = database("uuid");
        Flyway.configure().dataSource(dataSource).target("6").load().migrate();
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), club = UUID.randomUUID();
        UUID glaze = UUID.randomUUID(), combination = UUID.randomUUID(), step = UUID.randomUUID();
        UUID personalStock = UUID.randomUUID(), clubStock = UUID.randomUUID(), image = UUID.randomUUID();
        UUID token = UUID.randomUUID(), glazeFavorite = UUID.randomUUID(), comboFavorite = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            // Legacy text may be upper case; the native type normalizes it consistently on both sides.
            String ownerText = owner.toString().toUpperCase(Locale.ROOT);
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('"
                    + ownerText + "','owner@example.test','hash','Owner'), ('" + member
                    + "','member@example.test','hash','Member')");
            sql.executeUpdate("INSERT INTO club (id, name, owner_id) VALUES ('" + club + "','Studio','" + ownerText + "')");
            sql.executeUpdate("INSERT INTO membership (club_id, user_id, role) VALUES ('" + club + "','"
                    + ownerText + "','ADMIN'), ('" + club + "','" + member + "','MEMBER')");
            sql.executeUpdate("INSERT INTO glaze (id, brand, name, code, created_by, catalog_key) VALUES ('"
                    + glaze + "','Amaco','Blue','PC-20','" + ownerText + "','amaco:PC-20')");
            sql.executeUpdate("INSERT INTO stock (id, glaze_id, user_id, club_id, quantity_g) VALUES ('"
                    + personalStock + "','" + glaze + "','" + member + "',NULL,250), ('"
                    + clubStock + "','" + glaze + "',NULL,'" + club + "',900)");
            sql.executeUpdate("INSERT INTO combination (id, title, created_by, created_at) VALUES ('"
                    + combination + "','Blue over white','" + member + "',CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO combination_step (id, combination_id, position, glaze_id, coats) VALUES ('"
                    + step + "','" + combination + "',0,'" + glaze + "',2)");
            sql.executeUpdate("INSERT INTO image (id, combination_id, uploaded_by, club_id, visibility, kind,"
                    + " storage_key, width, height, created_at) VALUES ('" + image + "','" + combination + "','"
                    + member + "','" + club + "','CLUB','TEST_TILE','legacy.jpg',10,10,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO account_token (id, user_id, purpose, token_hash, issued_at, expires_at)"
                    + " VALUES ('" + token + "','" + member + "','MAGIC_LOGIN','" + "a".repeat(64)
                    + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO favorite (id, user_id, glaze_id, combination_id) VALUES ('"
                    + glazeFavorite + "','" + member + "','" + glaze + "',NULL), ('" + comboFavorite + "','"
                    + ownerText + "',NULL,'" + combination + "')");

            Flyway.configure().dataSource(dataSource).target("7").load().migrate();

            assertKeyColumnsAreNativeUuid(connection);
            try (var rows = sql.executeQuery("SELECT m.club_id, m.user_id, m.role, u.email FROM membership m"
                    + " JOIN app_user u ON u.id = m.user_id JOIN club c ON c.id = m.club_id"
                    + " AND c.owner_id = m.user_id")) {
                assertTrue(rows.next());
                assertEquals(club, rows.getObject("club_id", UUID.class));
                assertEquals(owner, rows.getObject("user_id", UUID.class));
                assertEquals("ADMIN", rows.getString("role"));
                assertEquals("owner@example.test", rows.getString("email"));
                assertFalse(rows.next());
            }
            assertEquals(2, count(sql, "SELECT count(*) FROM membership m JOIN app_user u ON u.id = m.user_id"));
            assertEquals(1, count(sql, "SELECT count(*) FROM stock s JOIN glaze g ON g.id = s.glaze_id"
                    + " JOIN app_user u ON u.id = s.user_id WHERE s.quantity_g = 250 AND u.email = 'member@example.test'"));
            assertEquals(1, count(sql, "SELECT count(*) FROM stock s JOIN club c ON c.id = s.club_id"
                    + " WHERE s.quantity_g = 900"));
            assertEquals(1, count(sql, "SELECT count(*) FROM combination_step s"
                    + " JOIN combination c ON c.id = s.combination_id JOIN glaze g ON g.id = s.glaze_id"
                    + " JOIN app_user u ON u.id = c.created_by WHERE g.created_by = '" + owner + "'"));
            assertEquals(1, count(sql, "SELECT count(*) FROM image i JOIN combination c ON c.id = i.combination_id"
                    + " JOIN club k ON k.id = i.club_id JOIN app_user u ON u.id = i.uploaded_by"));
            assertEquals(1, count(sql, "SELECT count(*) FROM account_token t JOIN app_user u ON u.id = t.user_id"));
            assertEquals(2, count(sql, "SELECT count(*) FROM favorite f JOIN app_user u ON u.id = f.user_id"
                    + " LEFT JOIN glaze g ON g.id = f.glaze_id LEFT JOIN combination c ON c.id = f.combination_id"
                    + " WHERE g.id IS NOT NULL OR c.id IS NOT NULL"));
            try (var rows = sql.executeQuery("SELECT id FROM image")) {
                assertTrue(rows.next());
                assertEquals(image, rows.getObject(1, UUID.class));
            }
            for (UUID id : new UUID[] {personalStock, clubStock, step, token, glazeFavorite, comboFavorite}) {
                assertEquals(1, count(sql, "SELECT (SELECT count(*) FROM stock WHERE id = '" + id + "')"
                        + " + (SELECT count(*) FROM combination_step WHERE id = '" + id + "')"
                        + " + (SELECT count(*) FROM account_token WHERE id = '" + id + "')"
                        + " + (SELECT count(*) FROM favorite WHERE id = '" + id + "')"));
            }

            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE constraint_type = 'FOREIGN KEY'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertEquals(17, constraints.size());
            assertTrue(constraints.containsAll(Set.of("fk_membership_club_id", "fk_membership_user_id",
                    "fk_stock_glaze_id", "fk_image_club_id", "fk_account_token_user_id",
                    "fk_favorite_combination_id")));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO stock (id, glaze_id, user_id,"
                    + " quantity_g) VALUES (RANDOM_UUID(),'" + UUID.randomUUID() + "','" + member + "',1)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO membership (club_id, user_id)"
                    + " VALUES ('" + club + "','" + member + "')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("DELETE FROM app_user WHERE id = '" + member + "'"));
        } finally {
            dataSource.destroy();
        }
    }

    @Test void uuidConversionRejectsNonUuidIdentifiersBeforeChangingTheSchema() throws Exception {
        Db db = newDatabase("uuid-invalid");
        Flyway.configure().dataSource(db.url(), db.user(), db.password()).target("6").load().migrate();
        try (var connection = DriverManager.getConnection(db.url(), db.user(), db.password());
                var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name)"
                    + " VALUES ('legacy-user-1','legacy@example.test','hash','Legacy')");
            var error = assertThrows(FlywayException.class,
                    () -> Flyway.configure().dataSource(db.url(), db.user(), db.password()).load().migrate());
            String messages = "";
            for (Throwable cause = error; cause != null; cause = cause.getCause()) messages += cause.getMessage();
            assertTrue(messages.contains("app_user.id='legacy-user-1'"), messages);
            try (var rows = sql.executeQuery("SELECT UPPER(data_type) FROM information_schema.columns"
                    + " WHERE LOWER(table_name) = 'app_user' AND LOWER(column_name) = 'id'")) {
                assertTrue(rows.next());
                assertEquals("CHARACTER VARYING", rows.getString(1));
            }
            assertEquals(1, count(sql, "SELECT count(*) FROM app_user WHERE id = 'legacy-user-1'"));
        }
    }

    @Test void glazeImagesKeepExistingCombinationImagesAndRequireExactlyOneTarget() throws Exception {
        // H2 binds IN-list check constraints to the creating session, so keep one session open.
        var dataSource = database("glaze-images");
        Flyway.configure().dataSource(dataSource).target("7").load().migrate();
        UUID user = UUID.randomUUID(), glaze = UUID.randomUUID(), combination = UUID.randomUUID();
        UUID legacyImage = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('"
                    + user + "','maker@example.test','hash','Maker')");
            sql.executeUpdate("INSERT INTO glaze (id, brand, name, code, created_by, catalog_key) VALUES ('"
                    + glaze + "','Amaco','Blue','PC-20','" + user + "','amaco:PC-20')");
            sql.executeUpdate("INSERT INTO combination (id, title, created_by, created_at) VALUES ('"
                    + combination + "','Blue over white','" + user + "',CURRENT_TIMESTAMP)");
            sql.executeUpdate(imageInsert(legacyImage, "'" + combination + "'", null, user));

            Flyway.configure().dataSource(dataSource).load().migrate();

            try (var rows = sql.executeQuery("SELECT combination_id, glaze_id FROM image WHERE id = '" + legacyImage + "'")) {
                assertTrue(rows.next());
                assertEquals(combination, rows.getObject("combination_id", UUID.class));
                assertNull(rows.getObject("glaze_id"));
            }
            sql.executeUpdate(imageInsert(UUID.randomUUID(), "NULL", "'" + glaze + "'", user));
            assertEquals(1, count(sql, "SELECT count(*) FROM image i JOIN glaze g ON g.id = i.glaze_id"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(
                    imageInsert(UUID.randomUUID(), "'" + combination + "'", "'" + glaze + "'", user)), "Two targets");
            assertThrows(SQLException.class, () -> sql.executeUpdate(
                    imageInsert(UUID.randomUUID(), "NULL", null, user)), "No target");
            assertThrows(SQLException.class, () -> sql.executeUpdate(
                    imageInsert(UUID.randomUUID(), "NULL", "'" + UUID.randomUUID() + "'", user)), "Unknown glaze");
            assertThrows(SQLException.class, () -> sql.executeUpdate("DELETE FROM glaze WHERE id = '" + glaze + "'"));
            assertEquals(2, count(sql, "SELECT count(*) FROM image"));

            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) = 'image'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("fk_image_glaze_id", "ck_image_single_target",
                    "fk_image_combination_id")), constraints.toString());
            try (var rows = sql.executeQuery("SELECT UPPER(data_type), is_nullable FROM information_schema.columns"
                    + " WHERE LOWER(table_name) = 'image' AND LOWER(column_name) IN ('glaze_id', 'combination_id')")) {
                int columns = 0;
                while (rows.next()) {
                    columns++;
                    assertEquals("UUID", rows.getString(1));
                    assertEquals("YES", rows.getString(2));
                }
                assertEquals(2, columns);
            }
        } finally {
            dataSource.destroy();
        }
    }

    @Test void manufacturersReplaceBrandsAndCodesBecomeMandatory() throws Exception {
        // H2 binds IN-list check constraints to the creating session, so keep one session open.
        var dataSource = database("manufacturers");
        Flyway.configure().dataSource(dataSource).target("9").load().migrate();
        UUID user = UUID.randomUUID(), blue = UUID.randomUUID(), plain = UUID.randomUUID(), cafe = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('"
                    + user + "','maker@example.test','hash','Maker')");
            String insert = "INSERT INTO glaze (id, brand, name, code, created_by, catalog_key) VALUES ";
            sql.executeUpdate(insert + "('" + blue + "','  Amaco ','Blue','PC-20','" + user + "','amaco:PC-20'),"
                    + " (RANDOM_UUID(),'AMACO','Red','PC-21','" + user + "','amaco:PC-21'),"
                    + " (RANDOM_UUID(),'amaco','Taken','NOCODE-1','" + user + "','amaco:NOCODE-1'),"
                    + " ('" + plain + "','Amaco','Plain',NULL,'" + user + "',NULL),"
                    + " (RANDOM_UUID(),'Amaco','Empty','','" + user + "',NULL),"
                    + " (RANDOM_UUID(),'Amaco!','Other','PC-20','" + user + "','amaco!:PC-20'),"
                    + " ('" + cafe + "','Café Crème Company','Latte',NULL,'" + user + "',NULL),"
                    + " (RANDOM_UUID(),'3D','Cube','X-1','" + user + "','3d:X-1')");
            sql.executeUpdate("INSERT INTO stock (id, glaze_id, user_id, quantity_g) VALUES (RANDOM_UUID(),'"
                    + plain + "','" + user + "',250)");

            Flyway.configure().dataSource(dataSource).load().migrate();

            Set<String> manufacturers = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT short_id || '|' || name || '|' || name_key FROM manufacturer")) {
                while (rows.next()) manufacturers.add(rows.getString(1));
            }
            assertEquals(Set.of("AMACO|Amaco|amaco", "AMACO2|Amaco!|amaco!", "CAFECREMEC|Café Crème Company|café crème company",
                    "MFR3D|3D|3d"), manufacturers);
            Set<String> amaco = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT g.name || '=' || g.code FROM glaze g"
                    + " JOIN manufacturer m ON m.id = g.manufacturer_id WHERE m.short_id = 'AMACO'")) {
                while (rows.next()) amaco.add(rows.getString(1));
            }
            assertEquals(Set.of("Blue=PC-20", "Red=PC-21", "Taken=NOCODE-1", "Empty=NOCODE-2", "Plain=NOCODE-3"), amaco);
            assertEquals(1, count(sql, "SELECT count(*) FROM glaze g JOIN manufacturer m ON m.id = g.manufacturer_id"
                    + " WHERE g.id = '" + cafe + "' AND g.code = 'NOCODE-1'"));
            assertEquals(1, count(sql, "SELECT count(*) FROM stock s JOIN glaze g ON g.id = s.glaze_id"
                    + " WHERE g.id = '" + plain + "' AND s.quantity_g = 250"));
            assertEquals(0, count(sql, "SELECT count(*) FROM information_schema.columns WHERE LOWER(table_name) = 'glaze'"
                    + " AND LOWER(column_name) IN ('brand', 'catalog_key')"));
            assertEquals(2, count(sql, "SELECT count(*) FROM information_schema.columns WHERE LOWER(table_name) = 'glaze'"
                    + " AND LOWER(column_name) IN ('manufacturer_id', 'code') AND is_nullable = 'NO'"));

            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) IN ('glaze', 'manufacturer')")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_manufacturer", "uq_manufacturer_short_id",
                    "uq_manufacturer_name_key", "fk_glaze_manufacturer_id", "uq_glaze_manufacturer_code")),
                    constraints.toString());
            String manufacturer = "(SELECT id FROM manufacturer WHERE short_id = 'AMACO')";
            String glaze = "INSERT INTO glaze (id, manufacturer_id, name, code, created_by) VALUES (RANDOM_UUID(),";
            assertThrows(SQLException.class, () -> sql.executeUpdate(glaze + manufacturer + ",'Again','PC-20','" + user + "')"),
                    "Duplicate code");
            assertThrows(SQLException.class, () -> sql.executeUpdate(glaze + manufacturer + ",'None',NULL,'" + user + "')"),
                    "Missing code");
            assertThrows(SQLException.class, () -> sql.executeUpdate(glaze + "NULL,'None','PC-99','" + user + "')"),
                    "Missing manufacturer");
            assertThrows(SQLException.class, () -> sql.executeUpdate(glaze + "'" + UUID.randomUUID() + "','None','PC-99','"
                    + user + "')"), "Unknown manufacturer");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO manufacturer (id, short_id, name, name_key)"
                    + " VALUES (RANDOM_UUID(),'AMACO','Another','another')"), "Duplicate short ID");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO manufacturer (id, short_id, name, name_key)"
                    + " VALUES (RANDOM_UUID(),'AMA','AMACO','amaco')"), "Duplicate name");
            assertEquals(1, sql.executeUpdate(glaze + manufacturer + ",'New','PC-99','" + user + "')"));
        } finally {
            dataSource.destroy();
        }
    }

    private static String imageInsert(UUID id, String combination, String glaze, UUID user) {
        return "INSERT INTO image (id, combination_id, " + (glaze == null ? "" : "glaze_id, ") + "uploaded_by,"
                + " visibility, kind, storage_key, width, height, created_at) VALUES ('" + id + "'," + combination + ","
                + (glaze == null ? "" : glaze + ",") + "'" + user + "','PUBLIC','TEST_TILE','" + id + ".jpg',10,10,"
                + "CURRENT_TIMESTAMP)";
    }

    private static void assertKeyColumnsAreNativeUuid(Connection connection) throws SQLException {
        String[] columns = {"app_user.id", "club.id", "club.owner_id", "membership.club_id", "membership.user_id",
                "glaze.id", "glaze.created_by", "stock.id", "stock.glaze_id", "stock.user_id", "stock.club_id",
                "combination.id", "combination.created_by", "combination_step.id",
                "combination_step.combination_id", "combination_step.glaze_id", "image.id",
                "image.combination_id", "image.uploaded_by", "image.club_id", "account_token.id",
                "account_token.user_id", "favorite.id", "favorite.user_id", "favorite.glaze_id",
                "favorite.combination_id"};
        try (var query = connection.prepareStatement("SELECT UPPER(data_type) FROM information_schema.columns"
                + " WHERE LOWER(table_name) = ? AND LOWER(column_name) = ?")) {
            for (String column : columns) {
                String[] parts = column.split("\\.");
                query.setString(1, parts[0]);
                query.setString(2, parts[1]);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next(), column);
                    assertEquals("UUID", rows.getString(1), column);
                }
            }
        }
    }

    private static int count(Statement sql, String query) throws SQLException {
        try (var rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test void tagTablesKeepOneRowPerItemAndTagAndNameTheirConstraints() throws Exception {
        var dataSource = database("tags");
        Flyway.configure().dataSource(dataSource).load().migrate();
        UUID user = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID(), combination = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, email_key, password_hash, display_name) VALUES ('"
                    + user + "','maker@example.test','maker@example.test','hash','Maker')");
            sql.executeUpdate("INSERT INTO manufacturer VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            sql.executeUpdate("INSERT INTO glaze (id, manufacturer_id, name, code, created_by) VALUES ('"
                    + glaze + "','" + maker + "','Blue','PC-20','" + user + "')");
            sql.executeUpdate("INSERT INTO combination (id, title, created_by, created_at) VALUES ('"
                    + combination + "','Layered','" + user + "',CURRENT_TIMESTAMP)");
            for (String table : new String[] {"glaze_tag (glaze_id", "combination_tag (combination_id"}) {
                UUID owner = table.startsWith("glaze") ? glaze : combination;
                String insert = "INSERT INTO " + table + ", tag) VALUES ('";
                sql.executeUpdate(insert + owner + "','glänzend'), ('" + owner + "','glanzend')");
                assertThrows(SQLException.class, () -> sql.executeUpdate(insert + owner + "','glänzend')"));
                assertThrows(SQLException.class, () -> sql.executeUpdate(insert + owner + "','')"));
                assertThrows(SQLException.class, () -> sql.executeUpdate(insert + UUID.randomUUID() + "','orphan')"));
            }
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) IN ('glaze_tag', 'combination_tag')")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertEquals(Set.of("pk_glaze_tag", "fk_glaze_tag_glaze_id", "ck_glaze_tag_not_empty", "pk_combination_tag",
                    "fk_combination_tag_combination_id", "ck_combination_tag_not_empty"), constraints);
        }
    }

    @Test void siteAdminFlagDefaultsToFalseForExistingAndNewAccounts() throws Exception {
        var source = database("admins");
        Flyway.configure().dataSource(source).target("11").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES (RANDOM_UUID(),"
                    + "'legacy@example.test','hash','Legacy')");
            Flyway.configure().dataSource(source).load().migrate();
            sql.executeUpdate("INSERT INTO app_user (id, email, email_key, password_hash, display_name) VALUES (RANDOM_UUID(),"
                    + "'new@example.test','new@example.test','hash','New')");
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM app_user WHERE site_admin = FALSE")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE app_user SET site_admin = NULL"));
        } finally {
            source.destroy();
        }
    }

    @Test void barcodesAreUniqueInTheLibraryAndFollowTheirGlaze() throws Exception {
        var source = database("barcodes");
        Flyway.configure().dataSource(source).target("14").load().migrate();
        UUID user = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID(), other = UUID.randomUUID();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('"
                    + user + "','maker@example.test','hash','Maker')");
            sql.executeUpdate("INSERT INTO manufacturer VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            for (UUID id : new UUID[] {glaze, other})
                sql.executeUpdate("INSERT INTO glaze (id, manufacturer_id, name, code, created_by) VALUES ('"
                        + id + "','" + maker + "','Blue','PC-" + id.toString().substring(0, 4) + "','" + user + "')");
            Flyway.configure().dataSource(source).load().migrate();
            String insert = "INSERT INTO glaze_barcode (barcode, glaze_id) VALUES ('";
            sql.executeUpdate(insert + "4006381333931','" + glaze + "'), ('96385074','" + glaze + "')");
            // Unique in the whole library, not per glaze or manufacturer.
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "4006381333931','" + other + "')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "036000291452','" + other + "')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "0036000291452','" + UUID.randomUUID() + "')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("DELETE FROM glaze WHERE id = '" + glaze + "'"));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM glaze_barcode WHERE glaze_id = '" + glaze + "'")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) = 'glaze_barcode'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertEquals(Set.of("pk_glaze_barcode", "fk_glaze_barcode_glaze_id", "ck_glaze_barcode_length"), constraints);
            try (var column = columns(connection, "GLAZE_BARCODE", "GLAZE_ID")) {
                assertTrue(column.next());
                assertEquals("UUID", column.getString("TYPE_NAME").toUpperCase(Locale.ROOT));
            }
        } finally {
            source.destroy();
        }
    }

    @Test void stockEntriesGainAnOptionalPositiveJarSize() throws Exception {
        var source = database("jar-size");
        Flyway.configure().dataSource(source).target("18").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID(), other = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + user + "','jar@example.test','hash','Jar')");
            sql.executeUpdate("INSERT INTO manufacturer (id,short_id,name,name_key) VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            for (UUID id : new UUID[] {glaze, other})
                sql.executeUpdate("INSERT INTO glaze (id,manufacturer_id,name,code) VALUES ('" + id + "','" + maker + "','Blue','PC-" + id.toString().substring(0, 4) + "')");
            sql.executeUpdate("INSERT INTO stock (id,glaze_id,user_id,quantity_g) VALUES (RANDOM_UUID(),'" + glaze + "','" + user + "',450)");
            Flyway.configure().dataSource(source).load().migrate();
            try (var rows = sql.executeQuery("SELECT quantity_g, jar_size_g FROM stock")) {
                assertTrue(rows.next());
                assertEquals(450, rows.getInt(1));
                assertNull(rows.getObject(2));
            }
            String insert = "INSERT INTO stock (id,glaze_id,user_id,quantity_g,jar_size_g) VALUES (RANDOM_UUID(),'" + other + "','" + user + "',0,";
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "0)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "-5)"));
            assertEquals(1, sql.executeUpdate(insert + "500)"));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) = 'stock' AND LOWER(constraint_name) = 'ck_stock_jar_size'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        } finally { source.destroy(); }
    }

    @Test void existingCombinationsArePublishedAtTheirCreationAndNewOnesMayBeDrafts() throws Exception {
        var source = database("drafts");
        Flyway.configure().dataSource(source).target("20").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), combo = UUID.randomUUID(), draft = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + user + "','drafts@example.test','hash','Drafts')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at) VALUES ('" + combo + "','Layers','" + user + "',TIMESTAMP '2026-03-01 10:15:00')");
            Flyway.configure().dataSource(source).load().migrate();
            try (var row = sql.executeQuery("SELECT created_at, published_at FROM combination WHERE id = '" + combo + "'")) {
                assertTrue(row.next());
                var created = row.getTimestamp(1).toLocalDateTime();
                var published = row.getObject(2, java.time.OffsetDateTime.class);
                assertNotNull(published);
                assertEquals(created.atZone(java.time.ZoneId.systemDefault()).toInstant(), published.toInstant());
            }
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at) VALUES ('" + draft + "','','" + user + "',CURRENT_TIMESTAMP)");
            try (var row = sql.executeQuery("SELECT published_at FROM combination WHERE id = '" + draft + "'")) {
                assertTrue(row.next()); assertNull(row.getObject(1));
            }
            try (var column = columns(connection, "COMBINATION", "PUBLISHED_AT")) {
                assertTrue(column.next());
                assertEquals(java.sql.DatabaseMetaData.columnNullable, column.getInt("NULLABLE"));
            }
        } finally { source.destroy(); }
    }

    @Test void variationsRememberTheirSourceAndLoseItWhenTheSourceIsDeleted() throws Exception {
        var source = database("based-on");
        Flyway.configure().dataSource(source).target("21").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), original = UUID.randomUUID(), variation = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + user + "','based@example.test','hash','Based')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at,published_at) VALUES ('" + original + "','Original','" + user + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            Flyway.configure().dataSource(source).load().migrate();
            try (var row = sql.executeQuery("SELECT based_on_id FROM combination WHERE id = '" + original + "'")) {
                assertTrue(row.next()); assertNull(row.getObject(1));
            }
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at,based_on_id) VALUES ('" + variation + "','Variation','" + user + "',CURRENT_TIMESTAMP,'" + original + "')");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at,based_on_id)"
                    + " VALUES (RANDOM_UUID(),'Dangling','" + user + "',CURRENT_TIMESTAMP,RANDOM_UUID())"));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) = 'combination' AND LOWER(constraint_name) = 'fk_combination_based_on'")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
            assertEquals(1, sql.executeUpdate("DELETE FROM combination WHERE id = '" + original + "'"));
            try (var row = sql.executeQuery("SELECT based_on_id FROM combination WHERE id = '" + variation + "'")) {
                assertTrue(row.next()); assertNull(row.getObject(1));
            }
        } finally { source.destroy(); }
    }

    @Test void combinationsGainAnOptionalFinalFiringWithCheckedTemperatureAndAtmosphere() throws Exception {
        var source = database("firing");
        Flyway.configure().dataSource(source).target("22").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), combo = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + user + "','firing@example.test','hash','Firing')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at,published_at) VALUES ('" + combo + "','Layers','" + user + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            Flyway.configure().dataSource(source).load().migrate();
            try (var row = sql.executeQuery("SELECT title, cone, temperature_c, atmosphere, clay_body, kiln, fired_on FROM combination WHERE id = '" + combo + "'")) {
                assertTrue(row.next());
                assertEquals("Layers", row.getString(1));
                for (int column = 2; column <= 7; column++) assertNull(row.getObject(column));
            }
            String update = "UPDATE combination SET ";
            String where = " WHERE id = '" + combo + "'";
            assertEquals(1, sql.executeUpdate(update + "cone = '04', temperature_c = 1060, atmosphere = 'REDUCTION',"
                    + " clay_body = 'Buff', kiln = 'Gas', fired_on = DATE '2026-10-01'" + where));
            assertEquals(1, sql.executeUpdate(update + "temperature_c = 100" + where));
            assertEquals(1, sql.executeUpdate(update + "temperature_c = 1600" + where));
            assertThrows(SQLException.class, () -> sql.executeUpdate(update + "temperature_c = 99" + where));
            assertThrows(SQLException.class, () -> sql.executeUpdate(update + "temperature_c = 1601" + where));
            assertThrows(SQLException.class, () -> sql.executeUpdate(update + "atmosphere = 'SMOKY'" + where));
            assertThrows(SQLException.class, () -> sql.executeUpdate(update + "clay_body = '" + "c".repeat(121) + "'" + where));
            assertThrows(SQLException.class, () -> sql.executeUpdate(update + "kiln = '" + "k".repeat(81) + "'" + where));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) = 'combination' AND LOWER(constraint_name) IN"
                    + " ('ck_combination_temperature', 'ck_combination_atmosphere')")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
        } finally { source.destroy(); }
    }

    @Test void photoReportsFollowTheirPhotoKeepReportsOfDeletedReportersAndAreUniquePerReporter() throws Exception {
        var source = database("reports");
        Flyway.configure().dataSource(source).target("23").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), reporter = UUID.randomUUID(), combo = UUID.randomUUID(), photo = UUID.randomUUID(), other = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + user + "','uploader@example.test','hash','Uploader'),"
                    + " ('" + reporter + "','reporter@example.test','hash','Reporter')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at,published_at) VALUES ('" + combo + "','Layers','" + user + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate(imageInsert(photo, "'" + combo + "'", null, user));
            sql.executeUpdate(imageInsert(other, "'" + combo + "'", null, user));
            Flyway.configure().dataSource(source).load().migrate();
            String insert = "INSERT INTO image_report (id, image_id, reporter_id, reason, created_at) VALUES (RANDOM_UUID(), '";
            assertEquals(1, sql.executeUpdate(insert + photo + "','" + reporter + "','" + "r".repeat(500) + "',CURRENT_TIMESTAMP)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + photo + "','" + reporter + "',NULL,CURRENT_TIMESTAMP)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + other + "','" + reporter + "','" + "r".repeat(501) + "',CURRENT_TIMESTAMP)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + UUID.randomUUID() + "',NULL,NULL,CURRENT_TIMESTAMP)"));
            assertEquals(1, sql.executeUpdate(insert + other + "','" + reporter + "',NULL,CURRENT_TIMESTAMP)"));
            assertEquals(1, sql.executeUpdate("DELETE FROM app_user WHERE id = '" + reporter + "'"));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM image_report WHERE reporter_id IS NULL")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
            // Several deleted reporters may have reported the same photo.
            assertEquals(1, sql.executeUpdate(insert + photo + "',NULL,NULL,CURRENT_TIMESTAMP)"));
            assertEquals(1, sql.executeUpdate("DELETE FROM image WHERE id = '" + photo + "'"));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM image_report")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name)='image_report'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_image_report", "fk_image_report_image", "fk_image_report_reporter",
                    "uq_image_report_reporter")), constraints.toString());
        } finally { source.destroy(); }
    }

    @Test void theChangeLogNumbersItsRowsAndChecksKindAndAudience() throws Exception {
        var source = database("change-log");
        Flyway.configure().dataSource(source).target("27").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            Flyway.configure().dataSource(source).load().migrate();
            String insert = "INSERT INTO change_log (changed_at, kind, item_id, audience, audience_id) VALUES (CURRENT_TIMESTAMP, ";
            assertEquals(1, sql.executeUpdate(insert + "'GLAZE', RANDOM_UUID(), 'PUBLIC', NULL)"));
            assertEquals(1, sql.executeUpdate(insert + "'COMBINATION', RANDOM_UUID(), 'CLUB', RANDOM_UUID())"));
            assertEquals(1, sql.executeUpdate(insert + "'COMBINATION', RANDOM_UUID(), 'USER', RANDOM_UUID())"));
            for (String values : new String[] {"'STOCK', RANDOM_UUID(), 'PUBLIC', NULL", "'GLAZE', RANDOM_UUID(), 'EVERYONE', NULL",
                    "'GLAZE', RANDOM_UUID(), 'PUBLIC', RANDOM_UUID()", "'GLAZE', RANDOM_UUID(), 'CLUB', NULL",
                    "'GLAZE', RANDOM_UUID(), 'USER', NULL", "'GLAZE', NULL, 'PUBLIC', NULL"})
                assertThrows(SQLException.class, () -> sql.executeUpdate(insert + values + ")"), values);
            // The revision is assigned by the database, in insert order, and never by a client.
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO change_log (revision, changed_at, kind, item_id, audience)"
                    + " VALUES (99, CURRENT_TIMESTAMP, 'GLAZE', RANDOM_UUID(), 'PUBLIC')"));
            try (var rows = sql.executeQuery("SELECT revision FROM change_log ORDER BY revision")) {
                long previous = 0;
                while (rows.next()) { assertTrue(rows.getLong(1) > previous); previous = rows.getLong(1); }
                assertTrue(previous >= 3);
            }
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name)='change_log'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_change_log", "ck_change_log_kind", "ck_change_log_audience",
                    "ck_change_log_audience_id")), constraints.toString());
        } finally { source.destroy(); }
    }

    @Test void photosGainAnOptionalCaptionAndCheckedFiringDate() throws Exception {
        var source = database("photo-details");
        Flyway.configure().dataSource(source).target("24").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), combo = UUID.randomUUID(), photo = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + user + "','details@example.test','hash','Details')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at,published_at) VALUES ('" + combo + "','Layers','" + user + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate(imageInsert(photo, "'" + combo + "'", null, user));
            Flyway.configure().dataSource(source).load().migrate();
            try (var row = sql.executeQuery("SELECT caption, fired_on, kind FROM image WHERE id = '" + photo + "'")) {
                assertTrue(row.next());
                assertNull(row.getObject(1)); assertNull(row.getObject(2)); assertEquals("TEST_TILE", row.getString(3));
            }
            String update = "UPDATE image SET ";
            String where = " WHERE id = '" + photo + "'";
            assertEquals(1, sql.executeUpdate(update + "caption = '" + "c".repeat(200) + "', fired_on = DATE '1900-01-01'" + where));
            assertEquals(1, sql.executeUpdate(update + "fired_on = DATE '2100-12-31'" + where));
            assertEquals(1, sql.executeUpdate(update + "caption = NULL, fired_on = NULL" + where));
            assertThrows(SQLException.class, () -> sql.executeUpdate(update + "caption = '" + "c".repeat(201) + "'" + where));
            assertThrows(SQLException.class, () -> sql.executeUpdate(update + "fired_on = DATE '1899-12-31'" + where));
            assertThrows(SQLException.class, () -> sql.executeUpdate(update + "fired_on = DATE '2101-01-01'" + where));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) = 'image' AND LOWER(constraint_name) = 'ck_image_fired_on'")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
        } finally { source.destroy(); }
    }

    @Test void glazeCorrectionsFollowTheirGlazeKeepDeletedEditorsAndCheckFieldAndValues() throws Exception {
        var source = database("glaze-edits");
        Flyway.configure().dataSource(source).target("25").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID editor = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID(), other = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + editor + "','editor@example.test','hash','Editor')");
            sql.executeUpdate("INSERT INTO manufacturer (id,short_id,name,name_key) VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            sql.executeUpdate("INSERT INTO glaze (id,manufacturer_id,name,code) VALUES ('" + glaze + "','" + maker + "','Blue','PC-20'),"
                    + " ('" + other + "','" + maker + "','Red','PC-21')");
            Flyway.configure().dataSource(source).load().migrate();
            String insert = "INSERT INTO glaze_edit (id, glaze_id, editor_id, edited_at, field, old_value, new_value) VALUES (RANDOM_UUID(), '";
            assertEquals(1, sql.executeUpdate(insert + glaze + "','" + editor + "',CURRENT_TIMESTAMP,'NAME','Bleu','Blue')"));
            assertEquals(1, sql.executeUpdate(insert + glaze + "','" + editor + "',CURRENT_TIMESTAMP,'NOTES',NULL,'" + "n".repeat(1000) + "')"));
            assertEquals(1, sql.executeUpdate(insert + other + "','" + editor + "',CURRENT_TIMESTAMP,'CODE','PC-1','PC-21')"));
            for (String values : new String[]{"'MANUFACTURER','a','b'", "'NAME',NULL,'b'", "'CODE','a',NULL", "'NOTES','a','" + "n".repeat(1001) + "'"})
                assertThrows(SQLException.class, () -> sql.executeUpdate(insert + glaze + "','" + editor + "',CURRENT_TIMESTAMP," + values + ")"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + UUID.randomUUID() + "',NULL,CURRENT_TIMESTAMP,'NAME','a','b')"));
            assertEquals(1, sql.executeUpdate("DELETE FROM app_user WHERE id = '" + editor + "'"));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM glaze_edit WHERE editor_id IS NULL")) {
                assertTrue(rows.next()); assertEquals(3, rows.getInt(1));
            }
            assertEquals(1, sql.executeUpdate("DELETE FROM glaze WHERE id = '" + glaze + "'"));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM glaze_edit")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name)='glaze_edit'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_glaze_edit", "fk_glaze_edit_glaze", "fk_glaze_edit_editor",
                    "ck_glaze_edit_field", "ck_glaze_edit_values")), constraints.toString());
        } finally { source.destroy(); }
    }

    @Test void clubStockRemembersItsAdderUntilTheirAccountIsDeletedAndPersonalStockHasNone() throws Exception {
        var source = database("stock-added-by");
        Flyway.configure().dataSource(source).target("26").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID adder = UUID.randomUUID(), club = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID(), other = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + adder + "','adder@example.test','hash','Adder')");
            sql.executeUpdate("INSERT INTO club (id, name, owner_id) VALUES ('" + club + "','Kiln','" + adder + "')");
            sql.executeUpdate("INSERT INTO manufacturer (id,short_id,name,name_key) VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            sql.executeUpdate("INSERT INTO glaze (id,manufacturer_id,name,code) VALUES ('" + glaze + "','" + maker + "','Blue','PC-20'),"
                    + " ('" + other + "','" + maker + "','Red','PC-21')");
            sql.executeUpdate("INSERT INTO stock (id,glaze_id,club_id,quantity_g) VALUES (RANDOM_UUID(),'" + glaze + "','" + club + "',450)");
            Flyway.configure().dataSource(source).load().migrate();
            try (var rows = sql.executeQuery("SELECT added_by FROM stock")) {
                assertTrue(rows.next()); assertNull(rows.getObject(1));
            }
            sql.executeUpdate("UPDATE club SET owner_id = NULL");
            assertEquals(1, sql.executeUpdate("INSERT INTO stock (id,glaze_id,club_id,added_by,quantity_g) VALUES (RANDOM_UUID(),'"
                    + other + "','" + club + "','" + adder + "',100)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO stock (id,glaze_id,user_id,added_by,quantity_g) VALUES (RANDOM_UUID(),'"
                    + other + "','" + adder + "','" + adder + "',100)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE stock SET added_by = '" + UUID.randomUUID() + "'"));
            assertEquals(1, sql.executeUpdate("DELETE FROM app_user WHERE id = '" + adder + "'"));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM stock WHERE added_by IS NULL")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name)='stock'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("fk_stock_added_by", "ck_stock_added_by")), constraints.toString());
        } finally { source.destroy(); }
    }

    @Test void buddiesArePairsStoredOnceWithTheirAccountsAndStockMayBeKeptPrivateOnlyWhenPersonal() throws Exception {
        var source = database("buddies");
        Flyway.configure().dataSource(source).target("29").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            // Two IDs that UUID.compareTo orders the other way round than the database does (signed first half).
            UUID low = UUID.fromString("10000000-0000-4000-8000-000000000000"), high = UUID.fromString("f0000000-0000-4000-8000-000000000000");
            UUID third = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID(), club = UUID.randomUUID();
            assertTrue(high.compareTo(low) < 0);
            assertEquals(low, eu.glazelab.api.model.BuddyId.of(high, low).getUserA());
            for (UUID id : new UUID[] {low, high, third})
                sql.executeUpdate("INSERT INTO app_user (id, email, password_hash, display_name) VALUES ('" + id + "','" + id + "@example.test','hash','B')");
            sql.executeUpdate("INSERT INTO manufacturer (id,short_id,name,name_key) VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            sql.executeUpdate("INSERT INTO glaze (id,manufacturer_id,name,code) VALUES ('" + glaze + "','" + maker + "','Blue','PC-1')");
            sql.executeUpdate("INSERT INTO club (id,name,owner_id) VALUES ('" + club + "','Club','" + low + "')");
            sql.executeUpdate("INSERT INTO stock (id,glaze_id,user_id,quantity_g) VALUES (RANDOM_UUID(),'" + glaze + "','" + low + "',450)");
            Flyway.configure().dataSource(source).load().migrate();
            try (var rows = sql.executeQuery("SELECT keep_private FROM stock")) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1));
            }
            assertEquals(1, sql.executeUpdate("UPDATE stock SET keep_private = TRUE"));
            String clubStock = "INSERT INTO stock (id,glaze_id,club_id,quantity_g,keep_private) VALUES (RANDOM_UUID(),'" + glaze + "','" + club + "',10,";
            assertThrows(SQLException.class, () -> sql.executeUpdate(clubStock + "TRUE)"));
            assertEquals(1, sql.executeUpdate(clubStock + "FALSE)"));

            String insert = "INSERT INTO buddy (user_a,user_b,status,requested_by,created_at,accepted_at) VALUES (";
            String now = "CURRENT_TIMESTAMP";
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "'" + high + "','" + low + "','PENDING','" + high + "'," + now + ",NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "'" + low + "','" + low + "','PENDING','" + low + "'," + now + ",NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "'" + low + "','" + high + "','BLOCKED','" + low + "'," + now + ",NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "'" + low + "','" + high + "','PENDING','" + third + "'," + now + ",NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "'" + low + "','" + high + "','PENDING','" + low + "'," + now + "," + now + ")"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "'" + low + "','" + high + "','ACCEPTED','" + low + "'," + now + ",NULL)"));
            assertEquals(1, sql.executeUpdate(insert + "'" + low + "','" + high + "','ACCEPTED','" + high + "'," + now + "," + now + ")"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "'" + low + "','" + high + "','PENDING','" + low + "'," + now + ",NULL)"));
            sql.executeUpdate("INSERT INTO buddy_link (user_id, token_hash, created_at) VALUES ('" + high + "','" + "a".repeat(64) + "'," + now + ")");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO buddy_link (user_id, token_hash, created_at) VALUES ('" + low + "','" + "a".repeat(64) + "'," + now + ")"));
            // Either account takes its pairs and its link along.
            sql.executeUpdate("DELETE FROM app_user WHERE id = '" + high + "'");
            try (var rows = sql.executeQuery("SELECT (SELECT COUNT(*) FROM buddy) + (SELECT COUNT(*) FROM buddy_link)")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) IN ('buddy', 'buddy_link', 'stock')")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_buddy", "ck_buddy_order", "ck_buddy_status", "ck_buddy_requested_by",
                    "ck_buddy_accepted_at", "fk_buddy_user_a", "fk_buddy_user_b", "pk_buddy_link", "uq_buddy_link_token",
                    "fk_buddy_link_user", "ck_stock_keep_private")), constraints.toString());
        } finally { source.destroy(); }
    }

    @Test void profilePicturesBelongToOneAccountAndTheirReportsEvidenceAndBlocksOutliveThem() throws Exception {
        var source = database("avatars");
        Flyway.configure().dataSource(source).target("30").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID owner = UUID.randomUUID(), reporter = UUID.randomUUID(), admin = UUID.randomUUID();
            for (UUID user : new UUID[] {owner, reporter, admin})
                sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + user + "','" + user + "@example.test','hash','Pictured')");
            Flyway.configure().dataSource(source).target("31").load().migrate();
            UUID avatar = UUID.randomUUID();
            String hashes = "'" + "a".repeat(64) + "','" + "b".repeat(64) + "',42,CURRENT_TIMESTAMP)";
            String insert = "INSERT INTO user_avatar (id,user_id,storage_key,upload_sha256,stored_sha256,dhash,created_at) VALUES (";
            assertEquals(1, sql.executeUpdate(insert + "'" + avatar + "','" + owner + "','" + avatar + ".jpg'," + hashes));
            // One picture per account, one row per stored key, only for an existing account, always with its hashes.
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "RANDOM_UUID(),'" + owner + "','other.jpg'," + hashes));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "RANDOM_UUID(),'" + reporter + "','" + avatar + ".jpg'," + hashes));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "RANDOM_UUID(),'" + UUID.randomUUID() + "','x.jpg'," + hashes));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO user_avatar (id,user_id,storage_key,created_at) VALUES (RANDOM_UUID(),'" + reporter + "','y.jpg',CURRENT_TIMESTAMP)"));
            String report = "INSERT INTO avatar_report (id,avatar_id,owner_id,reporter_id,reason,created_at,status,closed_at,closed_by) VALUES (RANDOM_UUID(),'" + avatar + "','" + owner + "',";
            assertEquals(1, sql.executeUpdate(report + "'" + reporter + "','Someone else',CURRENT_TIMESTAMP,'OPEN',NULL,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "'" + reporter + "',NULL,CURRENT_TIMESTAMP,'OPEN',NULL,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "NULL,'" + "x".repeat(501) + "',CURRENT_TIMESTAMP,'OPEN',NULL,NULL)"));
            // The status names its closing: open has none, a decision has its time (the admin may be gone).
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "NULL,NULL,CURRENT_TIMESTAMP,'CLOSED',CURRENT_TIMESTAMP,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "NULL,NULL,CURRENT_TIMESTAMP,'OPEN',CURRENT_TIMESTAMP,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "NULL,NULL,CURRENT_TIMESTAMP,'DISMISSED',NULL,NULL)"));
            assertEquals(1, sql.executeUpdate(report + "NULL,NULL,CURRENT_TIMESTAMP,'ACTIONED',CURRENT_TIMESTAMP,'" + admin + "')"));
            assertEquals(1, sql.executeUpdate(report.replace("'" + owner + "'", "NULL").replace("'" + avatar + "'", "RANDOM_UUID()")
                    + "NULL,NULL,CURRENT_TIMESTAMP,'DISMISSED',CURRENT_TIMESTAMP,NULL)"), "a report outlives its picture");
            String evidence = "INSERT INTO avatar_evidence (avatar_id,storage_key,outcome,upload_sha256,stored_sha256,dhash,removed_at) VALUES (RANDOM_UUID(),";
            assertEquals(1, sql.executeUpdate(evidence + "'kept.jpg','REPLACED'," + hashes));
            assertEquals(1, sql.executeUpdate(evidence + "NULL,'ACCOUNT_DELETED'," + hashes));
            assertThrows(SQLException.class, () -> sql.executeUpdate(evidence + "'kept.jpg','OWNER_REMOVED'," + hashes));
            assertThrows(SQLException.class, () -> sql.executeUpdate(evidence + "NULL,'CURRENT'," + hashes));
            assertEquals(1, sql.executeUpdate("INSERT INTO avatar_block (id,upload_sha256,stored_sha256,dhash,created_at) VALUES (RANDOM_UUID()," + hashes));
            // Deleted accounts leave the reports without them; the owner takes only the picture row along.
            sql.executeUpdate("DELETE FROM app_user WHERE id = '" + reporter + "'");
            sql.executeUpdate("DELETE FROM app_user WHERE id = '" + admin + "'");
            sql.executeUpdate("DELETE FROM app_user WHERE id = '" + owner + "'");
            assertEquals(0, count(sql, "SELECT COUNT(*) FROM user_avatar"));
            assertEquals(3, count(sql, "SELECT COUNT(*) FROM avatar_report WHERE owner_id IS NULL AND reporter_id IS NULL AND closed_by IS NULL"));
            assertEquals(2, count(sql, "SELECT COUNT(*) FROM avatar_evidence"));
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM avatar_block"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) IN ('user_avatar', 'avatar_report', 'avatar_evidence', 'avatar_block')")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_user_avatar", "uq_user_avatar_user", "uq_user_avatar_key", "fk_user_avatar_user",
                    "pk_avatar_report", "ck_avatar_report_status", "ck_avatar_report_closed", "fk_avatar_report_owner",
                    "fk_avatar_report_reporter", "fk_avatar_report_closed_by", "uq_avatar_report_reporter", "pk_avatar_evidence",
                    "uq_avatar_evidence_key", "ck_avatar_evidence_outcome", "pk_avatar_block")), constraints.toString());
            assertFalse(constraints.contains("fk_avatar_report_avatar"), "no cascade from the picture");
            try (var rows = sql.executeQuery("SELECT LOWER(table_name) || '.' || LOWER(column_name), UPPER(data_type) FROM information_schema.columns"
                    + " WHERE LOWER(table_name) IN ('user_avatar', 'avatar_report', 'avatar_evidence', 'avatar_block')"
                    + " AND LOWER(column_name) IN ('id', 'user_id', 'avatar_id', 'owner_id', 'reporter_id', 'closed_by')")) {
                int columns = 0;
                for (; rows.next(); columns++) assertEquals("UUID", rows.getString(2), rows.getString(1));
                assertEquals(9, columns);
            }
        } finally { source.destroy(); }
    }

    @Test void bansKeepOnlyAnAddressHashAndOutliveTheirAdminAndMembershipsGainAJoinTime() throws Exception {
        var source = database("bans");
        Flyway.configure().dataSource(source).target("30").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID admin = UUID.randomUUID(), member = UUID.randomUUID(), club = UUID.randomUUID();
            for (UUID user : new UUID[] {admin, member})
                sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + user + "','" + user + "@example.test','hash','Banner')");
            sql.executeUpdate("INSERT INTO club (id,name,owner_id) VALUES ('" + club + "','Old club','" + member + "')");
            sql.executeUpdate("INSERT INTO membership (club_id,user_id,role) VALUES ('" + club + "','" + member + "','ADMIN')");
            Flyway.configure().dataSource(source).target("31").load().migrate();
            // An existing membership gets the migration time; a new one without a time gets the insert time.
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM membership WHERE joined_at IS NOT NULL"));
            sql.executeUpdate("INSERT INTO membership (club_id,user_id,role) VALUES ('" + club + "','" + admin + "','MEMBER')");
            assertEquals(2, count(sql, "SELECT COUNT(*) FROM membership WHERE joined_at IS NOT NULL"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE membership SET joined_at = NULL"));
            String hash = "'" + "c".repeat(64) + "'";
            String block = "INSERT INTO email_block (id,email_hash,display_name,reason,banned_at,banned_by) VALUES (RANDOM_UUID(),";
            assertEquals(1, sql.executeUpdate(block + hash + ",'Banned','Spam',CURRENT_TIMESTAMP,'" + admin + "')"));
            // One entry per hash (the lookup's unique index), a reason, and no address column at all.
            assertThrows(SQLException.class, () -> sql.executeUpdate(block + hash + ",'Again','Spam',CURRENT_TIMESTAMP,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(block + "'" + "d".repeat(64) + "','No reason',NULL,CURRENT_TIMESTAMP,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(block + "'" + "e".repeat(64) + "','Long','" + "x".repeat(501) + "',CURRENT_TIMESTAMP,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(block + "'" + "f".repeat(64) + "','Ghost','Spam',CURRENT_TIMESTAMP,RANDOM_UUID())"));
            String action = "INSERT INTO admin_action (id,created_at,actor_id,action,target_user_id,target_id,reason) VALUES (RANDOM_UUID(),CURRENT_TIMESTAMP,'" + admin + "',";
            for (String type : new String[] {"BAN", "UNBAN", "AVATAR_REMOVED", "CLUB_ADMIN_PROMOTED"})
                assertEquals(1, sql.executeUpdate(action + "'" + type + "',RANDOM_UUID(),RANDOM_UUID(),NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(action + "'DELETE_EVERYTHING',NULL,NULL,NULL)"));
            // Targets have no foreign key; the admin's deletion keeps every entry and log row without the admin.
            sql.executeUpdate("DELETE FROM membership WHERE user_id = '" + admin + "'");
            sql.executeUpdate("DELETE FROM app_user WHERE id = '" + admin + "'");
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM email_block WHERE banned_by IS NULL"));
            assertEquals(4, count(sql, "SELECT COUNT(*) FROM admin_action WHERE actor_id IS NULL"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) IN ('email_block', 'admin_action')")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_email_block", "uq_email_block_hash", "fk_email_block_banned_by",
                    "pk_admin_action", "ck_admin_action_action", "fk_admin_action_actor")), constraints.toString());
            try (var rows = sql.executeQuery("SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = 'email_block'")) {
                Set<String> columns = new HashSet<>();
                while (rows.next()) columns.add(rows.getString(1));
                assertEquals(Set.of("id", "email_hash", "display_name", "reason", "banned_at", "banned_by"), columns);
            }
        } finally { source.destroy(); }
    }

    /** V33: existing accounts get no registration time (nothing reliable to derive it from); a later one may be set. */
    @Test void accountCreationTimeIsEmptyForExistingAccounts() throws Exception {
        var source = database("account-created");
        Flyway.configure().dataSource(source).target("32").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID existing = UUID.randomUUID(), other = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name,accepted_terms_version,terms_accepted_at)"
                    + " VALUES ('" + existing + "','old@example.test','hash','Old','2026-10-01',CURRENT_TIMESTAMP)");
            Flyway.configure().dataSource(source).load().migrate();
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM app_user WHERE created_at IS NULL"), "no invented date, not even the terms time");
            sql.executeUpdate("INSERT INTO app_user (id,email,email_key,password_hash,display_name,created_at) VALUES ('" + other
                    + "','new@example.test','new@example.test','hash','New',CURRENT_TIMESTAMP)");
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM app_user WHERE created_at IS NOT NULL"));
            assertEquals(2, count(sql, "SELECT COUNT(*) FROM app_user"));
        } finally { source.destroy(); }
    }

    /** V34: existing accounts get both feed sources on and nothing seen; stock gets a creation time, NULL for existing rows. */
    @Test void activityFeedColumnsStartOnAndEmpty() throws Exception {
        var source = database("activity-feed");
        Flyway.configure().dataSource(source).target("33").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID existing = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + existing + "','old@example.test','hash','Old')");
            Flyway.configure().dataSource(source).load().migrate();
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM app_user WHERE feed_buddies = TRUE AND feed_clubs = TRUE AND feed_seen_at IS NULL"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE app_user SET feed_clubs = NULL"));
            Set<String> columns = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(column_name), is_nullable FROM information_schema.columns WHERE LOWER(table_name) = 'stock'")) {
                while (rows.next()) columns.add(rows.getString(1) + ":" + rows.getString(2));
            }
            assertTrue(columns.contains("created_at:YES"), columns.toString());
            Set<String> indexes = new HashSet<>();
            for (String table : List.of("stock", "combination", "membership")) indexes.addAll(indexes(connection, table));
            assertTrue(indexes.containsAll(Set.of("stock_user_created_idx", "stock_club_created_idx", "combination_author_published_idx", "membership_user_idx")), indexes.toString());
        } finally { source.destroy(); }
    }

    /** V35: feedback is checked (status with its done time, device, photo index), and goes with its account and its photos with it. */
    @Test void feedbackIsCheckedAndGoesWithItsAccount() throws Exception {
        var source = database("feedback");
        Flyway.configure().dataSource(source).target("34").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID sender = UUID.randomUUID(), other = UUID.randomUUID(), feedback = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + sender + "','s@example.test','hash','Sender')");
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + other + "','o@example.test','hash','Other')");
            Flyway.configure().dataSource(source).load().migrate();
            String insert = "INSERT INTO feedback (id,user_id,message,device,created_at,status,done_at) VALUES (";
            assertEquals(1, sql.executeUpdate(insert + "'" + feedback + "','" + sender + "','Hello','PHONE',CURRENT_TIMESTAMP,'OPEN',NULL)"));
            assertEquals(1, sql.executeUpdate(insert + "RANDOM_UUID(),'" + other + "','Done',NULL,CURRENT_TIMESTAMP,'DONE',CURRENT_TIMESTAMP)"));
            // A done feedback has its time and an open one none; devices and states are checked; a feedback names an existing account.
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "RANDOM_UUID(),'" + sender + "','x',NULL,CURRENT_TIMESTAMP,'DONE',NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "RANDOM_UUID(),'" + sender + "','x',NULL,CURRENT_TIMESTAMP,'OPEN',CURRENT_TIMESTAMP)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "RANDOM_UUID(),'" + sender + "','x',NULL,CURRENT_TIMESTAMP,'NEW',NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "RANDOM_UUID(),'" + sender + "','x','WATCH',CURRENT_TIMESTAMP,'OPEN',NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "RANDOM_UUID(),RANDOM_UUID(),'x',NULL,CURRENT_TIMESTAMP,'OPEN',NULL)"));
            String photo = "INSERT INTO feedback_photo (id,feedback_id,photo_index,storage_key,width,height) VALUES (RANDOM_UUID(),'" + feedback + "',";
            for (int i = 0; i < 3; i++) assertEquals(1, sql.executeUpdate(photo + i + ",'" + UUID.randomUUID() + ".jpg',10,10)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(photo + "3,'" + UUID.randomUUID() + ".jpg',10,10)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(photo + "0,'" + UUID.randomUUID() + ".jpg',10,10)"));
            // Deleting the account deletes its feedback and the photo rows; nobody else's.
            sql.executeUpdate("DELETE FROM app_user WHERE id = '" + sender + "'");
            assertEquals(0, count(sql, "SELECT COUNT(*) FROM feedback_photo"));
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM feedback"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) IN ('feedback', 'feedback_photo')")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_feedback", "ck_feedback_status", "ck_feedback_done", "ck_feedback_device",
                    "fk_feedback_user", "pk_feedback_photo", "uq_feedback_photo_key", "uq_feedback_photo_index", "ck_feedback_photo_index",
                    "fk_feedback_photo_feedback")), constraints.toString());
            Set<String> indexes = new HashSet<>();
            indexes.addAll(indexes(connection, "feedback"));
            assertTrue(indexes.containsAll(Set.of("feedback_time_idx", "feedback_user_idx", "feedback_done_idx")), indexes.toString());
        } finally { source.destroy(); }
    }

    @Test void errorReportsAreOffForEveryAccountAndGoWithTheirAccount() throws Exception {
        var source = database("client-reports");
        Flyway.configure().dataSource(source).target("31").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID existing = UUID.randomUUID(), other = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + existing + "','old@example.test','hash','Old')");
            Flyway.configure().dataSource(source).load().migrate();
            // Existing and new accounts start with error reports off.
            sql.executeUpdate("INSERT INTO app_user (id,email,email_key,password_hash,display_name) VALUES ('" + other + "','new@example.test','new@example.test','hash','New')");
            assertEquals(2, count(sql, "SELECT COUNT(*) FROM app_user WHERE diagnostics_opt_in = FALSE"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE app_user SET diagnostics_opt_in = NULL"));
            String report = "INSERT INTO client_report (id,user_id,received_at,occurred_at,kind,fingerprint,message,occurrences,build_label,pointer,service_worker,color_scheme)"
                    + " VALUES (RANDOM_UUID(),";
            String now = "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,";
            for (UUID user : new UUID[] {existing, existing, other})
                assertEquals(1, sql.executeUpdate(report + "'" + user + "'," + now + "'ERROR','f','Boom',1,'1.0','COARSE','ACTIVE','DARK')"));
            for (String kind : new String[] {"WARNING", "UNHANDLED_REJECTION"})
                assertEquals(1, sql.executeUpdate(report + "'" + other + "'," + now + "'" + kind + "','f','Boom',1000,'1.0',NULL,NULL,NULL)"));
            // Checked kinds, counts and device enums; a report always names an existing account.
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "'" + other + "'," + now + "'FATAL','f','Boom',1,'1.0',NULL,NULL,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "'" + other + "'," + now + "'ERROR','f','Boom',0,'1.0',NULL,NULL,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "'" + other + "'," + now + "'ERROR','f','Boom',1001,'1.0',NULL,NULL,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "'" + other + "'," + now + "'ERROR','f','Boom',1,'1.0','PEN',NULL,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "'" + other + "'," + now + "'ERROR','f','Boom',1,'1.0',NULL,'WAITING',NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "'" + other + "'," + now + "'ERROR','f','Boom',1,'1.0',NULL,NULL,'SEPIA')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(report + "RANDOM_UUID()," + now + "'ERROR','f','Boom',1,'1.0',NULL,NULL,NULL)"));
            // Deleting an account deletes its reports and nobody else's.
            sql.executeUpdate("DELETE FROM app_user WHERE id = '" + existing + "'");
            assertEquals(0, count(sql, "SELECT COUNT(*) FROM client_report WHERE user_id = '" + existing + "'"));
            assertEquals(3, count(sql, "SELECT COUNT(*) FROM client_report"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) = 'client_report'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_client_report", "ck_client_report_kind", "ck_client_report_occurrences",
                    "ck_client_report_pointer", "ck_client_report_service_worker", "ck_client_report_color_scheme",
                    "fk_client_report_user")), constraints.toString());
            Set<String> indexes = new HashSet<>();
            indexes.addAll(indexes(connection, "client_report"));
            assertTrue(indexes.containsAll(Set.of("client_report_time_idx", "client_report_user_idx", "client_report_fingerprint_idx")), indexes.toString());
        } finally { source.destroy(); }
    }
    /**
     * 34-7 (V32): a ban entry keeps only the pseudonymous fingerprint, the date and the reason; reports about a picture
     * of a deleted account (owner nulled) lose their text, open ones are CLOSED; other reports keep theirs.
     */
    @Test void banEntriesLoseNameAndAdminAndReportsAboutDeletedAccountsLoseTheirText() throws Exception {
        var source = database("minimised");
        Flyway.configure().dataSource(source).target("31").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID admin = UUID.randomUUID(), owner = UUID.randomUUID(), gone = UUID.randomUUID(), kept = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + admin + "','a@example.test','hash','Admin')");
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + owner + "','o@example.test','hash','Owner')");
            sql.executeUpdate("INSERT INTO email_block (id,email_hash,display_name,reason,banned_at,banned_by) VALUES (RANDOM_UUID(),'"
                    + "c".repeat(64) + "','Banned Person','Spam',CURRENT_TIMESTAMP,'" + admin + "')");
            String report = "INSERT INTO avatar_report (id,avatar_id,owner_id,reporter_id,reason,created_at,status,closed_at,closed_by) VALUES (RANDOM_UUID(),";
            sql.executeUpdate(report + "'" + gone + "',NULL,NULL,'Shows my neighbour Ann',CURRENT_TIMESTAMP,'OPEN',NULL,NULL)");
            sql.executeUpdate(report + "'" + gone + "',NULL,NULL,'Ann again',CURRENT_TIMESTAMP,'DISMISSED',CURRENT_TIMESTAMP,NULL)");
            sql.executeUpdate(report + "'" + kept + "','" + owner + "',NULL,'Rude',CURRENT_TIMESTAMP,'OPEN',NULL,NULL)");
            Flyway.configure().dataSource(source).load().migrate();
            try (var rows = sql.executeQuery("SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = 'email_block'")) {
                Set<String> columns = new HashSet<>();
                while (rows.next()) columns.add(rows.getString(1));
                assertEquals(Set.of("id", "email_hash", "reason", "banned_at"), columns);
            }
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM email_block WHERE reason = 'Spam' AND banned_at IS NOT NULL"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + ""
                    + " WHERE LOWER(table_name) IN ('email_block', 'avatar_report')")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertFalse(constraints.contains("fk_email_block_banned_by"), constraints.toString());
            assertTrue(constraints.containsAll(Set.of("pk_email_block", "uq_email_block_hash", "ck_avatar_report_status")), constraints.toString());
            assertEquals(0, count(sql, "SELECT COUNT(*) FROM avatar_report WHERE avatar_id = '" + gone + "' AND reason IS NOT NULL"));
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM avatar_report WHERE avatar_id = '" + gone + "' AND status = 'CLOSED' AND closed_at IS NOT NULL"));
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM avatar_report WHERE avatar_id = '" + gone + "' AND status = 'DISMISSED'"));
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM avatar_report WHERE avatar_id = '" + kept + "' AND status = 'OPEN' AND reason = 'Rude'"));
            // CLOSED is a closed state (it needs its time); other values stay refused.
            String fresh = report + "RANDOM_UUID(),NULL,NULL,NULL,CURRENT_TIMESTAMP,";
            assertEquals(1, sql.executeUpdate(fresh + "'CLOSED',CURRENT_TIMESTAMP,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(fresh + "'CLOSED',NULL,NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(fresh + "'REOPENED',CURRENT_TIMESTAMP,NULL)"));
        } finally { source.destroy(); }
    }

    /**
     * V36: the four server-local times become instants read in the zone that wrote them, the API JVM's default zone,
     * which the migrating session uses on H2 and PostgreSQL alike (run with -Duser.timezone=Pacific/Auckland to check a
     * non-UTC server); NOT NULL and the time indexes stay.
     */
    @Test void serverLocalTimesBecomeInstantsInTheServersZone() throws Exception {
        var source = database("instants");
        Flyway.configure().dataSource(source).target("35").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), combo = UUID.randomUUID(), photo = UUID.randomUUID(), report = UUID.randomUUID(), log = UUID.randomUUID();
            String wall = "'2026-07-10 07:00:00.123456'";
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + user + "','instants@example.test','hash','Times')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at,published_at) VALUES ('" + combo + "','Layers','" + user + "',"
                    + wall + ",CURRENT_TIMESTAMP)");
            sql.executeUpdate(imageInsert(photo, "'" + combo + "'", null, user).replace("CURRENT_TIMESTAMP)", wall + ")"));
            sql.executeUpdate("INSERT INTO image_report (id,image_id,reporter_id,reason,created_at) VALUES ('" + report + "','" + photo + "',NULL,NULL," + wall + ")");
            sql.executeUpdate("INSERT INTO image_deletion (id,image_id,glaze_id,combination_id,title,visibility,uploader_id,deleted_by,deleted_at)"
                    + " VALUES ('" + log + "',RANDOM_UUID(),NULL,'" + combo + "','Layers','PUBLIC',NULL,NULL," + wall + ")");
            Flyway.configure().dataSource(source).load().migrate();
            var written = java.time.LocalDateTime.of(2026, 7, 10, 7, 0, 0, 123_456_000).atZone(java.time.ZoneId.systemDefault()).toInstant();
            for (String[] column : List.of(new String[]{"combination", "created_at"}, new String[]{"image", "created_at"},
                    new String[]{"image_report", "created_at"}, new String[]{"image_deletion", "deleted_at"})) {
                try (var rows = sql.executeQuery("SELECT " + column[1] + " FROM " + column[0])) {
                    assertTrue(rows.next());
                    assertEquals(written, rows.getObject(1, java.time.OffsetDateTime.class).toInstant(), column[0]);
                }
                try (var meta = columns(connection, column[0].toUpperCase(Locale.ROOT), column[1].toUpperCase(Locale.ROOT))) {
                    assertTrue(meta.next());
                    assertEquals(java.sql.Types.TIMESTAMP_WITH_TIMEZONE, timestampType(meta), column[0] + "." + column[1]);
                    assertEquals("NO", meta.getString("IS_NULLABLE"), column[0] + "." + column[1]);
                }
            }
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE image_report SET created_at = NULL"));
            assertTrue(indexes(connection, "image_report").contains("image_report_time_idx"));
            assertTrue(indexes(connection, "image_deletion").contains("image_deletion_time_idx"));
            // A new row stores the instant it is given, whatever the session's zone.
            sql.executeUpdate("UPDATE combination SET created_at = TIMESTAMP WITH TIME ZONE '2026-11-10 07:00:00+00'");
            try (var rows = sql.executeQuery("SELECT created_at FROM combination")) {
                assertTrue(rows.next());
                assertEquals(java.time.Instant.parse("2026-11-10T07:00:00Z"), rows.getObject(1, java.time.OffsetDateTime.class).toInstant());
            }
        } finally { source.destroy(); }
    }

    /**
     * V37: every account gets the lookup form of its address (trimmed, lower case, a long s or a dotless i folded to the
     * plain letter), required and unique under {@code uq_app_user_email_key}; the address itself stays as stored.
     */
    @Test void accountsGetTheirUniqueLookupKey() throws Exception {
        var source = database("email-key");
        Flyway.configure().dataSource(source).target("36").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID mixed = UUID.randomUUID(), folded = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + mixed + "','Potter@Example.TEST','hash','Mixed')");
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + folded + "','jeſſ.ıvy@example.test','hash','Folded')");
            Flyway.configure().dataSource(source).load().migrate();
            for (String[] row : List.of(new String[]{mixed.toString(), "Potter@Example.TEST", "potter@example.test"},
                    new String[]{folded.toString(), "jeſſ.ıvy@example.test", "jess.ivy@example.test"})) {
                try (var rows = sql.executeQuery("SELECT email,email_key FROM app_user WHERE id = '" + row[0] + "'")) {
                    assertTrue(rows.next());
                    assertEquals(row[1], rows.getString(1));
                    assertEquals(row[2], rows.getString(2));
                }
            }
            try (var column = columns(connection, "APP_USER", "EMAIL_KEY")) {
                assertTrue(column.next());
                assertEquals(java.sql.DatabaseMetaData.columnNoNulls, column.getInt("NULLABLE"));
            }
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name) = 'app_user'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.contains("uq_app_user_email_key"), constraints.toString());
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO app_user (id,email,email_key,password_hash,display_name)"
                    + " VALUES (RANDOM_UUID(),'jess.ivy@example.test','jess.ivy@example.test','hash','Twin')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name)"
                    + " VALUES (RANDOM_UUID(),'keyless@example.test','hash','Keyless')"));
        } finally { source.destroy(); }
    }

    /**
     * V37 stops before changing anything when two accounts' addresses differ only in letter case (or a folded letter),
     * naming the accounts by ID, never by address; once the operator has resolved them, the next start migrates.
     */
    @Test void theLookupKeyMigrationStopsOnAddressesThatDifferOnlyInCase() throws Exception {
        var source = database("email-key-clash");
        Flyway.configure().dataSource(source).target("36").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID lower = UUID.randomUUID(), upper = UUID.randomUUID(), plain = UUID.randomUUID(), longS = UUID.randomUUID(), solo = UUID.randomUUID();
            String insert = "INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('";
            sql.executeUpdate(insert + lower + "','twin@example.test','hash','Twin')");
            sql.executeUpdate(insert + upper + "','TWIN@example.test','hash','Twin')");
            sql.executeUpdate(insert + plain + "','jess@example.test','hash','Jess')");
            sql.executeUpdate(insert + longS + "','jeſſ@example.test','hash','Jess')");
            sql.executeUpdate(insert + solo + "','solo@example.test','hash','Solo')");
            var failed = assertThrows(FlywayException.class, () -> Flyway.configure().dataSource(source).load().migrate());
            var chain = new StringBuilder();
            for (Throwable t = failed; t != null; t = t.getCause()) chain.append(t.getMessage()).append(' ');
            String message = chain.toString();
            assertTrue(message.contains("2 group(s) of accounts"), message);
            for (UUID id : List.of(lower, upper, plain, longS)) assertTrue(message.contains(id.toString()), message);
            assertFalse(message.contains(solo.toString()), message);
            for (String address : List.of("twin", "TWIN", "jess", "jeſſ", "example.test")) assertFalse(message.contains(address), message);
            try (var column = columns(connection, "APP_USER", "EMAIL_KEY")) { assertFalse(column.next(), "nothing changed"); }
            assertEquals(5, count(sql, "SELECT COUNT(*) FROM app_user"));
            // The operator's pre-check query (backend guide "Schema") finds the same two groups.
            assertEquals(2, count(sql, "SELECT COUNT(*) FROM (" + EMAIL_KEY_PRECHECK + ") clashes"));
            sql.executeUpdate("DELETE FROM app_user WHERE id IN ('" + upper + "','" + longS + "')");
            assertEquals(0, count(sql, "SELECT COUNT(*) FROM (" + EMAIL_KEY_PRECHECK + ") clashes"));
            Flyway.configure().dataSource(source).load().repair();
            Flyway.configure().dataSource(source).load().migrate();
            assertEquals(3, count(sql, "SELECT COUNT(*) FROM app_user WHERE email_key IS NOT NULL"));
        } finally { source.destroy(); }
    }

    /** The operator's pre-check before V37, as printed in the backend guide "Schema": one row per clashing address group. */
    private static final String EMAIL_KEY_PRECHECK = "SELECT LOWER(UPPER(LOWER(TRIM(email)))) AS address, COUNT(*) AS accounts"
            + " FROM app_user GROUP BY LOWER(UPPER(LOWER(TRIM(email)))) HAVING COUNT(*) > 1";

    /** V41: a photo's focus point, both coordinates or neither, each 0 to 100; existing photos keep none (the centre). */
    @Test void photoFocusPointsAreBothOrNeitherWithinPercent() throws Exception {
        var source = database("image-focus");
        Flyway.configure().dataSource(source).target("39").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), combo = UUID.randomUUID(), tile = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,email_key,password_hash,display_name) VALUES ('" + user
                    + "','focus@example.test','focus@example.test','hash','Maker')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at,published_at) VALUES ('" + combo + "','Layers','" + user
                    + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate(imageInsert(tile, "'" + combo + "'", null, user));
            Flyway.configure().dataSource(source).load().migrate();
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM image WHERE id = '" + tile + "' AND focus_x IS NULL AND focus_y IS NULL"));
            String update = "UPDATE image SET ";
            String where = " WHERE id = '" + tile + "'";
            for (String accepted : new String[] {"focus_x = 0, focus_y = 100", "focus_x = 100, focus_y = 0", "focus_x = 37, focus_y = 62",
                    "focus_x = NULL, focus_y = NULL"})
                assertEquals(1, sql.executeUpdate(update + accepted + where), accepted);
            for (String refused : new String[] {"focus_x = 50", "focus_y = 50", "focus_x = -1, focus_y = 50", "focus_x = 50, focus_y = 101",
                    "focus_x = 101, focus_y = 101"})
                assertThrows(SQLException.class, () -> sql.executeUpdate(update + refused + where), refused);
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM image WHERE id = '" + tile + "' AND focus_x IS NULL AND focus_y IS NULL"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name)='image'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("ck_image_focus", "ck_image_kind", "ck_image_fired_on")), constraints.toString());
        } finally { source.destroy(); }
    }

    /** V39: the admin log gains a title correction that always keeps both titles; earlier rows keep none. */
    @Test void adminTitleCorrectionsKeepBothTitlesAndOtherActionsNone() throws Exception {
        var source = database("title-corrections");
        Flyway.configure().dataSource(source).target("36").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            String action = "INSERT INTO admin_action (id,created_at,actor_id,action,target_user_id,target_id,reason) VALUES (RANDOM_UUID(),CURRENT_TIMESTAMP,NULL,";
            assertEquals(1, sql.executeUpdate(action + "'BAN',RANDOM_UUID(),RANDOM_UUID(),'Spam')"));
            Flyway.configure().dataSource(source).load().migrate();
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM admin_action WHERE old_value IS NULL AND new_value IS NULL"));
            String values = "INSERT INTO admin_action (id,created_at,actor_id,action,target_user_id,target_id,reason,old_value,new_value) VALUES (RANDOM_UUID(),CURRENT_TIMESTAMP,NULL,";
            assertEquals(1, sql.executeUpdate(values + "'TITLE_CORRECTED',NULL,RANDOM_UUID(),NULL,'Tenmoku ovr ash','Tenmoku over ash')"));
            assertEquals(1, sql.executeUpdate(values + "'TITLE_CORRECTED',NULL,RANDOM_UUID(),NULL,'Draft','')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(values + "'TITLE_CORRECTED',NULL,RANDOM_UUID(),NULL,'Old',NULL)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(values + "'TITLE_CORRECTED',NULL,RANDOM_UUID(),NULL,NULL,'New')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(values + "'BAN',RANDOM_UUID(),RANDOM_UUID(),'Spam','Old','New')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(values + "'TITLE_CORRECTED',NULL,RANDOM_UUID(),NULL,'" + "x".repeat(161) + "','New')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate(values + "'RENAME_EVERYTHING',NULL,NULL,NULL,NULL,NULL)"));
            for (String type : new String[] {"UNBAN", "AVATAR_REMOVED", "CLUB_ADMIN_PROMOTED"})
                assertEquals(1, sql.executeUpdate(action + "'" + type + "',NULL,RANDOM_UUID(),NULL)"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name)='admin_action'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_admin_action", "ck_admin_action_action", "ck_admin_action_values", "fk_admin_action_actor")),
                    constraints.toString());
        } finally { source.destroy(); }
    }

    /** V38: a fourth kind, IN_PROGRESS, for combination photos only; existing photos keep their kind. */
    @Test void workInProgressPhotosBelongToCombinationsOnly() throws Exception {
        var source = database("in-progress");
        Flyway.configure().dataSource(source).target("36").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID user = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID(), combo = UUID.randomUUID();
            UUID tile = UUID.randomUUID(), jar = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,password_hash,display_name) VALUES ('" + user + "','progress@example.test','hash','Maker')");
            sql.executeUpdate("INSERT INTO manufacturer (id,short_id,name,name_key) VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            sql.executeUpdate("INSERT INTO glaze (id,manufacturer_id,name,code) VALUES ('" + glaze + "','" + maker + "','Blue','PC-20')");
            sql.executeUpdate("INSERT INTO combination (id,title,created_by,created_at,published_at) VALUES ('" + combo + "','Layers','" + user
                    + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate(imageInsert(tile, "'" + combo + "'", null, user));
            sql.executeUpdate(imageInsert(jar, "NULL", "'" + glaze + "'", user).replace("'TEST_TILE'", "'PACKAGING'"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE image SET kind='IN_PROGRESS' WHERE id='" + tile + "'"));
            Flyway.configure().dataSource(source).load().migrate();
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM image WHERE id = '" + tile + "' AND kind = 'TEST_TILE'"));
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM image WHERE id = '" + jar + "' AND kind = 'PACKAGING'"));
            assertEquals(1, sql.executeUpdate("UPDATE image SET kind='IN_PROGRESS' WHERE id='" + tile + "'"));
            for (String refused : new String[]{"kind='IN_PROGRESS'", "kind='JAR'"})
                assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE image SET " + refused + " WHERE id='" + jar + "'"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE image SET kind='PACKAGING' WHERE id='" + tile + "'"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name)='image'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("ck_image_kind", "ck_image_packaging_target", "ck_image_in_progress_target",
                    "ck_image_single_target")), constraints.toString());
        } finally { source.destroy(); }
    }

    @Test void clubWishlistsAreCheckedGoWithTheirClubAndGlazeAndOutliveTheirAdders() throws Exception {
        var source = database("club-wishlist");
        Flyway.configure().dataSource(source).target("39").load().migrate();
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            UUID adder = UUID.randomUUID(), club = UUID.randomUUID(), maker = UUID.randomUUID(), glaze = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO app_user (id,email,email_key,password_hash,display_name) VALUES ('" + adder
                    + "','wish@example.test','wish@example.test','hash','Adder')");
            sql.executeUpdate("INSERT INTO club (id, name) VALUES ('" + club + "','Kiln')");
            sql.executeUpdate("INSERT INTO manufacturer (id,short_id,name,name_key) VALUES ('" + maker + "','AMACO','Amaco','amaco')");
            sql.executeUpdate("INSERT INTO glaze (id,manufacturer_id,name,code) VALUES ('" + glaze + "','" + maker + "','Blue','PC-20')");
            Flyway.configure().dataSource(source).load().migrate();
            String insert = "INSERT INTO club_wishlist (id,club_id,entry_type,glaze_id,item_name,link,note,added_by,created_at) VALUES (RANDOM_UUID(),'"
                    + club + "',";
            String by = ",'" + adder + "',CURRENT_TIMESTAMP)";
            assertEquals(1, sql.executeUpdate(insert + "'GLAZE','" + glaze + "',NULL,NULL,'For the course'" + by));
            assertEquals(1, sql.executeUpdate(insert + "'ITEM',NULL,'Kiln shelf','https://example.test/shelf',NULL" + by));
            // The type is checked, and each type has exactly its own fields.
            for (String refused : new String[]{"'BOOK',NULL,'Kiln shelf','https://example.test/',NULL", "'GLAZE',NULL,NULL,NULL,NULL",
                    "'GLAZE','" + glaze + "','Blue',NULL,NULL", "'GLAZE','" + glaze + "',NULL,'https://example.test/',NULL",
                    "'ITEM',NULL,'Kiln shelf',NULL,NULL", "'ITEM',NULL,NULL,'https://example.test/',NULL",
                    "'ITEM','" + glaze + "','Kiln shelf','https://example.test/',NULL"})
                assertThrows(SQLException.class, () -> sql.executeUpdate(insert + refused + by), refused);
            // An adder is an existing account.
            assertThrows(SQLException.class, () -> sql.executeUpdate(insert + "'ITEM',NULL,'Shelf','https://example.test/',NULL,RANDOM_UUID(),CURRENT_TIMESTAMP)"));
            // The adder's account goes, the entries stay without them; the glaze goes with its entries; the club with all of them.
            sql.executeUpdate("DELETE FROM app_user WHERE id = '" + adder + "'");
            assertEquals(2, count(sql, "SELECT COUNT(*) FROM club_wishlist WHERE added_by IS NULL"));
            sql.executeUpdate("DELETE FROM glaze WHERE id = '" + glaze + "'");
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM club_wishlist"));
            sql.executeUpdate("DELETE FROM club WHERE id = '" + club + "'");
            assertEquals(0, count(sql, "SELECT COUNT(*) FROM club_wishlist"));
            Set<String> constraints = new HashSet<>();
            try (var rows = sql.executeQuery("SELECT LOWER(constraint_name) FROM " + CONSTRAINTS + " WHERE LOWER(table_name)='club_wishlist'")) {
                while (rows.next()) constraints.add(rows.getString(1));
            }
            assertTrue(constraints.containsAll(Set.of("pk_club_wishlist", "ck_club_wishlist_type", "ck_club_wishlist_fields",
                    "fk_club_wishlist_club", "fk_club_wishlist_glaze", "fk_club_wishlist_added_by")), constraints.toString());
            assertTrue(indexes(connection, "club_wishlist").containsAll(Set.of("club_wishlist_club_idx", "club_wishlist_glaze_idx",
                    "club_wishlist_added_by_idx")), indexes(connection, "club_wishlist").toString());
        } finally { source.destroy(); }
    }

    /** The JDBC type of a column; PostgreSQL's driver reports {@code timestamptz} as TIMESTAMP, so its type name decides. */
    private static int timestampType(java.sql.ResultSet column) throws SQLException {
        return "timestamptz".equals(column.getString("TYPE_NAME")) ? java.sql.Types.TIMESTAMP_WITH_TIMEZONE : column.getInt("DATA_TYPE");
    }
}
