package db.migration;

import java.util.ArrayList;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** V1's unnamed kind/visibility check has database-generated names in H2 and PostgreSQL. */
public class V20__packaging_photos extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var previous = new ArrayList<String>();
        try (var query = connection.createStatement(); var rows = query.executeQuery(
                "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc"
                + " JOIN information_schema.check_constraints cc ON tc.constraint_catalog = cc.constraint_catalog"
                + " AND tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name"
                + " WHERE LOWER(tc.table_name) = 'image' AND tc.constraint_type = 'CHECK'"
                + " AND tc.table_schema = CURRENT_SCHEMA()")) {
            while (rows.next()) {
                String clause = rows.getString(2).toLowerCase(Locale.ROOT);
                // PostgreSQL 18 also reports NOT NULL entries as CHECK constraints here.
                if (clause.matches("(?s).*\\bkind\\b.*") && clause.contains("'test_tile'")
                        && clause.contains("'finished'")) previous.add(rows.getString(1));
            }
        }
        if (previous.size() != 1) throw new IllegalStateException("Expected one legacy image kind check");
        String quote = connection.getMetaData().getIdentifierQuoteString();
        try (var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE image DROP CONSTRAINT " + quote + previous.getFirst().replace(quote, quote + quote) + quote);
            sql.execute("ALTER TABLE image ADD CONSTRAINT ck_image_visibility CHECK (visibility IN ('PUBLIC','CLUB','PRIVATE')"
                    + " AND ((visibility = 'CLUB' AND club_id IS NOT NULL) OR (visibility <> 'CLUB' AND club_id IS NULL)))");
            sql.execute("ALTER TABLE image ADD CONSTRAINT ck_image_kind CHECK (kind IN ('TEST_TILE','FINISHED','PACKAGING'))");
            sql.execute("ALTER TABLE image ADD CONSTRAINT ck_image_packaging_target CHECK (kind <> 'PACKAGING' OR glaze_id IS NOT NULL)");
        }
    }
}
