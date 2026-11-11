package eu.glazelab.api.controller;

import eu.glazelab.api.dto.AvatarDtos.AuthorAvatarView;
import eu.glazelab.api.dto.ClubDtos.MemberView;
import eu.glazelab.api.support.ApiTestBase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** The member list and the authors' pictures need a constant number of statements however many accounts have pictures. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:avatarqueries;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false" })
class AvatarQueryApiTest extends ApiTestBase {
    /** An account written with SQL (it never signs in), with a picture row (the listings never read its objects). */
    private UUID accountWithPicture(String name) {
        UUID id = UUID.randomUUID(), avatar = UUID.randomUUID();
        String address = email(name);
        jdbc.update("INSERT INTO app_user (id, email, email_key, password_hash, display_name, email_verified, session_version, site_admin) VALUES (?, ?, ?, 'unusable', ?, TRUE, 0, FALSE)",
                id, address, address, name);
        jdbc.update("INSERT INTO user_avatar (id, user_id, storage_key, upload_sha256, stored_sha256, dhash, created_at)"
                + " VALUES (?, ?, ?, ?, ?, 0, ?)", avatar, id, avatar + ".jpg", "a".repeat(64), "b".repeat(64), Timestamp.from(Instant.now()));
        return id;
    }

    private void member(UUID club, UUID user) {
        jdbc.update("INSERT INTO membership (club_id, user_id, role) VALUES (?, ?, 'MEMBER')", club, user);
    }

    private void published(UUID author, String title) {
        jdbc.update("INSERT INTO combination (id, title, created_by, created_at, published_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), title, author, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private static List<MemberView> members(RestTestClient client, UUID club) {
        return body(client.get().uri("/api/clubs/{id}/members", club).exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<MemberView>>() {});
    }

    private static List<AuthorAvatarView> authors(RestTestClient client) {
        return body(client.get().uri("/api/combinations/author-avatars").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<AuthorAvatarView>>() {});
    }

    @Test void membersAndAuthorsWithPicturesUseConstantStatements() {
        RestTestClient admin = user("Admin");
        UUID club = createClub(admin, unique("Many pictures"));
        member(club, accountWithPicture("Member0"));
        published(accountWithPicture("Author0"), "First");
        Measured<List<MemberView>> small = measured(() -> members(admin, club));
        Measured<List<AuthorAvatarView>> fewAuthors = measured(() -> authors(admin));
        assertEquals(2, small.result().size());
        for (int n = 1; n < 30; n++) {
            member(club, accountWithPicture("Member" + n));
            UUID author = accountWithPicture("Author" + n);
            published(author, "Combination " + n);
            published(author, "Another " + n);
        }
        Measured<List<MemberView>> large = measured(() -> members(admin, club));
        Measured<List<AuthorAvatarView>> manyAuthors = measured(() -> authors(admin));
        assertEquals(31, large.result().size());
        assertEquals(30, large.result().stream().filter(member -> member.avatarId() != null).count());
        assertTrue(manyAuthors.result().size() >= 30, manyAuthors.result().toString());
        assertTrue(small.statements() > 0, "statistics must count");
        assertEquals(small.statements(), large.statements(), "member list statements");
        assertEquals(fewAuthors.statements(), manyAuthors.statements(), "author picture statements");
    }
}
