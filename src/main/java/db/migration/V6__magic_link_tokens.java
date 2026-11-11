package db.migration;

import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** The original unnamed check has different generated names on H2 and PostgreSQL. */
public class V6__magic_link_tokens extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var constraints = new ArrayList<String>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT tc.constraint_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_name = tc.constraint_name AND cc.constraint_schema = tc.constraint_schema
                WHERE LOWER(tc.table_name) = 'account_token' AND tc.constraint_type = 'CHECK'
                  AND LOWER(cc.check_clause) LIKE '%purpose%'
                  AND LOWER(cc.check_clause) NOT LIKE '%is not null%'
                """)) {
            while (rows.next()) constraints.add(rows.getString(1));
        }
        if (constraints.size() != 1) throw new IllegalStateException("Expected one account-token purpose constraint");
        String quote = connection.getMetaData().getIdentifierQuoteString();
        String name = constraints.getFirst().replace(quote, quote + quote);
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE account_token DROP CONSTRAINT " + quote + name + quote);
            statement.execute("ALTER TABLE account_token ADD CONSTRAINT account_token_purpose_check "
                    + "CHECK (purpose IN ('RESET_PASSWORD', 'VERIFY_EMAIL', 'MAGIC_LOGIN'))");
        }
    }
}
