package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Converts every VARCHAR(36) key and foreign key to the native UUID type.
 *
 * <p>The V1/V4/V5 foreign keys are unnamed, so H2 and PostgreSQL generated different names, and the
 * type-change syntax differs (PostgreSQL needs {@code USING col::uuid}). The migration therefore
 * discovers the existing foreign keys through JDBC metadata, refuses unexpected ones, validates every
 * value before any DDL, converts the columns and recreates the foreign keys with explicit names.
 */
public class V7__native_uuid_keys extends BaseJavaMigration {
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Key columns per table, in an order that converts referenced tables first. */
    private static final Map<String, List<String>> COLUMNS = new LinkedHashMap<>();
    static {
        COLUMNS.put("app_user", List.of("id"));
        COLUMNS.put("club", List.of("id", "owner_id"));
        COLUMNS.put("membership", List.of("club_id", "user_id"));
        COLUMNS.put("glaze", List.of("id", "created_by"));
        COLUMNS.put("stock", List.of("id", "glaze_id", "user_id", "club_id"));
        COLUMNS.put("combination", List.of("id", "created_by"));
        COLUMNS.put("combination_step", List.of("id", "combination_id", "glaze_id"));
        COLUMNS.put("image", List.of("id", "combination_id", "uploaded_by", "club_id"));
        COLUMNS.put("account_token", List.of("id", "user_id"));
        COLUMNS.put("favorite", List.of("id", "user_id", "glaze_id", "combination_id"));
    }

    private record ForeignKey(String table, String column, String target) {
        String name() { return "fk_" + table + "_" + column; }
    }

    private static final List<ForeignKey> FOREIGN_KEYS = List.of(
            new ForeignKey("club", "owner_id", "app_user"),
            new ForeignKey("membership", "club_id", "club"),
            new ForeignKey("membership", "user_id", "app_user"),
            new ForeignKey("glaze", "created_by", "app_user"),
            new ForeignKey("stock", "glaze_id", "glaze"),
            new ForeignKey("stock", "user_id", "app_user"),
            new ForeignKey("stock", "club_id", "club"),
            new ForeignKey("combination", "created_by", "app_user"),
            new ForeignKey("combination_step", "combination_id", "combination"),
            new ForeignKey("combination_step", "glaze_id", "glaze"),
            new ForeignKey("image", "combination_id", "combination"),
            new ForeignKey("image", "uploaded_by", "app_user"),
            new ForeignKey("image", "club_id", "club"),
            new ForeignKey("account_token", "user_id", "app_user"),
            new ForeignKey("favorite", "user_id", "app_user"),
            new ForeignKey("favorite", "glaze_id", "glaze"),
            new ForeignKey("favorite", "combination_id", "combination"));

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        boolean postgres = product.contains("postgres");
        if (!postgres && !product.contains("h2")) {
            throw new IllegalStateException("Unsupported database for UUID conversion: " + product);
        }
        rejectInvalidValues(connection);
        Map<ForeignKey, String> existing = existingForeignKeys(connection);
        String quote = connection.getMetaData().getIdentifierQuoteString();
        try (var statement = connection.createStatement()) {
            for (var entry : existing.entrySet()) {
                String name = entry.getValue().replace(quote, quote + quote);
                statement.execute("ALTER TABLE " + entry.getKey().table() + " DROP CONSTRAINT "
                        + quote + name + quote);
            }
            for (var table : COLUMNS.entrySet()) {
                if (postgres) {
                    var changes = table.getValue().stream()
                            .map(column -> "ALTER COLUMN " + column + " TYPE UUID USING " + column + "::uuid")
                            .toList();
                    statement.execute("ALTER TABLE " + table.getKey() + " " + String.join(", ", changes));
                } else {
                    for (String column : table.getValue()) {
                        statement.execute("ALTER TABLE " + table.getKey() + " ALTER COLUMN " + column
                                + " SET DATA TYPE UUID");
                    }
                }
            }
            for (ForeignKey key : FOREIGN_KEYS) {
                statement.execute("ALTER TABLE " + key.table() + " ADD CONSTRAINT " + key.name()
                        + " FOREIGN KEY (" + key.column() + ") REFERENCES " + key.target() + "(id)");
            }
        }
    }

    /** Fails before any DDL, so a database with non-UUID identifiers is left unchanged for review. */
    private static void rejectInvalidValues(Connection connection) throws SQLException {
        var problems = new ArrayList<String>();
        try (var statement = connection.createStatement()) {
            for (var table : COLUMNS.entrySet()) {
                for (String column : table.getValue()) {
                    try (var rows = statement.executeQuery("SELECT " + column + " FROM " + table.getKey()
                            + " WHERE " + column + " IS NOT NULL")) {
                        while (rows.next()) {
                            String value = rows.getString(1);
                            if (!UUID_TEXT.matcher(value).matches()) {
                                problems.add(table.getKey() + "." + column + "='" + value + "'");
                            }
                        }
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Cannot convert non-UUID identifiers to UUID columns: "
                    + String.join(", ", problems.subList(0, Math.min(problems.size(), 20))));
        }
    }

    /** Maps each expected foreign key to its generated name and rejects any other foreign key. */
    private static Map<ForeignKey, String> existingForeignKeys(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = connection.getSchema();
        var found = new LinkedHashMap<ForeignKey, String>();
        var unexpected = new ArrayList<String>();
        for (String table : COLUMNS.keySet()) {
            String stored = metadata.storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT) : table;
            try (var rows = metadata.getImportedKeys(null, schema, stored)) {
                while (rows.next()) {
                    var key = new ForeignKey(table, rows.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT),
                            rows.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT));
                    int deleteRule = rows.getShort("DELETE_RULE");
                    int updateRule = rows.getShort("UPDATE_RULE");
                    boolean defaultRules = isDefaultRule(deleteRule) && isDefaultRule(updateRule);
                    if (!FOREIGN_KEYS.contains(key) || !defaultRules
                            || !"id".equalsIgnoreCase(rows.getString("PKCOLUMN_NAME"))
                            || rows.getShort("KEY_SEQ") != 1 || found.containsKey(key)) {
                        unexpected.add(rows.getString("FK_NAME") + " on " + table);
                    } else {
                        found.put(key, rows.getString("FK_NAME"));
                    }
                }
            }
        }
        // Missing expected keys are simply created afterwards; unknown keys need a manual review.
        if (!unexpected.isEmpty()) {
            throw new IllegalStateException("Unexpected foreign keys before UUID conversion: " + unexpected);
        }
        return found;
    }

    private static boolean isDefaultRule(int rule) {
        return rule == DatabaseMetaData.importedKeyNoAction || rule == DatabaseMetaData.importedKeyRestrict;
    }
}
