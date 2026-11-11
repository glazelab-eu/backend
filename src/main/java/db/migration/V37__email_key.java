package db.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Every account gets the lookup form of its address ({@code app_user.email_key}, unique {@code uq_app_user_email_key}),
 * so sign-in, registration and account mail find it by one indexed statement and no two accounts differ only in
 * letter case. Computed here in Java (the form of {@code EmailAddresses.key} at this version, copied so the
 * migration never changes): H2 has no expression index and the database's {@code lower()} folds differently.
 * Accounts that would share a key stop the migration before anything changes, naming their IDs only (never an address);
 * the operator query that finds them in advance is in the backend guide "Schema".
 */
public class V37__email_key extends BaseJavaMigration {
    static String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT).codePoints().map(Character::toUpperCase).map(Character::toLowerCase)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        Map<String, List<Object>> accounts = new LinkedHashMap<>();
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT id, email FROM app_user ORDER BY id")) {
            while (rows.next()) accounts.computeIfAbsent(key(rows.getString(2)), k -> new ArrayList<>()).add(rows.getObject(1));
        }
        List<String> clashes = new ArrayList<>();
        for (var ids : accounts.values()) if (ids.size() > 1) clashes.add(ids.toString());
        if (!clashes.isEmpty())
            throw new FlywayException("V37 stopped, nothing changed: " + clashes.size() + " group(s) of accounts whose addresses"
                    + " differ only in letter case, by account ID: " + String.join(", ", clashes)
                    + ". Remove or rename all but one account of each group, then start the application again"
                    + " (backend guide \"Schema\": the operator pre-check query).");
        try (var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE app_user ADD COLUMN email_key VARCHAR(510)");
        }
        try (var update = connection.prepareStatement("UPDATE app_user SET email_key = ? WHERE id = ?")) {
            for (var account : accounts.entrySet()) {
                update.setString(1, account.getKey());
                update.setObject(2, account.getValue().get(0));
                update.addBatch();
            }
            update.executeBatch();
        }
        try (var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE app_user ALTER COLUMN email_key SET NOT NULL");
            sql.execute("ALTER TABLE app_user ADD CONSTRAINT uq_app_user_email_key UNIQUE (email_key)");
        }
    }
}
