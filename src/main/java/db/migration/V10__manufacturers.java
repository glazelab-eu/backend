package db.migration;

import java.sql.Connection;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Replaces the free-text glaze brand with a manufacturer list and makes the glaze code mandatory.
 *
 * <p>Only dev and test databases exist, so the mapping is plain: one manufacturer per brand (ignoring
 * letter case and surrounding spaces), a short ID made from the brand's letters and digits, and
 * NOCODE-1, NOCODE-2… for glazes without a code. Java, because UUID generation differs between H2
 * and PostgreSQL.
 */
public class V10__manufacturers extends BaseJavaMigration {
    private record Row(UUID id, String code) {}

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE manufacturer (id UUID NOT NULL, short_id VARCHAR(10) NOT NULL,"
                    + " name VARCHAR(100) NOT NULL, name_key VARCHAR(100) NOT NULL,"
                    + " CONSTRAINT pk_manufacturer PRIMARY KEY (id),"
                    + " CONSTRAINT uq_manufacturer_short_id UNIQUE (short_id),"
                    + " CONSTRAINT uq_manufacturer_name_key UNIQUE (name_key))");
            statement.execute("ALTER TABLE glaze ADD COLUMN manufacturer_id UUID");
        }

        // Display name of each brand (the first spelling in sort order) and its glazes, by lower-case name.
        Map<String, String> names = new TreeMap<>();
        Map<String, List<Row>> glazes = new TreeMap<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT id, brand, code FROM glaze ORDER BY brand, name, id")) {
            while (rows.next()) {
                String name = rows.getString("brand").trim();
                if (name.isEmpty()) name = "Unknown";
                String key = name.toLowerCase(Locale.ROOT);
                names.putIfAbsent(key, name);
                glazes.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Row(rows.getObject("id", UUID.class), rows.getString("code")));
            }
        }

        Set<String> shortIds = new HashSet<>();
        try (var manufacturer = connection.prepareStatement(
                        "INSERT INTO manufacturer (id, short_id, name, name_key) VALUES (?, ?, ?, ?)");
                var glaze = connection.prepareStatement("UPDATE glaze SET manufacturer_id = ?, code = ? WHERE id = ?")) {
            for (var entry : names.entrySet()) {
                UUID id = UUID.randomUUID();
                manufacturer.setObject(1, id);
                manufacturer.setString(2, shortId(entry.getValue(), shortIds));
                manufacturer.setString(3, entry.getValue());
                manufacturer.setString(4, entry.getKey());
                manufacturer.executeUpdate();

                List<Row> rows = glazes.get(entry.getKey());
                Set<String> codes = new HashSet<>();
                for (Row row : rows) if (present(row.code())) codes.add(normalized(row.code()));
                int missing = 0;
                for (Row row : rows) {
                    String code = present(row.code()) ? normalized(row.code()) : null;
                    while (code == null) {
                        String next = "NOCODE-" + ++missing;
                        if (codes.add(next)) code = next;
                    }
                    glaze.setObject(1, id);
                    glaze.setString(2, code);
                    glaze.setObject(3, row.id());
                    glaze.executeUpdate();
                }
            }
        }

        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE glaze ALTER COLUMN manufacturer_id SET NOT NULL");
            statement.execute("ALTER TABLE glaze ALTER COLUMN code SET NOT NULL");
            statement.execute("ALTER TABLE glaze ADD CONSTRAINT fk_glaze_manufacturer_id"
                    + " FOREIGN KEY (manufacturer_id) REFERENCES manufacturer(id)");
            statement.execute("DROP INDEX glaze_catalog_key_unique");
            statement.execute("ALTER TABLE glaze DROP COLUMN catalog_key");
            statement.execute("ALTER TABLE glaze DROP COLUMN brand");
            statement.execute("ALTER TABLE glaze ADD CONSTRAINT uq_glaze_manufacturer_code UNIQUE (manufacturer_id, code)");
        }
    }

    private static boolean present(String code) {
        return code != null && !code.isBlank();
    }

    private static String normalized(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /** Upper-case ASCII letters and digits of the name, 2 to 10 long, starting with a letter, not yet taken. */
    static String shortId(String name, Set<String> taken) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (base.length() < 2 || !Character.isLetter(base.charAt(0))) base = "MFR" + base;
        base = base.substring(0, Math.min(base.length(), 10));
        String candidate = base;
        for (int n = 2; !taken.add(candidate); n++) {
            String suffix = Integer.toString(n);
            candidate = base.substring(0, Math.min(base.length(), 10 - suffix.length())) + suffix;
        }
        return candidate;
    }
}
