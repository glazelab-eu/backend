package eu.glazelab.api.controller;

import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.dto.SyncDtos.CombinationPageView;
import eu.glazelab.api.dto.SyncDtos.Resync;
import eu.glazelab.api.dto.SyncDtos.SyncView;
import eu.glazelab.api.repository.ChangeLogRepository;
import eu.glazelab.api.service.SyncService;
import eu.glazelab.api.support.ApiTestBase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who receives what from GET /api/sync: an upsert or a tombstone for exactly the audience of a change (guest, author,
 * club member, member of another club, signed-in outsider, site admin) and nothing, not even the ID, for everyone else.
 */
class SyncApiTest extends ApiTestBase {
    @Autowired SyncService syncService;
    @Autowired ChangeLogRepository changeLog;
    @Autowired TransactionTemplate transactions;

    /** What a client keeps between syncs. */
    record From(String cursor, String epoch) {
        From after(SyncView view) { return new From(view.cursor(), view.epoch()); }
    }

    static RestTestClient.ResponseSpec request(RestTestClient client, String since, String epoch, Integer format) {
        return client.get().uri(uri -> {
            uri.path("/api/sync");
            if (since != null) uri.queryParam("since", since);
            if (epoch != null) uri.queryParam("epoch", epoch);
            if (format != null) uri.queryParam("format", format);
            return uri.build();
        }).exchange().expectStatus().isOk();
    }

    static SyncView sync(RestTestClient client, String since, String epoch, Integer format) {
        return body(request(client, since, epoch, format), SyncView.class);
    }

    /** The cursor a full load starts from: the answer without a cursor. */
    static From start(RestTestClient client) {
        SyncView view = sync(client, null, null, null);
        assertEquals(Resync.CURSOR, view.resync());
        assertTrue(view.glazes().isEmpty() && view.combinations().isEmpty() && view.removedCombinationIds().isEmpty());
        return new From(view.cursor(), view.epoch());
    }

    static SyncView delta(RestTestClient client, From from) {
        SyncView view = sync(client, from.cursor(), from.epoch(), SyncService.FORMAT);
        assertNull(view.resync(), "expected a delta");
        return view;
    }

    static String raw(RestTestClient client, From from) {
        return body(request(client, from.cursor(), from.epoch(), SyncService.FORMAT), String.class);
    }

    /** A published combination with one custom step. */
    static UUID published(RestTestClient client, String title) {
        return created(client.post().uri("/api/combinations")
                .body(Map.of("title", title, "steps", List.of(Map.of("type", "CUSTOM", "label", "Glaze firing")))).exchange());
    }

    static Optional<CombinationView> upserted(SyncView view, UUID id) {
        return view.combinations().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    static Optional<GlazeView> upsertedGlaze(SyncView view, UUID id) {
        return view.glazes().stream().filter(g -> g.id().equals(id)).findFirst();
    }

    /** Six viewers: the author and a member share a club, another user has their own club, plus an outsider, an admin and a guest. */
    final class People {
        final RestTestClient author = user("Author"), member = user("Member"), otherClub = user("OtherClub"),
                outsider = user("Outsider"), admin = siteAdmin(), guest = guest();
        final UUID club = createClub(author, unique("Club"));
        final Map<String, RestTestClient> all = new LinkedHashMap<>();
        Map<String, From> from = new HashMap<>();

        People() {
            joinClub(author, club, member);
            createClub(otherClub, unique("Elsewhere"));
            all.put("author", author); all.put("member", member); all.put("otherClub", otherClub);
            all.put("outsider", outsider); all.put("admin", admin); all.put("guest", guest);
        }

        /**
         * Everyone takes a cursor now (after the setup, which changed their epochs). The rows written so far are
         * dated before the overlap window, so a delta shows exactly what changed afterwards (the window would
         * otherwise bring the setup's public rows again, harmlessly but in the way of "nothing" assertions).
         */
        void start() {
            settle();
            all.forEach((name, client) -> from.put(name, SyncApiTest.start(client)));
        }

        SyncView delta(String name) { return SyncApiTest.delta(all.get(name), from.get(name)); }
        String raw(String name) { return SyncApiTest.raw(all.get(name), from.get(name)); }
    }

    /** Who may see a photo of the author's (the uploader's) visibility: the rule of ImageRecord.visibleTo for the six viewers. */
    static boolean sees(String visibility, String viewer) {
        return switch (visibility) {
            case "PUBLIC" -> true;
            case "CLUB" -> viewer.equals("author") || viewer.equals("member");
            default -> viewer.equals("author");
        };
    }

    private Map<String, Object> visibility(String visibility, UUID club) {
        var body = new HashMap<String, Object>(Map.of("visibility", visibility));
        if (visibility.equals("CLUB")) body.put("clubId", club);
        return body;
    }

    @Test void publishedCombinationReachesEveryoneAndTheCountsMatchTheListings() {
        People people = new People();
        UUID glaze = addGlaze(people.author, "Sync Maker", unique("Blue"), unique("S").replace(" ", ""));
        people.start();
        UUID id = addCombination(people.author, unique("Published"), glaze);
        for (var name : people.all.keySet()) {
            SyncView view = people.delta(name);
            CombinationView seen = upserted(view, id).orElseThrow(() -> new AssertionError(name));
            assertEquals(name.equals("author"), seen.own(), name);
            assertEquals(combination(people.all.get(name), id).orElseThrow(), seen, name);
        }
    }

    @Test void deltaCountsAreTheSizesOfTheCallersListings() {
        People people = new People();
        created(people.author.post().uri("/api/combinations").body(Map.of("title", "Draft", "steps", List.of(), "draft", true)).exchange());
        people.start();
        published(people.member, unique("Counted"));
        for (var name : people.all.keySet()) {
            SyncView view = people.delta(name);
            assertEquals(glazes(people.all.get(name)).size(), view.visibleGlazes(), name);
            assertEquals(combinations(people.all.get(name)).size(), view.visibleCombinations(), name);
        }
    }

    @Test void draftReachesOnlyItsAuthorUntilPublishedAndItsDeletionOnlyTheAuthor() {
        People people = new People();
        people.start();
        UUID draft = created(people.author.post().uri("/api/combinations")
                .body(Map.of("title", "Secret draft", "steps", List.of(), "draft", true)).exchange());
        people.author.patch().uri("/api/combinations/{id}/tags", draft).body(Map.of("add", List.of("hidden-tag"))).exchange()
                .expectStatus().isOk();
        assertTrue(upserted(people.delta("author"), draft).orElseThrow().draft());
        for (var name : List.of("member", "otherClub", "outsider", "admin", "guest"))
            assertFalse(people.raw(name).contains(draft.toString()), name + " must not learn the draft's ID");
        people.author.delete().uri("/api/combinations/{id}", draft).exchange().expectStatus().isNoContent();
        SyncView author = people.delta("author");
        assertTrue(author.removedCombinationIds().contains(draft));
        assertTrue(upserted(author, draft).isEmpty());
        for (var name : List.of("member", "otherClub", "outsider", "admin", "guest"))
            assertFalse(people.raw(name).contains(draft.toString()), name + " must not get the draft's tombstone");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ? AND audience <> 'USER'", Integer.class, draft),
                "a draft's rows name only its author");
    }

    @Test void publishingADraftIsAnUpsertForEveryone() {
        People people = new People();
        UUID draft = created(people.author.post().uri("/api/combinations").body(Map.of("title", "Soon", "steps",
                List.of(Map.of("type", "CUSTOM", "label", "Bisque")), "draft", true)).exchange());
        people.start();
        people.author.post().uri("/api/combinations/{id}/publish", draft).exchange().expectStatus().isNoContent();
        for (var name : people.all.keySet())
            assertFalse(upserted(people.delta(name), draft).orElseThrow(() -> new AssertionError(name)).draft(), name);
    }

    @Test void removingAPublishedCombinationIsATombstoneForEveryone() {
        People people = new People();
        UUID id = published(people.author, unique("Gone soon"));
        people.start();
        people.author.delete().uri("/api/combinations/{id}", id).exchange().expectStatus().isNoContent();
        for (var name : people.all.keySet()) {
            SyncView view = people.delta(name);
            assertTrue(view.removedCombinationIds().contains(id), name);
            assertTrue(upserted(view, id).isEmpty(), name);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"PUBLIC", "CLUB", "PRIVATE"})
    void anUploadReachesExactlyWhoMaySeeThePhoto(String visibility) {
        People people = new People();
        UUID id = published(people.author, unique("Photographed"));
        people.start();
        UUID photo = uploadTile(people.author, id, visibility, visibility.equals("CLUB") ? people.club : null);
        for (var name : people.all.keySet()) {
            if (sees(visibility, name)) {
                CombinationView seen = upserted(people.delta(name), id).orElseThrow(() -> new AssertionError(name));
                assertEquals(List.of(photo), seen.images().stream().map(ImageView::id).toList(), name);
            } else {
                String raw = people.raw(name);
                assertFalse(raw.contains(id.toString()) || raw.contains(photo.toString()), name + " gets nothing");
            }
        }
    }

    /**
     * Every visibility move of a photo: whoever saw it before or sees it after gets the combination (with the photo
     * only if they see it now, under its new ID after leaving PUBLIC); everyone else gets nothing.
     */
    @ParameterizedTest @CsvSource({"PUBLIC,CLUB", "PUBLIC,PRIVATE", "CLUB,PUBLIC", "CLUB,PRIVATE", "PRIVATE,PUBLIC", "PRIVATE,CLUB"})
    void aVisibilityMoveReachesTheUnionOfBeforeAndAfter(String before, String after) {
        People people = new People();
        UUID id = published(people.author, unique("Moved"));
        UUID photo = uploadTile(people.author, id, before, before.equals("CLUB") ? people.club : null);
        people.start();
        ImageView moved = body(people.author.put().uri("/api/images/{id}/visibility", photo).body(visibility(after, people.club))
                .exchange().expectStatus().isOk(), ImageView.class);
        assertEquals(before.equals("PUBLIC"), !moved.id().equals(photo), "leaving PUBLIC rotates the ID");
        for (var name : people.all.keySet()) {
            if (sees(before, name) || sees(after, name)) {
                CombinationView seen = upserted(people.delta(name), id).orElseThrow(() -> new AssertionError(name));
                assertEquals(sees(after, name) ? List.of(moved.id()) : List.of(),
                        seen.images().stream().map(ImageView::id).toList(), name);
            } else {
                String raw = people.raw(name);
                assertFalse(raw.contains(id.toString()) || raw.contains(photo.toString()) || raw.contains(moved.id().toString()), name);
            }
        }
    }

    @Test void aClubPhotoMadePrivateLeavesTheMembersCombinationAndTellsOutsidersNothing() {
        People people = new People();
        UUID id = published(people.author, unique("Club tile"));
        UUID photo = uploadTile(people.member, id, "CLUB", people.club);
        people.start();
        // The member's photo on the author's combination must stay visible to the author: move the author's own instead.
        UUID own = uploadTile(people.author, id, "CLUB", people.club);
        people.from.put("member", start(people.member));
        people.author.put().uri("/api/images/{id}/visibility", own).body(Map.of("visibility", "PRIVATE")).exchange().expectStatus().isOk();
        CombinationView member = upserted(people.delta("member"), id).orElseThrow();
        assertEquals(List.of(photo), member.images().stream().map(ImageView::id).toList());
        for (var name : List.of("otherClub", "outsider", "admin", "guest"))
            assertFalse(people.raw(name).contains(id.toString()), name);
    }

    @Test void detailEditsAndDeletionsOfAClubPhotoReachOnlyTheClub() {
        People people = new People();
        UUID id = published(people.author, unique("Details"));
        UUID photo = uploadTile(people.member, id, "CLUB", people.club);
        people.start();
        people.member.patch().uri("/api/images/{id}", photo).body(Map.of("caption", "Glossy")).exchange().expectStatus().isOk();
        assertEquals("Glossy", upserted(people.delta("author"), id).orElseThrow().images().getFirst().caption());
        for (var name : List.of("otherClub", "outsider", "admin", "guest")) assertFalse(people.raw(name).contains(id.toString()), name);
        people.start();
        // A site admin deletes a photo they cannot see (moderation): the club learns it, the admin does not.
        people.admin.delete().uri("/api/images/{id}", photo).exchange().expectStatus().isNoContent();
        assertTrue(upserted(people.delta("member"), id).orElseThrow().images().isEmpty());
        assertTrue(upserted(people.delta("author"), id).orElseThrow().images().isEmpty());
        for (var name : List.of("otherClub", "outsider", "admin", "guest")) assertFalse(people.raw(name).contains(id.toString()), name);
    }

    @Test void glazeChangesReachEveryoneAndAClubGlazePhotoOnlyTheClub() {
        People people = new People();
        people.start();
        UUID glaze = addGlaze(people.outsider, "Sync Glazes", unique("Teal"), unique("G").replace(" ", ""));
        people.member.patch().uri("/api/glazes/{id}/tags", glaze).body(Map.of("add", List.of("matte"))).exchange().expectStatus().isOk();
        people.member.patch().uri("/api/glazes/{id}", glaze).body(Map.of("name", "Renamed teal")).exchange().expectStatus().isOk();
        for (var name : people.all.keySet()) {
            GlazeView seen = upsertedGlaze(people.delta(name), glaze).orElseThrow(() -> new AssertionError(name));
            assertEquals("Renamed teal", seen.name());
            assertEquals(List.of("matte"), seen.tags());
        }
        people.start();
        UUID photo = created(uploadGlazeImage(people.member, glaze, Map.of("visibility", "CLUB", "clubId", people.club.toString(),
                "kind", "TEST_TILE"), PNG));
        assertEquals(List.of(photo), upsertedGlaze(people.delta("author"), glaze).orElseThrow().images().stream().map(ImageView::id).toList());
        for (var name : List.of("otherClub", "outsider", "admin", "guest")) assertFalse(people.raw(name).contains(glaze.toString()), name);
        people.start();
        people.admin.delete().uri("/api/glazes/{id}", glaze).exchange().expectStatus().isNoContent();
        for (var name : people.all.keySet()) assertTrue(people.delta(name).removedGlazeIds().contains(glaze), name);
    }

    @Test void aGlazeRenameSendsTheGlazeOnlyAndTheStepsFollowItInTheListing() {
        People people = new People();
        UUID glaze = addGlaze(people.author, "Sync Steps", unique("Ochre"), unique("O").replace(" ", ""));
        UUID id = addCombination(people.author, unique("Uses ochre"), glaze);
        people.start();
        people.member.patch().uri("/api/glazes/{id}", glaze).body(Map.of("name", "Golden ochre")).exchange().expectStatus().isOk();
        SyncView view = people.delta("guest");
        assertEquals("Golden ochre", upsertedGlaze(view, glaze).orElseThrow().name());
        // Decided: clients copy the name into the steps; the combinations using the glaze are not re-sent.
        assertTrue(upserted(view, id).isEmpty());
        assertEquals("Golden ochre", combination(people.guest, id).orElseThrow().steps().getFirst().name());
    }

    @Test void variationsFollowTheirSourcesPublicationAndDeletion() {
        People people = new People();
        UUID source = published(people.author, unique("Source"));
        UUID variation = created(people.member.post().uri("/api/combinations").body(Map.of("title", "Variation",
                "steps", List.of(Map.of("type", "CUSTOM", "label", "Glaze fire")), "basedOnId", source)).exchange());
        people.start();
        people.author.delete().uri("/api/combinations/{id}", source).exchange().expectStatus().isNoContent();
        for (var name : people.all.keySet()) {
            SyncView view = people.delta(name);
            assertTrue(view.removedCombinationIds().contains(source), name);
            assertNull(upserted(view, variation).orElseThrow(() -> new AssertionError(name)).basedOnId(), name);
        }
        UUID draft = created(people.author.post().uri("/api/combinations").body(Map.of("title", "Draft source", "steps",
                List.of(Map.of("type", "CUSTOM", "label", "Bisque")), "draft", true)).exchange());
        UUID own = created(people.author.post().uri("/api/combinations").body(Map.of("title", "Own variation", "steps",
                List.of(Map.of("type", "CUSTOM", "label", "Fire")), "basedOnId", draft)).exchange());
        people.start();
        assertNull(combination(people.guest, own).orElseThrow().basedOnId());
        people.author.post().uri("/api/combinations/{id}/publish", draft).exchange().expectStatus().isNoContent();
        for (var name : people.all.keySet())
            assertEquals(draft, upserted(people.delta(name), own).orElseThrow(() -> new AssertionError(name)).basedOnId(), name);
    }

    /** A draft variation of a deleted source: its author gets it without the source, nobody else learns its ID. */
    @Test void aDraftVariationOfADeletedSourceReachesOnlyItsAuthor() {
        People people = new People();
        UUID source = published(people.author, unique("Source"));
        UUID draft = created(people.member.post().uri("/api/combinations").body(Map.of("title", "Draft variation",
                "steps", List.of(), "draft", true, "basedOnId", source)).exchange());
        people.start();
        people.author.delete().uri("/api/combinations/{id}", source).exchange().expectStatus().isNoContent();
        assertNull(upserted(people.delta("member"), draft).orElseThrow().basedOnId());
        for (var name : List.of("author", "otherClub", "outsider", "admin", "guest"))
            assertFalse(people.raw(name).contains(draft.toString()), name + " must not learn the draft's ID");
        // Delivery hides an existing draft anyway; the writer must not rely on that.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ? AND audience <> 'USER'", Integer.class, draft),
                "the draft variation's rows name only its author");
    }

    @Test void membershipChangesChangeTheEpochOfExactlyTheAccountsConcerned() {
        People people = new People();
        people.start();
        // A role change: the promoted member's photo rights change, the admin's do not.
        people.author.put().uri("/api/clubs/{id}/members/{user}", people.club, me(people.member).id()).body(Map.of("role", "ADMIN"))
                .exchange().expectStatus().isOk();
        assertEquals(Resync.EPOCH, sync(people.member, people.from.get("member").cursor(), people.from.get("member").epoch(), SyncService.FORMAT).resync());
        for (var name : List.of("author", "otherClub", "outsider", "admin", "guest")) people.delta(name);
        people.start();
        // Leaving.
        people.author.put().uri("/api/clubs/{id}/members/{user}", people.club, me(people.member).id()).body(Map.of("role", "MEMBER"))
                .exchange().expectStatus().isOk();
        people.from.put("member", start(people.member));
        people.member.delete().uri("/api/clubs/{id}/members/{user}", people.club, me(people.member).id()).exchange().expectStatus().isNoContent();
        assertEquals(Resync.EPOCH, sync(people.member, people.from.get("member").cursor(), people.from.get("member").epoch(), SyncService.FORMAT).resync());
        people.delta("author");
        // Joining, and removal by an admin.
        joinClub(people.author, people.club, people.outsider);
        assertEquals(Resync.EPOCH, sync(people.outsider, people.from.get("outsider").cursor(), people.from.get("outsider").epoch(), SyncService.FORMAT).resync());
        From outsider = start(people.outsider);
        people.author.delete().uri("/api/clubs/{id}/members/{user}", people.club, me(people.outsider).id()).exchange().expectStatus().isNoContent();
        assertEquals(Resync.EPOCH, sync(people.outsider, outsider.cursor(), outsider.epoch(), SyncService.FORMAT).resync());
        people.delta("author");
        // A site-admin grant by SQL.
        From guestTurned = start(people.otherClub);
        jdbc.update("UPDATE app_user SET site_admin = TRUE WHERE id = ?", me(people.otherClub).id());
        assertEquals(Resync.EPOCH, sync(people.otherClub, guestTurned.cursor(), guestTurned.epoch(), SyncService.FORMAT).resync());
        // Another account's epoch never fits.
        From author = start(people.author);
        assertEquals(Resync.EPOCH, sync(people.outsider, author.cursor(), author.epoch(), SyncService.FORMAT).resync());
    }

    @Test void accountDeletionAnonymisesInEveryAudienceAndItsDraftsLeaveNoTrace() {
        People people = new People();
        RestTestClient leaving = user("Leaving");
        joinClub(people.author, people.club, leaving);
        UUID shared = published(people.author, unique("Shared"));
        UUID glaze = addGlaze(leaving, "Sync Leaving", unique("Rust"), unique("R").replace(" ", ""));
        UUID corrected = addGlaze(people.author, "Sync Leaving", unique("Ash"), unique("A").replace(" ", ""));
        leaving.patch().uri("/api/glazes/{id}", corrected).body(Map.of("notes", "Fires well")).exchange().expectStatus().isOk();
        UUID own = published(leaving, unique("Leaving combination"));
        UUID draft = created(leaving.post().uri("/api/combinations").body(Map.of("title", "Leaving draft", "steps", List.of(), "draft", true)).exchange());
        UUID publicPhoto = uploadTile(leaving, shared, "PUBLIC", null);
        UUID clubPhoto = uploadTile(leaving, own, "CLUB", people.club);
        people.start();
        leaving.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        for (var name : people.all.keySet()) {
            SyncView view = people.delta(name);
            assertNull(upserted(view, own).orElseThrow(() -> new AssertionError(name)).author(), name);
            assertNull(upsertedGlaze(view, glaze).orElseThrow().addedBy(), name);
            assertNull(upsertedGlaze(view, corrected).orElseThrow().lastEditedBy(), name);
            ImageView onShared = upserted(view, shared).orElseThrow().images().getFirst();
            assertEquals(publicPhoto, onShared.id());
            assertNull(onShared.uploader(), name);
            List<UUID> clubPhotos = upserted(view, own).orElseThrow().images().stream().map(ImageView::id).toList();
            assertEquals(sees("CLUB", name) ? List.of(clubPhoto) : List.of(), clubPhotos, name);
            String raw = people.raw(name);
            assertFalse(raw.contains(draft.toString()), name);
            assertEquals(sees("CLUB", name), raw.contains(clubPhoto.toString()), name);
        }
    }

    @Test void overlapReDeliveryIsIdempotent() {
        People people = new People();
        From from = start(people.guest);
        UUID id = published(people.author, unique("Twice"));
        SyncView first = delta(people.guest, from);
        // Right after, the overlap window brings the same change again; applying it twice changes nothing.
        SyncView second = delta(people.guest, from.after(first));
        assertEquals(upserted(first, id).orElseThrow(), upserted(second, id).orElseThrow());
        Map<UUID, CombinationView> local = new HashMap<>();
        combinations(people.guest).forEach(view -> local.put(view.id(), view));
        Map<UUID, CombinationView> once = apply(new HashMap<>(local), first);
        assertEquals(once, apply(apply(new HashMap<>(local), first), second));
    }

    private static Map<UUID, CombinationView> apply(Map<UUID, CombinationView> local, SyncView delta) {
        delta.combinations().forEach(view -> local.put(view.id(), view));
        delta.removedCombinationIds().forEach(local::remove);
        return local;
    }

    /** The overlap window by time: a row below the cursor's revision written within it comes again, an older one does not. */
    @Test void theOverlapWindowCoversRecentRowsBelowTheCursorOnly() {
        People people = new People();
        UUID recent = published(people.author, unique("Late commit")), old = published(people.author, unique("Long ago"));
        Instant now = Instant.now();
        // Only these rows speak for the two items, both below the cursor's revision: as if their transactions committed late.
        jdbc.update("DELETE FROM change_log WHERE item_id IN (?, ?)", recent, old);
        insertRow("COMBINATION", recent, now.minus(SyncService.OVERLAP).plusSeconds(20));
        insertRow("COMBINATION", old, now.minus(SyncService.OVERLAP).minusSeconds(60));
        From from = new From(changeLog.top() + "." + now.toEpochMilli(), start(people.guest).epoch());
        SyncView view = delta(people.guest, from);
        assertTrue(upserted(view, recent).isPresent());
        assertTrue(upserted(view, old).isEmpty());
    }

    void settle() { settle(jdbc); }

    /** Dates every recent row an hour back, out of every later delta's overlap window. */
    static void settle(JdbcTemplate jdbc) {
        Timestamp before = Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS));
        jdbc.update("UPDATE change_log SET changed_at = ? WHERE changed_at > ?", before, before);
    }

    private void insertRow(String kind, UUID item, Instant at) {
        jdbc.update("INSERT INTO change_log (changed_at, kind, item_id, audience) VALUES (?, ?, ?, 'PUBLIC')", Timestamp.from(at), kind, item);
    }

    @Test void resyncAnswersForAMissingOrForeignCursorAnotherFormatAnOldCursorAndTooManyChanges() {
        RestTestClient client = user("Resync");
        From from = start(client);
        assertEquals(Resync.CURSOR, sync(client, "nonsense", from.epoch(), SyncService.FORMAT).resync());
        assertEquals(Resync.CURSOR, sync(client, (changeLog.top() + 1000) + "." + Instant.now().toEpochMilli(), from.epoch(), SyncService.FORMAT).resync());
        assertEquals(Resync.CURSOR, sync(client, "1." + Instant.now().plusSeconds(3600).toEpochMilli(), from.epoch(), SyncService.FORMAT).resync());
        assertEquals(Resync.FORMAT, sync(client, from.cursor(), from.epoch(), SyncService.FORMAT + 1).resync());
        assertEquals(Resync.FORMAT, sync(client, from.cursor(), from.epoch(), null).resync());
        assertEquals(Resync.EPOCH, sync(client, from.cursor(), null, SyncService.FORMAT).resync());
        long revision = Long.parseLong(from.cursor().substring(0, from.cursor().indexOf('.')));
        String old = revision + "." + Instant.now().minus(SyncService.RETENTION).minusSeconds(60).toEpochMilli();
        SyncView expired = sync(client, old, from.epoch(), SyncService.FORMAT);
        assertEquals(Resync.EXPIRED, expired.resync());
        assertEquals(combinations(client).size(), expired.visibleCombinations());
        assertEquals(glazes(client).size(), expired.visibleGlazes());
        // The resync answer's cursor is a valid start.
        assertNull(sync(client, expired.cursor(), expired.epoch(), SyncService.FORMAT).resync());
        From before = start(client);
        // Rows dated outside the overlap window, so later tests' deltas never meet them.
        Instant longAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i <= SyncService.MAX_ITEMS; i++) rows.add(new Object[] {Timestamp.from(longAgo), UUID.randomUUID()});
        jdbc.batchUpdate("INSERT INTO change_log (changed_at, kind, item_id, audience) VALUES (?, 'COMBINATION', ?, 'PUBLIC')", rows);
        assertEquals(Resync.TOO_MANY, sync(client, before.cursor(), before.epoch(), SyncService.FORMAT).resync());
    }

    @Test void thePurgeDropsRowsPastTheRetentionAndKeepsTheNewest() {
        UUID old = UUID.randomUUID(), kept = UUID.randomUUID();
        insertRow("GLAZE", old, Instant.now().minus(SyncService.RETENTION).minus(SyncService.OVERLAP).minusSeconds(60));
        // Past the retention but inside its overlap: a cursor just inside the retention still needs it. (Written
        // before the newest row, which the purge keeps whatever its age.)
        UUID edge = UUID.randomUUID();
        insertRow("GLAZE", edge, Instant.now().minus(SyncService.RETENTION).minusSeconds(30));
        insertRow("GLAZE", kept, Instant.now().minus(SyncService.RETENTION).plusSeconds(600));
        long top = changeLog.top();
        assertTrue(syncService.purge() >= 1);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ?", Integer.class, old));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ?", Integer.class, kept));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ?", Integer.class, edge));
        assertEquals(top, changeLog.top());
    }

    /** Even when every row is past the retention the newest stays, so the newest revision (and every cursor below it) never goes back. */
    @Test void thePurgeKeepsTheNewestRowWhenEverythingIsOld() {
        transactions.executeWithoutResult(status -> {
            insertRow("GLAZE", UUID.randomUUID(), Instant.now().minusSeconds(5));
            insertRow("GLAZE", UUID.randomUUID(), Instant.now());
            long top = changeLog.top();
            assertTrue(changeLog.purge(Instant.now().plus(1, ChronoUnit.DAYS)) >= 1);
            assertEquals(1, changeLog.count());
            assertEquals(top, changeLog.top());
            // Leave the shared database's log as it was.
            status.setRollbackOnly();
        });
    }

    @Test void pagesCoverExactlyTheListingWithTheListingsViews() {
        People people = new People();
        UUID id = published(people.author, unique("Paged"));
        published(people.member, unique("Paged too"));
        published(people.outsider, unique("Paged three"));
        uploadTile(people.member, id, "CLUB", people.club);
        created(people.author.post().uri("/api/combinations").body(Map.of("title", "Paged draft", "steps", List.of(), "draft", true)).exchange());
        for (var name : List.of("author", "outsider", "guest")) {
            RestTestClient client = people.all.get(name);
            Map<UUID, CombinationView> paged = new HashMap<>();
            UUID after = null;
            do {
                UUID previous = after;
                CombinationPageView page = body(client.get().uri(uri -> {
                    uri.path("/api/sync/combinations").queryParam("limit", 1000);
                    if (previous != null) uri.queryParam("after", previous);
                    return uri.build();
                }).exchange().expectStatus().isOk(), CombinationPageView.class);
                page.combinations().forEach(view -> assertNull(paged.put(view.id(), view), "each combination once"));
                after = page.next();
            } while (after != null);
            Map<UUID, CombinationView> listed = combinations(client).stream().collect(Collectors.toMap(CombinationView::id, view -> view));
            assertEquals(listed, paged, name);
        }
        // The database orders UUIDs like their text (unsigned), not like UUID.compareTo.
        List<UUID> sorted = combinations(people.guest).stream().map(CombinationView::id).sorted(Comparator.comparing(UUID::toString)).toList();
        CombinationPageView first = body(people.guest.get().uri("/api/sync/combinations?limit=2").exchange().expectStatus().isOk(), CombinationPageView.class);
        assertEquals(Set.copyOf(sorted.subList(0, 2)), first.combinations().stream().map(CombinationView::id).collect(Collectors.toSet()));
        assertEquals(sorted.get(1), first.next());
        // The next page continues right after it, so a resumed first sync neither skips nor repeats.
        CombinationPageView second = body(people.guest.get().uri("/api/sync/combinations?limit=2&after=" + first.next())
                .exchange().expectStatus().isOk(), CombinationPageView.class);
        assertEquals(Set.copyOf(sorted.subList(2, Math.min(4, sorted.size()))), second.combinations().stream().map(CombinationView::id).collect(Collectors.toSet()));
        people.guest.get().uri("/api/sync/combinations?after=not-an-id").exchange().expectStatus().isBadRequest();
    }

    /**
     * Delivery does not trust the writers alone: a row naming someone else's existing draft (as a writer bug could
     * write, e.g. a draft logged as published) renders nothing and is no tombstone either, so no audience learns the
     * ID. An existing item that does not render was never visible to the caller (glazes are public, publication is
     * one-way, a draft is its author's), so a client never holds a copy to drop.
     */
    @Test void aStrayRowForSomeoneElsesDraftNamesItToNobodyButItsAuthor() {
        People people = new People();
        UUID draft = created(people.author.post().uri("/api/combinations")
                .body(Map.of("title", "Stray draft", "steps", List.of(), "draft", true)).exchange());
        people.start();
        jdbc.update("INSERT INTO change_log (changed_at, kind, item_id, audience) VALUES (?, 'COMBINATION', ?, 'PUBLIC')",
                Timestamp.from(Instant.now()), draft);
        jdbc.update("INSERT INTO change_log (changed_at, kind, item_id, audience, audience_id) VALUES (?, 'COMBINATION', ?, 'CLUB', ?)",
                Timestamp.from(Instant.now()), draft, people.club);
        assertTrue(upserted(people.delta("author"), draft).orElseThrow().draft());
        for (var name : List.of("member", "otherClub", "outsider", "admin", "guest"))
            assertFalse(people.raw(name).contains(draft.toString()), name + " must not learn the draft's ID");
    }

    /**
     * A member who joins after a club photo left the club loads in full (another epoch); the overlap window of their
     * next delta still holds the club row of that move, which brings the combination but never the photo or its ID.
     */
    @Test void aMemberJoiningAfterAClubPhotoWasHiddenLearnsNothingOfItFromTheOverlap() {
        People people = new People();
        UUID id = published(people.author, unique("Hidden later"));
        UUID photo = uploadTile(people.author, id, "CLUB", people.club);
        people.start();
        people.author.put().uri("/api/images/{id}/visibility", photo).body(Map.of("visibility", "PRIVATE")).exchange().expectStatus().isOk();
        joinClub(people.author, people.club, people.outsider);
        From joined = start(people.outsider);
        String raw = raw(people.outsider, joined);
        assertTrue(raw.contains(id.toString()), "the club row of the move lies in the overlap window");
        assertFalse(raw.contains(photo.toString()), "the hidden photo stays unnamed");
    }

    /** A removed member's next delta (after the full load their new epoch forces) carries no club row from the overlap window. */
    @Test void aRemovedMemberGetsNoClubRowsFromTheOverlapWindow() {
        People people = new People();
        UUID id = published(people.author, unique("Club only"));
        people.start();
        UUID photo = uploadTile(people.author, id, "CLUB", people.club);
        people.author.delete().uri("/api/clubs/{id}/members/{user}", people.club, me(people.member).id()).exchange().expectStatus().isNoContent();
        assertEquals(Resync.EPOCH, sync(people.member, people.from.get("member").cursor(), people.from.get("member").epoch(), SyncService.FORMAT).resync());
        String raw = raw(people.member, start(people.member));
        assertFalse(raw.contains(id.toString()) || raw.contains(photo.toString()), "no club row reaches a former member");
    }

    /**
     * The cursor moves only with rows the caller may receive: if it carried the global newest revision, watching it
     * would tell anyone when somebody edits a draft or a private photo (rows nobody else receives).
     */
    @Test void othersPrivateChangesDoNotMoveTheCursor() {
        People people = new People();
        UUID id = published(people.author, unique("Watched"));
        UUID draft = created(people.author.post().uri("/api/combinations")
                .body(Map.of("title", "Quiet draft", "steps", List.of(), "draft", true)).exchange());
        people.start();
        people.author.put().uri("/api/combinations/{id}", draft).body(Map.of("title", "Quieter", "steps", List.of())).exchange()
                .expectStatus().isNoContent();
        uploadTile(people.author, id, "PRIVATE", null);
        uploadTile(people.author, id, "CLUB", people.club);
        for (var name : List.of("otherClub", "outsider", "admin", "guest"))
            assertEquals(revision(people.from.get(name).cursor()), revision(people.delta(name).cursor()), name);
        for (var name : List.of("author", "member"))
            assertTrue(revision(people.delta(name).cursor()) > revision(people.from.get(name).cursor()), name);
        // A start cursor too: an outsider's equals the newest row they may receive, below the author's.
        assertTrue(revision(start(people.outsider).cursor()) < revision(start(people.author).cursor()));
    }

    static long revision(String cursor) {
        return Long.parseLong(cursor.substring(0, cursor.indexOf('.')));
    }

    /**
     * The epoch names the account: two accounts with the same clubs and roles (here none) never share one, and a
     * guest's differs, so a device that switches accounts never applies a delta to another account's copy.
     */
    @Test void accountsWithTheSameClubsNeverShareAnEpoch() {
        RestTestClient first = user("Plain"), second = user("Plain"), guest = guest();
        From mine = start(first);
        assertNotEquals(mine.epoch(), start(second).epoch());
        assertNotEquals(mine.epoch(), start(guest).epoch());
        assertEquals(Resync.EPOCH, sync(second, mine.cursor(), mine.epoch(), SyncService.FORMAT).resync());
        assertEquals(mine.epoch(), start(first).epoch(), "stable for one account");
    }

    /** Cursors this server never answered start over instead of failing; the page limit is clamped. */
    @Test void malformedCursorsAndLimitsAreHarmless() {
        RestTestClient client = user("Odd");
        From from = start(client);
        for (String since : List.of("", ".", "5", "-1.5", "1.-5", "1.2.3", " 1.2", "1e3.5", "x.y",
                Long.MAX_VALUE + "." + Long.MAX_VALUE, "99999999999999999999.1"))
            assertEquals(Resync.CURSOR, sync(client, since, from.epoch(), SyncService.FORMAT).resync(), since);
        published(client, unique("Paged"));
        published(client, unique("Paged"));
        for (int limit : List.of(0, -3)) {
            CombinationPageView page = body(client.get().uri("/api/sync/combinations?limit=" + limit).exchange().expectStatus().isOk(),
                    CombinationPageView.class);
            assertEquals(1, page.combinations().size(), "limit " + limit);
            assertNotNull(page.next());
        }
        client.get().uri("/api/sync/combinations?limit=99999999999").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/sync?format=one").exchange().expectStatus().isBadRequest();
    }

    @Test void guestsAndSignedInUsersAlikeSeeOnlyPublicRowsWithoutAnAccount() {
        People people = new People();
        people.start();
        UUID id = published(people.author, unique("Visible"));
        uploadTile(people.author, id, "PRIVATE", null);
        Predicate<ImageView> privateOnes = image -> image.visibility().name().equals("PRIVATE");
        assertTrue(upserted(people.delta("guest"), id).orElseThrow().images().stream().noneMatch(privateOnes));
        assertTrue(upserted(people.delta("author"), id).orElseThrow().images().stream().anyMatch(privateOnes));
    }
}
