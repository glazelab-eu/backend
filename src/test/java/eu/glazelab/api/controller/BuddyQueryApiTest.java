package eu.glazelab.api.controller;

import eu.glazelab.api.dto.BuddyDtos.BuddiesView;
import eu.glazelab.api.dto.BuddyDtos.BuddyGlazeView;
import eu.glazelab.api.model.BuddyId;
import eu.glazelab.api.support.ApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** The buddy list and buddy availability need a constant number of statements however many buddies and glazes there are. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:buddyqueries;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false" })
class BuddyQueryApiTest extends ApiTestBase {
    /** An account written with SQL (it never signs in), so a test can have many of them quickly. */
    private UUID account(String name) {
        UUID id = UUID.randomUUID();
        String address = email(name);
        jdbc.update("INSERT INTO app_user (id, email, email_key, password_hash, display_name, email_verified, session_version, site_admin) VALUES (?, ?, ?, 'unusable', ?, TRUE, 0, FALSE)",
                id, address, address, name);
        return id;
    }

    private void stock(UUID user, UUID glaze) {
        jdbc.update("INSERT INTO stock (id, glaze_id, user_id, quantity_g) VALUES (?, ?, ?, 100)", UUID.randomUUID(), glaze, user);
    }

    private void pair(UUID asker, UUID asked, boolean accepted) {
        BuddyId id = BuddyId.of(asker, asked);
        var now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbc.update("INSERT INTO buddy (user_a, user_b, status, requested_by, created_at, accepted_at) VALUES (?, ?, ?, ?, ?, ?)",
                id.getUserA(), id.getUserB(), accepted ? "ACCEPTED" : "PENDING", asker, now, accepted ? now : null);
    }

    /** A profile picture row (the listing never reads its objects). */
    private void avatar(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_avatar (id, user_id, storage_key, upload_sha256, stored_sha256, dhash, created_at)"
                + " VALUES (?, ?, ?, ?, ?, 0, ?)", id, user, id + ".jpg", "a".repeat(64), "b".repeat(64),
                java.sql.Timestamp.from(java.time.Instant.now()));
    }

    /**
     * A buddy of the viewer with two glazes on their shelf (one shared with every buddy), plus one pending request each
     * way; every other buddy has a profile picture.
     */
    private void addBuddy(UUID viewer, UUID shared, RestTestClient adder, String manufacturer, int n) {
        UUID buddy = account("Buddy" + n);
        if (n % 2 == 0) avatar(buddy);
        stock(buddy, shared);
        stock(buddy, addGlaze(adder, manufacturer, "Own " + n, "Q" + n));
        pair(viewer, buddy, true);
        pair(account("Asker" + n), viewer, false);
        pair(viewer, account("Asked" + n), false);
    }

    @Test void listAndAvailabilityUseConstantStatements() {
        var viewer = user("Viewer");
        UUID viewerId = me(viewer).id();
        String manufacturer = unique("Buddy queries");
        UUID shared = addGlaze(viewer, manufacturer, "Shared", "Q-SHARED");
        addBuddy(viewerId, shared, viewer, manufacturer, 0);
        Measured<List<BuddyGlazeView>> small = measured(() -> BuddyApiTest.availability(viewer));
        Measured<BuddiesView> smallList = measured(() -> BuddyApiTest.buddies(viewer));
        assertEquals(2, small.result().size());
        assertEquals(1, smallList.result().buddies().size());

        for (int n = 1; n < 30; n++) addBuddy(viewerId, shared, viewer, manufacturer, n);
        Measured<List<BuddyGlazeView>> large = measured(() -> BuddyApiTest.availability(viewer));
        Measured<BuddiesView> largeList = measured(() -> BuddyApiTest.buddies(viewer));
        assertEquals(31, large.result().size());
        assertEquals(30, large.result().stream().filter(g -> g.glazeId().equals(shared)).findFirst().orElseThrow().buddies().size());
        assertEquals(30, largeList.result().buddies().size());
        assertEquals(30, largeList.result().incoming().size());
        assertEquals(30, largeList.result().outgoing().size());
        assertEquals(15, largeList.result().buddies().stream().filter(b -> b.avatarId() != null).count());
        assertTrue(small.statements() > 0, "statistics must count");
        assertEquals(small.statements(), large.statements(), "availability statements");
        assertEquals(smallList.statements(), largeList.statements(), "list statements");
    }
}
