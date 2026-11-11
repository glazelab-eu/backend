package eu.glazelab.api.controller;

import eu.glazelab.api.model.ImageKind;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

class PackagingImageApiTest extends ApiTestBase {

    private ImageView uploadPackaging(RestTestClient client, UUID glaze, String visibility) {
        return body(uploadGlazeImage(client, glaze, Map.of("kind", "PACKAGING", "visibility", visibility), TestImages.png(24, 24))
                .expectStatus().isOk(), ImageView.class);
    }

    private RestTestClient.ResponseSpec change(RestTestClient client, UUID id, String kind) {
        return client.patch().uri("/api/images/{id}", id).body(Map.of("kind", kind)).exchange();
    }

    @Test void glazePackagingRoundTripsWithoutChangingBytesIdentityDatesOrCaching() {
        var owner = user("Packaging");
        UUID glaze = addGlaze(owner, unique("Packaging"), "Blue", "P-1");
        ImageView photo = uploadPackaging(owner, glaze, "PUBLIC");
        assertEquals(ImageKind.PACKAGING, photo.kind());
        assertEquals(photo, glazes(owner).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow().images().getFirst());
        var before = jdbc.queryForMap("SELECT storage_key, created_at FROM image WHERE id = ?", photo.id());
        byte[] bytes = guest().get().uri(photo.url()).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "max-age=31536000, public, immutable")
                .expectHeader().valueEquals("X-Accel-Expires", "60").expectBody(byte[].class).returnResult().getResponseBody();
        for (String kind : new String[]{"TEST_TILE", "FINISHED", "PACKAGING"}) {
            ImageView updated = body(change(owner, photo.id(), kind).expectStatus().isOk(), ImageView.class);
            assertEquals(ImageKind.valueOf(kind), updated.kind());
            assertEquals(photo.id(), updated.id()); assertEquals(photo.url(), updated.url());
            assertEquals(before, jdbc.queryForMap("SELECT storage_key, created_at FROM image WHERE id = ?", photo.id()));
            assertArrayEquals(bytes, guest().get().uri(photo.url()).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control", "max-age=31536000, public, immutable")
                    .expectBody(byte[].class).returnResult().getResponseBody());
        }
        change(owner, photo.id(), "JAR").expectStatus().isBadRequest();
    }

    @Test void kindEditsHaveUploaderAuthorizationAndDoNotRevealHiddenImages() {
        var owner = user("PackagingOwner"); var other = user("PackagingOther"); var guest = guest();
        UUID glaze = addGlaze(owner, unique("Ownership"), "Blue", "P-2");
        ImageView photo = uploadPackaging(owner, glaze, "PUBLIC");
        change(other, photo.id(), "FINISHED").expectStatus().isForbidden();
        change(guest, photo.id(), "FINISHED").expectStatus().isUnauthorized();
        ImageView moved = body(owner.put().uri(photo.url() + "/visibility").body(Map.of("visibility", "PRIVATE"))
                .exchange().expectStatus().isOk(), ImageView.class);
        assertNotEquals(photo.id(), moved.id()); assertEquals(ImageKind.PACKAGING, moved.kind());
        change(owner, photo.id(), "FINISHED").expectStatus().isNotFound();
        change(other, moved.id(), "FINISHED").expectStatus().isNotFound();
        other.get().uri(moved.url()).exchange().expectStatus().isNotFound();
        assertTrue(glazes(other).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow().images().isEmpty());
        change(owner, moved.id(), "TEST_TILE").expectStatus().isOk();
        change(owner, moved.id(), "PACKAGING").expectStatus().isOk();
        owner.get().uri(moved.url()).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "max-age=604800, private, immutable");
        owner.delete().uri(moved.url()).exchange().expectStatus().isNoContent();
        owner.get().uri(moved.url()).exchange().expectStatus().isNotFound();
    }

    @Test void clubPackagingUsesTheSameMembershipAndKindEditRules() {
        var owner = user("ClubPackaging"); var member = user("PackagingMember"); var outsider = user("PackagingOutsider");
        UUID glaze = addGlaze(owner, unique("Club packaging"), "Blue", "P-4");
        UUID club = createClub(owner, unique("Packaging club"));
        joinClub(owner, club, member);
        ImageView photo = body(uploadGlazeImage(owner, glaze, Map.of("kind", "PACKAGING", "visibility", "CLUB", "clubId", club.toString()), TestImages.png(24, 24))
                .expectStatus().isOk(), ImageView.class);
        member.get().uri(photo.url()).exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "max-age=604800, private, immutable");
        change(member, photo.id(), "FINISHED").expectStatus().isForbidden();
        change(outsider, photo.id(), "FINISHED").expectStatus().isNotFound();
        change(owner, photo.id(), "FINISHED").expectStatus().isOk();
        change(owner, photo.id(), "PACKAGING").expectStatus().isOk();
        assertEquals(ImageKind.PACKAGING, glazes(member).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow().images().getFirst().kind());
    }

    @Test void combinationsRejectPackagingOnBothUploadAndEditWithoutChangingExistingPhoto() {
        var owner = user("CombinationPackaging");
        UUID glaze = addGlaze(owner, unique("Reject"), "Blue", "P-3");
        UUID combination = addCombination(owner, unique("No packaging"), glaze);
        upload(owner, combination, Map.of("kind", "PACKAGING", "visibility", "PUBLIC"), TestImages.png(24, 24))
                .expectStatus().isBadRequest();
        assertTrue(combination(owner, combination).orElseThrow().images().isEmpty());
        ImageView photo = body(upload(owner, combination, Map.of("kind", "FINISHED", "visibility", "PUBLIC"), TestImages.png(24, 24))
                .expectStatus().isOk(), ImageView.class);
        change(owner, photo.id(), "PACKAGING").expectStatus().isBadRequest();
        assertEquals(photo, combination(owner, combination).orElseThrow().images().getFirst());
        change(owner, photo.id(), "TEST_TILE").expectStatus().isOk();
    }
}
