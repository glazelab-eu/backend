package eu.glazelab.api.controller;

import eu.glazelab.api.controller.SyncApiTest.From;
import eu.glazelab.api.dto.SyncDtos.SyncView;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** Its own database with statement statistics: a delta renders its changed items in a constant number of statements. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:syncqueries;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false" })
class SyncQueryApiTest extends ApiTestBase {
    private record Fixture(RestTestClient owner, UUID club) {}

    /** A combination with glaze steps, tags, a variation source and photos of every visibility, and a corrected glaze with a photo. */
    private void changes(Fixture fixture, UUID source) {
        RestTestClient owner = fixture.owner();
        UUID glaze = addGlaze(owner, unique("Delta maker"), "Delta blue", "1");
        owner.patch().uri("/api/glazes/{id}", glaze).body(Map.of("notes", "Runs")).exchange().expectStatus().isOk();
        uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "PACKAGING"), TestImages.png(20, 20)).expectStatus().isOk();
        UUID id = created(owner.post().uri("/api/combinations").body(Map.of("title", unique("Delta"), "tags", List.of("delta"),
                "basedOnId", source, "steps", List.of(Map.of("glazeId", glaze, "coats", 2), Map.of("type", "CUSTOM", "label", "Fire"))))
                .exchange());
        for (String visibility : List.of("PUBLIC", "PRIVATE", "CLUB"))
            uploadTile(owner, id, visibility, visibility.equals("CLUB") ? fixture.club() : null);
    }

    @Test void aDeltaNeedsTheSameStatementsForOneAndForManyChanges() {
        RestTestClient owner = user("Delta"), member = user("DeltaMember"), guest = guest();
        UUID club = createClub(owner, unique("Delta club"));
        joinClub(owner, club, member);
        Fixture fixture = new Fixture(owner, club);
        UUID source = SyncApiTest.published(member, unique("Source"));
        SyncApiTest.settle(jdbc);
        From ownerFrom = SyncApiTest.start(owner), memberFrom = SyncApiTest.start(member), guestFrom = SyncApiTest.start(guest);
        changes(fixture, source);
        var ownerOne = measured(() -> SyncApiTest.delta(owner, ownerFrom));
        var memberOne = measured(() -> SyncApiTest.delta(member, memberFrom));
        var guestOne = measured(() -> SyncApiTest.delta(guest, guestFrom));
        for (int i = 0; i < 6; i++) changes(fixture, source);
        var ownerMany = measured(() -> SyncApiTest.delta(owner, ownerFrom));
        var memberMany = measured(() -> SyncApiTest.delta(member, memberFrom));
        var guestMany = measured(() -> SyncApiTest.delta(guest, guestFrom));
        for (SyncView view : List.of(ownerOne.result(), memberOne.result(), guestOne.result())) {
            assertEquals(1, view.combinations().size());
            assertEquals(1, view.glazes().size());
        }
        for (SyncView view : List.of(ownerMany.result(), memberMany.result(), guestMany.result())) {
            assertEquals(7, view.combinations().size());
            assertEquals(7, view.glazes().size());
            assertEquals(source, view.combinations().getFirst().basedOnId());
        }
        assertEquals(3, ownerMany.result().combinations().getFirst().images().size());
        assertEquals(2, memberMany.result().combinations().getFirst().images().size());
        assertEquals(1, guestMany.result().combinations().getFirst().images().size());
        assertEquals(ownerOne.statements(), ownerMany.statements());
        assertEquals(memberOne.statements(), memberMany.statements());
        assertEquals(guestOne.statements(), guestMany.statements());
        assertTrue(ownerMany.statements() <= 16, "a delta needs at most 16 statements, took " + ownerMany.statements());
    }
}
