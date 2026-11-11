package db.migration;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Existing dormant registrations get a full 24-hour window; verified accounts keep no deadline. */
public class V16__pending_account_expiry extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE app_user ADD COLUMN verification_expires_at TIMESTAMP");
        }
        try (var update = context.getConnection().prepareStatement(
                "UPDATE app_user SET verification_expires_at = ? WHERE email_verified = FALSE")) {
            update.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusHours(24)));
            update.executeUpdate();
        }
    }
}
