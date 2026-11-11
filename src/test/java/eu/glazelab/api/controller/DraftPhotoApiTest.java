package eu.glazelab.api.controller;

import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Photos of a draft: stored with the visibility their author chose, effective as PRIVATE until the combination is
 * published, so nobody else (club members, club admins, site admins, guests) learns that they exist.
 */
class DraftPhotoApiTest extends ApiTestBase {
    @Autowired ImageStorage storage;

    private static final String WEEK_IN_THE_BROWSER = "max-age=604800, private, immutable";
    private static final String YEAR_PUBLIC = "max-age=31536000, public, immutable";

    /** An author with a club (the member joined it, the author is its admin), a draft and three photos. */
    private final class Setup {
        final RestTestClient author = user("Author"), member = user("Member"), clubAdmin = user("ClubAdmin"),
                other = user("Other"), admin = siteAdmin(), guest = guest();
        final UUID club = createClub(clubAdmin, unique("Club"));
        final UUID draft;
        final UUID publicPhoto, clubPhoto, privatePhoto;

        Setup() {
            joinClub(clubAdmin, club, author);
            joinClub(clubAdmin, club, member);
            draft = created(author.post().uri("/api/combinations").body(Map.of("title", unique("Draft"), "steps",
                    List.of(Map.of("type", "CUSTOM", "label", "Bisque")), "draft", true)).exchange());
            publicPhoto = uploadTile(author, draft, "PUBLIC", null);
            clubPhoto = uploadTile(author, draft, "CLUB", club);
            privatePhoto = uploadTile(author, draft, "PRIVATE", null);
        }

        Set<UUID> photos() { return Set.of(publicPhoto, clubPhoto, privatePhoto); }
        Map<String, RestTestClient> outsiders() {
            return Map.of("member", member, "clubAdmin", clubAdmin, "other", other, "admin", admin, "guest", guest);
        }
        void publish() { author.post().uri("/api/combinations/{id}/publish", draft).exchange().expectStatus().isNoContent(); }
    }

    private static ResponseSpec get(RestTestClient client, UUID id, String size) {
        return client.get().uri("/api/images/" + id + size).exchange();
    }

    private String storageKey(UUID image) {
        return jdbc.queryForObject("SELECT storage_key FROM image WHERE id = ?", String.class, image);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test void theAuthorUploadsToADraftAndSeesThePhotosWithTheChosenVisibility() {
        Setup s = new Setup();
        CombinationView own = combination(s.author, s.draft).orElseThrow();
        assertTrue(own.draft());
        assertFalse(own.stepsLocked(), "only the author's own photos exist on a draft");
        var byId = new HashMap<UUID, ImageView>();
        own.images().forEach(image -> byId.put(image.id(), image));
        assertEquals(Map.of(s.publicPhoto, "PUBLIC", s.clubPhoto, "CLUB", s.privatePhoto, "PRIVATE"),
                Map.of(s.publicPhoto, byId.get(s.publicPhoto).visibility().name(), s.clubPhoto, byId.get(s.clubPhoto).visibility().name(),
                        s.privatePhoto, byId.get(s.privatePhoto).visibility().name()));
        assertEquals(s.club, byId.get(s.clubPhoto).clubId());
        assertTrue(own.images().stream().allMatch(image -> image.own() && image.canDelete()));
        // Until publication every photo of a draft is the author's browser's only, PUBLIC ones included.
        for (UUID photo : s.photos())
            for (String size : List.of("", "/card"))
                get(s.author, photo, size).expectStatus().isOk()
                        .expectHeader().valueEquals("Cache-Control", WEEK_IN_THE_BROWSER)
                        .expectHeader().valueEquals("X-Accel-Expires", "0");
    }

    @Test void nobodyElseCanReachADraftsPhotoAndEveryAnswerIsThatOfAnUnknownId() {
        Setup s = new Setup();
        UUID unknown = UUID.randomUUID();
        Map<String, BiFunction<RestTestClient, UUID, ResponseSpec>> calls = new java.util.LinkedHashMap<>();
        calls.put("full size", (client, id) -> get(client, id, ""));
        calls.put("card", (client, id) -> get(client, id, "/card"));
        calls.put("report", (client, id) -> client.post().uri("/api/images/{id}/reports", id).body(Map.of()).exchange());
        calls.put("details", (client, id) -> client.patch().uri("/api/images/{id}", id).body(Map.of("caption", "x")).exchange());
        calls.put("visibility", (client, id) -> client.put().uri("/api/images/{id}/visibility", id)
                .body(Map.of("visibility", "PRIVATE")).exchange());
        calls.put("delete", (client, id) -> client.delete().uri("/api/images/{id}", id).exchange());
        s.outsiders().forEach((name, client) -> {
            for (var call : calls.entrySet()) {
                String expected = statusAndBody(call.getValue().apply(client, unknown));
                for (UUID photo : s.photos())
                    assertEquals(expected, statusAndBody(call.getValue().apply(client, photo)), name + " " + call.getKey());
            }
            if (!name.equals("guest")) assertTrue(combination(client, s.draft).isEmpty(), name);
            assertTrue(combinations(client).stream().flatMap(c -> c.images().stream()).noneMatch(i -> s.photos().contains(i.id())), name);
        });
        // Nothing changed: every photo is still there, unreported, as the author left it.
        assertEquals(3, count("SELECT COUNT(*) FROM image WHERE combination_id = ?", s.draft));
        assertEquals(0, count("SELECT COUNT(*) FROM image_report WHERE image_id IN (?, ?, ?)", s.publicPhoto, s.clubPhoto, s.privatePhoto));
        assertEquals(3, combination(s.author, s.draft).orElseThrow().images().stream().filter(i -> i.caption() == null).count());
    }

    @Test void publishingAppliesTheChosenVisibilitiesAtOnceUnderTheSameIds() {
        Setup s = new Setup();
        s.publish();
        assertEquals(Set.of(s.publicPhoto), ids(combination(s.guest, s.draft).orElseThrow()));
        assertEquals(Set.of(s.publicPhoto), ids(combination(s.other, s.draft).orElseThrow()));
        assertEquals(Set.of(s.publicPhoto), ids(combination(s.admin, s.draft).orElseThrow()));
        assertEquals(Set.of(s.publicPhoto, s.clubPhoto), ids(combination(s.member, s.draft).orElseThrow()));
        assertEquals(s.photos(), ids(combination(s.author, s.draft).orElseThrow()));
        for (String size : List.of("", "/card")) {
            get(s.guest, s.publicPhoto, size).expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control", YEAR_PUBLIC).expectHeader().valueEquals("X-Accel-Expires", "60");
            get(s.member, s.clubPhoto, size).expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control", WEEK_IN_THE_BROWSER);
            get(s.guest, s.clubPhoto, size).expectStatus().isNotFound();
            get(s.member, s.privatePhoto, size).expectStatus().isNotFound();
        }
        // A club admin and site admins now reach the photos as on any published combination.
        s.clubAdmin.delete().uri("/api/images/{id}", s.clubPhoto).exchange().expectStatus().isNoContent();
        assertEquals(1, count("SELECT COUNT(*) FROM image_deletion WHERE image_id = ?", s.clubPhoto));
    }

    @Test void theAuthorEditsAndRemovesADraftsPhotosWithoutALogEntry() {
        Setup s = new Setup();
        s.author.patch().uri("/api/images/{id}", s.privatePhoto).body(Map.of("caption", "Glossy")).exchange().expectStatus().isOk();
        ImageView moved = body(s.author.put().uri("/api/images/{id}/visibility", s.publicPhoto)
                .body(Map.of("visibility", "CLUB", "clubId", s.club)).exchange().expectStatus().isOk(), ImageView.class);
        s.member.get().uri("/api/images/{id}", moved.id()).exchange().expectStatus().isNotFound();
        String key = storageKey(s.privatePhoto);
        s.author.delete().uri("/api/images/{id}", s.privatePhoto).exchange().expectStatus().isNoContent();
        assertStored(storage, key, false);
        // Nobody but the author ever saw it: the moderation log does not name a draft (nor its title, which may be blank).
        assertEquals(0, count("SELECT COUNT(*) FROM image_deletion WHERE combination_id = ?", s.draft));
        assertEquals(2, combination(s.author, s.draft).orElseThrow().images().size());
    }

    @Test void deletingADraftRemovesItsPhotosRecordsAndObjects() {
        Setup s = new Setup();
        var keys = s.photos().stream().map(this::storageKey).toList();
        s.author.delete().uri("/api/combinations/{id}", s.draft).exchange().expectStatus().isNoContent();
        assertEquals(0, count("SELECT COUNT(*) FROM image WHERE combination_id = ?", s.draft));
        assertEquals(0, count("SELECT COUNT(*) FROM image_deletion WHERE combination_id = ?", s.draft));
        keys.forEach(key -> assertStored(storage, key, false));
    }

    @Test void deletingTheAccountRemovesItsDraftsWithTheirPhotos() {
        Setup s = new Setup();
        UUID authorId = me(s.author).id();
        var keys = s.photos().stream().map(this::storageKey).toList();
        s.author.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().is2xxSuccessful();
        assertEquals(0, count("SELECT COUNT(*) FROM combination WHERE id = ?", s.draft));
        assertEquals(0, count("SELECT COUNT(*) FROM image WHERE combination_id = ?", s.draft));
        assertEquals(0, count("SELECT COUNT(*) FROM image_deletion WHERE combination_id = ? OR uploader_id = ?", s.draft, authorId));
        keys.forEach(key -> assertStored(storage, key, false));
    }

    @Test void aBanRemovesTheDraftsWithTheirPhotos() {
        Setup s = new Setup();
        UUID authorId = me(s.author).id();
        var keys = s.photos().stream().map(this::storageKey).toList();
        s.admin.post().uri("/api/admin/users/{id}/ban", authorId).body(Map.of("reason", "Spam")).exchange().expectStatus().is2xxSuccessful();
        assertEquals(0, count("SELECT COUNT(*) FROM combination WHERE id = ?", s.draft));
        assertEquals(0, count("SELECT COUNT(*) FROM image WHERE combination_id = ?", s.draft));
        assertEquals(0, count("SELECT COUNT(*) FROM image_deletion WHERE combination_id = ?", s.draft));
        keys.forEach(key -> assertStored(storage, key, false));
    }

    @Test void syncCarriesADraftsPhotosOnlyToItsAuthorUntilPublished() {
        Setup s = new Setup();
        SyncApiTest.settle(jdbc);
        var from = new HashMap<String, SyncApiTest.From>();
        var everyone = new HashMap<>(s.outsiders());
        everyone.put("author", s.author);
        everyone.forEach((name, client) -> from.put(name, SyncApiTest.start(client)));
        UUID late = uploadTile(s.author, s.draft, "PUBLIC", null);
        s.author.put().uri("/api/images/{id}/visibility", s.privatePhoto).body(Map.of("visibility", "PUBLIC")).exchange()
                .expectStatus().isOk();
        var authorView = SyncApiTest.upserted(SyncApiTest.delta(s.author, from.get("author")), s.draft).orElseThrow();
        assertTrue(authorView.draft());
        assertTrue(ids(authorView).containsAll(List.of(late, s.privatePhoto)));
        s.outsiders().forEach((name, client) -> {
            String raw = SyncApiTest.raw(client, from.get(name));
            assertFalse(raw.contains(s.draft.toString()) || raw.contains(late.toString()), name + " must not learn of the draft");
        });
        assertEquals(0, count("SELECT COUNT(*) FROM change_log WHERE item_id = ? AND audience <> 'USER'", s.draft),
                "a draft's photo rows name only its author");
        s.publish();
        assertEquals(Set.of(late, s.publicPhoto, s.privatePhoto),
                ids(SyncApiTest.upserted(SyncApiTest.delta(s.guest, from.get("guest")), s.draft).orElseThrow()));
        assertTrue(ids(SyncApiTest.upserted(SyncApiTest.delta(s.member, from.get("member")), s.draft).orElseThrow()).contains(s.clubPhoto));
    }

    private static Set<UUID> ids(CombinationView view) {
        return view.images().stream().map(ImageView::id).collect(Collectors.toSet());
    }
}
