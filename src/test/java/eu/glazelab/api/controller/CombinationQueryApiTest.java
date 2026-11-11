package eu.glazelab.api.controller;

import eu.glazelab.api.dto.CombinationDtos;
import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.model.StepType;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:combinationqueries;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false" })
class CombinationQueryApiTest extends ApiTestBase {
    private record Fixture(UUID id, UUID glazeId, RestTestClient owner) {}
    private Measured<List<CombinationView>> measured(RestTestClient browser) {
        return measured(() -> combinations(browser));
    }

    private Fixture fixture(boolean withClub) {
        var owner = user("batch-author");
        UUID glaze = addGlaze(owner, unique("Batch manufacturer"), "Batch blue", "1");
        UUID id = created(owner.post().uri("/api/combinations").body(Map.of(
                "title", unique("Batch combination"), "tags", List.of("batch-test"),
                "steps", List.of(Map.of("glazeId", glaze, "coats", 2, "method", "Brush"),
                        Map.of("glazeId", glaze, "coats", 1, "method", "Dip"))))
                .exchange().expectStatus().isOk());
        byte[] photo = TestImages.png(24, 24);
        for (String visibility : List.of("PUBLIC", "PRIVATE"))
            upload(owner, id, Map.of("visibility", visibility, "kind", "TEST_TILE"), photo)
                    .expectStatus().isOk();
        if (withClub) {
            UUID club = createClub(owner, unique("Batch club"));
            upload(owner, id, Map.of("visibility", "CLUB", "kind", "FINISHED", "clubId", club.toString()), photo)
                    .expectStatus().isOk();
            owner.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 100, "ownerType", "personal"))
                    .exchange().expectStatus().isOk();
        }
        return new Fixture(id, glaze, owner);
    }

    @Test void browseUsesConstantQueriesAsCombinationsAuthorsGlazesAndImagesGrow() {
        var empty = measured(guest());
        assertTrue(empty.result().isEmpty());
        assertEquals(1, empty.statements());
        Fixture first = fixture(true);
        var guestOne = measured(guest());
        var ownerOne = measured(first.owner());
        for (int i = 0; i < 7; i++) fixture(false);
        var guestMany = measured(guest());
        var ownerMany = measured(first.owner());
        assertEquals(1, guestOne.result().size());
        assertEquals(8, guestMany.result().size());
        assertEquals(3, guestOne.statements());
        assertEquals(guestOne.statements(), guestMany.statements());
        assertTrue(ownerOne.statements() <= 7, "Authenticated browse should need at most seven statements");
        assertEquals(ownerOne.statements(), ownerMany.statements());
        for (CombinationView view : guestMany.result()) {
            assertNotNull(view.author());
            assertEquals(List.of("batch-test"), view.tags());
            assertEquals(List.of(1, 2), view.steps().stream().map(CombinationDtos.StepView::position).toList());
            assertEquals(List.of("Brush", "Dip"), view.steps().stream().map(CombinationDtos.StepView::method).toList());
            assertEquals(List.of(ImageVisibility.PUBLIC), view.images().stream().map(ImageView::visibility).toList());
        }
        CombinationView own = ownerMany.result().stream().filter(view -> view.id().equals(first.id())).findFirst().orElseThrow();
        assertEquals(List.of(ImageVisibility.CLUB, ImageVisibility.PRIVATE, ImageVisibility.PUBLIC), own.images().stream().map(ImageView::visibility).toList());
        System.out.printf("Combination browse statements: guest 1 item=%d, 8 items=%d; member 1 item=%d, 8 items=%d%n",
                guestOne.statements(), guestMany.statements(), ownerOne.statements(), ownerMany.statements());
    }

    @Test void canDeleteOfClubPhotosNeedsNoQueryPerPhotoOrClub() {
        var admin = user("rights-admin");
        UUID glaze = addGlaze(admin, unique("Rights manufacturer"), "Rights blue", "1");
        long[] combinationStatements = new long[2], glazeStatements = new long[2];
        for (int round = 0; round < 2; round++) {
            // Each round adds a club the viewer administers and one it only belongs to, each with members' photos.
            for (boolean administered : new boolean[] {true, false}) {
                var member = user("rights-member");
                UUID club = administered ? createClub(admin, unique("Rights club")) : createClub(member, unique("Joined club"));
                if (administered) joinClub(admin, club, member);
                else joinClub(member, club, admin);
                UUID combination = addCombination(member, unique("Rights combination"), glaze);
                for (int i = 0; i < 2; i++) uploadTile(member, combination, "CLUB", club);
                uploadGlazeImage(member, glaze, Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "FINISHED"), PNG)
                        .expectStatus().isOk();
            }
            combinationStatements[round] = measured(admin).statements();
            glazeStatements[round] = measured(() -> glazes(admin)).statements();
        }
        assertEquals(combinationStatements[0], combinationStatements[1]);
        assertEquals(glazeStatements[0], glazeStatements[1]);
        var clubPhotos = measured(admin).result().stream().flatMap(c -> c.images().stream())
                .filter(i -> i.visibility() == ImageVisibility.CLUB).toList();
        assertEquals(8, clubPhotos.size());
        assertEquals(4, clubPhotos.stream().filter(ImageView::canDelete).count());
        var glazePhotos = glazes(admin).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow().images();
        // Newest first: the joined club's photo, then the administered one's, per round.
        assertEquals(List.of(false, true, false, true), glazePhotos.stream().map(ImageView::canDelete).toList());
    }

    @Test void customStepsStayInTheListingWithConstantQueries() {
        var owner = user("custom-author");
        UUID glaze = addGlaze(owner, unique("Custom manufacturer"), "Custom blue", "1");
        long[] statements = new long[2];
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++) {
                owner.post().uri("/api/combinations").body(Map.of("title", unique("Custom only"), "steps", List.of(
                        Map.of("type", "CUSTOM", "label", "Bisque firing"))))
                        .exchange().expectStatus().isOk();
                owner.post().uri("/api/combinations").body(Map.of("title", unique("Mixed"), "steps", List.of(
                        Map.of("type", "CUSTOM", "label", "Bisque firing"),
                        Map.of("glazeId", glaze, "coats", 1)))).exchange().expectStatus().isOk();
            }
            statements[round] = measured(owner).statements();
        }
        assertEquals(statements[0], statements[1]);
        var withCustom = measured(guest()).result().stream()
                .filter(v -> v.steps().stream().anyMatch(s -> s.type() == StepType.CUSTOM)).toList();
        assertEquals(16, withCustom.size());
        assertEquals(8, withCustom.stream().filter(v -> v.steps().size() == 1).count());
    }
}
