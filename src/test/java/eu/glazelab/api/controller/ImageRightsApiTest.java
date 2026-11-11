package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.repository.ImageDeletionRepository;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/** Who may delete a photo (decision Q11): the uploader, an admin of a club photo's club, site admins. */
class ImageRightsApiTest extends ApiTestBase {
    @Autowired ImageDeletionRepository deletions;

    /** Uploader and member of a club the admin created; a plain member; an outsider; a site admin. */
    private record People(RestTestClient uploader, RestTestClient clubAdmin, RestTestClient member,
            RestTestClient other, RestTestClient siteAdmin, UUID club, UUID combination, UUID glaze) {
        Map<String, RestTestClient> byRole() {
            return Map.of("uploader", uploader, "clubAdmin", clubAdmin, "member", member, "other", other, "siteAdmin", siteAdmin);
        }
    }

    private People people() {
        var uploader = user("RightsUploader");
        var member = user("RightsMember");
        var clubAdmin = user("RightsClubAdmin");
        UUID club = createClub(clubAdmin, unique("Rights club"));
        for (var joining : List.of(uploader, member)) joinClub(clubAdmin, club, joining);
        UUID glaze = addGlaze(uploader, unique("Rights maker"), "Blue", "1");
        return new People(uploader, clubAdmin, member, user("RightsOther"), siteAdmin(), club,
                addCombination(uploader, unique("Rights combination"), glaze), glaze);
    }

    private static UUID photo(RestTestClient client, People p, String visibility) {
        return uploadTile(client, p.combination(), visibility, visibility.equals("CLUB") ? p.club() : null);
    }

    @Test void deletionMatrixForEveryRoleAndVisibility() {
        var p = people();
        // Expected status per visibility, then per role; 204 removes the photo and logs who did it.
        Map<String, Map<String, Integer>> expected = Map.of(
                "PUBLIC", Map.of("uploader", 204, "clubAdmin", 403, "member", 403, "other", 403, "siteAdmin", 204),
                "CLUB", Map.of("uploader", 204, "clubAdmin", 204, "member", 403, "other", 404, "siteAdmin", 204),
                "PRIVATE", Map.of("uploader", 204, "clubAdmin", 404, "member", 404, "other", 404, "siteAdmin", 204));
        expected.forEach((visibility, statuses) -> {
            UUID guestTarget = photo(p.uploader(), p, visibility);
            guest().delete().uri("/api/images/{id}", guestTarget).exchange().expectStatus().isUnauthorized();
            assertTrue(exists(guestTarget));
            statuses.forEach((role, status) -> {
                UUID id = photo(p.uploader(), p, visibility);
                RestTestClient actor = p.byRole().get(role);
                var response = actor.delete().uri("/api/images/{id}", id).exchange();
                String cell = visibility + " by " + role;
                switch (status) {
                    case 204 -> {
                        response.expectStatus().isNoContent();
                        assertFalse(exists(id), cell);
                        assertEquals(me(actor).id(), deletions.findAll().stream().filter(d -> d.getImageId().equals(id))
                                .findFirst().orElseThrow().getDeletedBy(), cell);
                    }
                    case 403 -> {
                        assertError(response, 403, ImageController.DELETE_RIGHTS_REQUIRED);
                        assertTrue(exists(id), cell);
                    }
                    default -> {
                        assertError(response, 404, ImageController.IMAGE_NOT_FOUND);
                        assertTrue(exists(id), cell);
                    }
                }
            });
        });
        p.siteAdmin().delete().uri("/api/images/{id}", UUID.randomUUID()).exchange().expectStatus().isNotFound();
    }

    @Test void canDeleteFollowsTheSameRuleInEveryListing() {
        var p = people();
        UUID publicId = photo(p.uploader(), p, "PUBLIC");
        UUID clubId = photo(p.uploader(), p, "CLUB");
        UUID privateId = photo(p.uploader(), p, "PRIVATE");
        UUID glazePhoto = created(uploadGlazeImage(p.uploader(), p.glaze(), Map.of("visibility", "CLUB",
                "clubId", p.club().toString(), "kind", "PACKAGING"), PNG));
        assertEquals(Map.of(publicId, true, clubId, true, privateId, true), canDelete(p.uploader(), p));
        assertEquals(Map.of(publicId, false, clubId, true), canDelete(p.clubAdmin(), p));
        assertEquals(Map.of(publicId, false, clubId, false), canDelete(p.member(), p));
        assertEquals(Map.of(publicId, false), canDelete(p.other(), p));
        assertEquals(Map.of(publicId, true), canDelete(p.siteAdmin(), p));
        assertEquals(Map.of(publicId, false), canDelete(guest(), p));
        // The glaze listings, the glaze page and the barcode lookup share the mapper.
        for (var entry : Map.of(p.clubAdmin(), true, p.member(), false).entrySet()) {
            var images = body(entry.getKey().get().uri("/api/glazes/{id}/images", p.glaze()).exchange().expectStatus().isOk(),
                    new ParameterizedTypeReference<List<ImageView>>() {});
            assertEquals(List.of(entry.getValue()), images.stream().filter(i -> i.id().equals(glazePhoto)).map(ImageView::canDelete).toList());
            assertEquals(List.of(entry.getValue()), glazes(entry.getKey()).stream().filter(g -> g.id().equals(p.glaze()))
                    .flatMap(g -> g.images().stream()).filter(i -> i.id().equals(glazePhoto)).map(ImageView::canDelete).toList());
        }
    }

    @Test void uploadAndEditAnswersLetTheUploaderDelete() {
        var p = people();
        var fields = new HashMap<>(Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"));
        ImageView uploaded = body(upload(p.uploader(), p.combination(), fields, PNG).expectStatus().isOk(), ImageView.class);
        assertTrue(uploaded.canDelete());
        ImageView edited = body(p.uploader().patch().uri("/api/images/{id}", uploaded.id()).body(Map.of("kind", "FINISHED"))
                .exchange().expectStatus().isOk(), ImageView.class);
        assertTrue(edited.canDelete());
        ImageView moved = body(p.uploader().put().uri("/api/images/{id}/visibility", uploaded.id())
                .body(Map.of("visibility", "PRIVATE")).exchange().expectStatus().isOk(), ImageView.class);
        assertTrue(moved.canDelete());
    }

    @Test void photosOfADeletedAccountAreLeftToClubAdminsAndSiteAdmins() {
        var p = people();
        UUID publicId = photo(p.uploader(), p, "PUBLIC");
        UUID clubId = photo(p.uploader(), p, "CLUB");
        UUID secondClubId = photo(p.uploader(), p, "CLUB");
        p.uploader().post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(Map.of(publicId, false, clubId, true, secondClubId, true), canDelete(p.clubAdmin(), p));
        assertEquals(Map.of(publicId, true), canDelete(p.siteAdmin(), p));
        assertEquals(Map.of(publicId, false, clubId, false, secondClubId, false), canDelete(p.member(), p));
        assertError(p.other().delete().uri("/api/images/{id}", publicId).exchange(), 403, ImageController.DELETE_RIGHTS_REQUIRED);
        assertError(p.member().delete().uri("/api/images/{id}", clubId).exchange(), 403, ImageController.DELETE_RIGHTS_REQUIRED);
        p.clubAdmin().delete().uri("/api/images/{id}", clubId).exchange().expectStatus().isNoContent();
        p.siteAdmin().delete().uri("/api/images/{id}", secondClubId).exchange().expectStatus().isNoContent();
        p.siteAdmin().delete().uri("/api/images/{id}", publicId).exchange().expectStatus().isNoContent();
        assertFalse(exists(publicId) || exists(clubId) || exists(secondClubId));
    }

    @Test void canDeleteMatchesTheDeleteAnswerForEveryRoleAndPhoto() {
        var p = people();
        // An admin of another club who is only a member of the photo's club has no right here.
        var elsewhere = user("RightsElsewhere");
        createClub(elsewhere, unique("Elsewhere club"));
        joinClub(p.clubAdmin(), p.club(), elsewhere);
        var roles = new HashMap<>(p.byRole());
        roles.put("elsewhereAdmin", elsewhere);
        for (var role : roles.entrySet()) {
            var photos = new HashMap<UUID, String>();
            for (String visibility : List.of("PUBLIC", "CLUB", "PRIVATE")) photos.put(photo(p.uploader(), p, visibility), visibility);
            UUID packaging = created(uploadGlazeImage(p.uploader(), p.glaze(), Map.of("visibility", "CLUB",
                    "clubId", p.club().toString(), "kind", "PACKAGING"), PNG));
            photos.put(packaging, "CLUB glaze");
            var flags = canDelete(role.getValue(), p);
            body(role.getValue().get().uri("/api/glazes/{id}/images", p.glaze()).exchange().expectStatus().isOk(),
                    new ParameterizedTypeReference<List<ImageView>>() {})
                    .stream().filter(i -> i.id().equals(packaging)).forEach(i -> flags.put(i.id(), i.canDelete()));
            photos.forEach((id, label) -> {
                String cell = label + " by " + role.getKey();
                int status = role.getValue().delete().uri("/api/images/{id}", id).exchange().returnResult().getStatus().value();
                Boolean flag = flags.get(id);
                // Listed with canDelete exactly when DELETE succeeds; a photo not listed is a 404, never a 403,
                // except for site admins, who may remove hidden photos (a reported one may have left PUBLIC).
                if (flag == null) assertEquals(role.getKey().equals("siteAdmin") ? 204 : 404, status, cell);
                else assertEquals(flag ? 204 : 403, status, cell);
            });
        }
    }

    private boolean exists(UUID image) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE id = ?", Integer.class, image) == 1;
    }

    /** The viewer's canDelete flags of the fixture combination's photos. */
    private static Map<UUID, Boolean> canDelete(RestTestClient viewer, People p) {
        var images = combinations(viewer).stream().filter(c -> c.id().equals(p.combination())).findFirst().orElseThrow().images();
        var flags = new HashMap<UUID, Boolean>();
        images.forEach(image -> flags.put(image.id(), image.canDelete()));
        return flags;
    }
}
