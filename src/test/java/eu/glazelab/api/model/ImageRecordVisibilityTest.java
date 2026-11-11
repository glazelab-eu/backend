package eu.glazelab.api.model;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The scalar rule that ImageRecord.visibleTo and the Target projection share. */
class ImageRecordVisibilityTest {
    private final UUID uploader = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID club = UUID.randomUUID();

    private boolean visible(ImageVisibility visibility, UUID clubId, UUID userId, Set<UUID> clubIds) {
        return ImageRecord.visibleTo(visibility, uploader, clubId, userId, clubIds);
    }

    @Test void publicImagesAreVisibleToEveryone() {
        assertTrue(visible(ImageVisibility.PUBLIC, null, null, Set.of()));
        assertTrue(visible(ImageVisibility.PUBLIC, null, other, Set.of()));
        assertTrue(visible(ImageVisibility.PUBLIC, null, uploader, Set.of()));
    }

    @Test void clubImagesAreVisibleToMembersOfThatClubOnly() {
        assertTrue(visible(ImageVisibility.CLUB, club, other, Set.of(club)));
        assertFalse(visible(ImageVisibility.CLUB, club, other, Set.of(UUID.randomUUID())));
        assertFalse(visible(ImageVisibility.CLUB, club, other, Set.of()));
        assertFalse(visible(ImageVisibility.CLUB, club, null, Set.of(club)), "guests have no clubs");
        assertFalse(visible(ImageVisibility.CLUB, null, other, Set.of(club)), "a club image without club is hidden");
    }

    @Test void clubImagesStayVisibleToTheirUploaderAfterLeaving() {
        assertTrue(visible(ImageVisibility.CLUB, club, uploader, Set.of()));
        assertTrue(visible(ImageVisibility.CLUB, null, uploader, Set.of()));
        assertFalse(visible(ImageVisibility.CLUB, club, null, Set.of(club)), "guests have no clubs");
    }

    @Test void privateImagesAreVisibleToTheirUploaderOnly() {
        assertTrue(visible(ImageVisibility.PRIVATE, null, uploader, Set.of()));
        assertFalse(visible(ImageVisibility.PRIVATE, null, other, Set.of(club)));
        assertFalse(visible(ImageVisibility.PRIVATE, null, null, Set.of()));
    }

    @Test void privateImageOfADeletedUploaderIsHiddenFromEveryone() {
        assertFalse(ImageRecord.visibleTo(ImageVisibility.PRIVATE, null, null, other, Set.of()));
        assertFalse(ImageRecord.visibleTo(ImageVisibility.PRIVATE, null, null, null, Set.of()));
    }
}
