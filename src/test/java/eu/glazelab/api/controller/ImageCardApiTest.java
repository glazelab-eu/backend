package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** The card-size copy of every picture: its size, and the same visibility, 404 and cache rules as the full size. */
class ImageCardApiTest extends ApiTestBase {
    @Autowired ImageRecordRepository records;
    @Autowired ImageStorage storage;

    private static EntityExchangeResult<byte[]> download(RestTestClient client, String url) {
        return client.get().uri(url).exchange().expectBody(byte[].class).returnResult();
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private String key(UUID image) { return records.findById(image).orElseThrow().getStorageKey(); }

    @Test void theCardIsASmallUprightCopyOfTheFullSize() throws IOException {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Card"), "Blue", "PC-20");
        byte[] upload = TestImages.withExifOrientation(TestImages.photo(3000, 2000), 6, true);
        ImageView view = body(uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), upload)
                .expectStatus().isOk(), ImageView.class);
        assertEquals("/api/images/" + view.id() + "/card", view.cardUrl());
        byte[] full = download(guest(), view.url()).getResponseBody();
        byte[] card = download(guest(), view.cardUrl()).getResponseBody();
        BufferedImage fullImage = decode(full), cardImage = decode(card);
        assertEquals(List.of(1067, 1600), List.of(fullImage.getWidth(), fullImage.getHeight()));
        assertEquals(List.of(320, ImageEncoder.CARD_SIDE), List.of(cardImage.getWidth(), cardImage.getHeight()), "upright, like the full size");
        assertTrue(TestImages.hasMarker(card, 0xc2), "progressive JPEG");
        assertFalse(TestImages.hasMarker(card, 0xe1), "Exif stripped");
        assertTrue(card.length * 5 < full.length, "full " + full.length + " bytes, card " + card.length);
        assertArrayEquals(card, storage.get(ImageStorage.cardKey(key(view.id()))));
    }

    @Test void theCardFollowsTheFullSizesVisibilityAndCacheRules() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cyd = user("Cyd");
        UUID club = createClub(ada, unique("Kiln"));
        joinClub(ada, club, bea);
        joinClub(cyd, createClub(cyd, unique("Other kiln")), user("Dee"));
        UUID combo = addCombination(ada, unique("Parity"), addGlaze(ada, unique("Parity"), "Blue", "PC-20"));
        List<UUID> pictures = List.of(uploadTile(ada, combo, "PUBLIC", null), uploadTile(ada, combo, "CLUB", club),
                uploadTile(ada, combo, "PRIVATE", null), UUID.randomUUID());
        for (UUID picture : pictures) {
            for (RestTestClient viewer : List.of(ada, bea, cyd, guest())) {
                var full = download(viewer, "/api/images/" + picture);
                var card = download(viewer, "/api/images/" + picture + "/card");
                assertEquals(full.getStatus(), card.getStatus(), picture.toString());
                for (String header : List.of("Cache-Control", "X-Accel-Expires", "Content-Type"))
                    assertEquals(full.getResponseHeaders().get(header), card.getResponseHeaders().get(header), header);
                if (card.getStatus().value() == 200)
                    assertEquals(MediaType.IMAGE_JPEG, card.getResponseHeaders().getContentType());
                else
                    assertTrue(new String(card.getResponseBody()).contains(ImageController.IMAGE_NOT_FOUND));
            }
        }
        assertEquals(200, download(guest(), "/api/images/" + pictures.getFirst() + "/card").getStatus().value());
        assertEquals("max-age=31536000, public, immutable",
                download(guest(), "/api/images/" + pictures.getFirst() + "/card").getResponseHeaders().getCacheControl());
        assertEquals("max-age=604800, private, immutable", download(bea, "/api/images/" + pictures.get(1) + "/card").getResponseHeaders().getCacheControl());
        assertEquals(404, download(cyd, "/api/images/" + pictures.get(1) + "/card").getStatus().value());
        assertEquals(404, download(bea, "/api/images/" + pictures.get(2) + "/card").getStatus().value());
        assertEquals(400, download(guest(), "/api/images/not-an-id/card").getStatus().value());
    }

    @Test void aHiddenPublicPictureLeavesBothSizesUnderItsOldId() {
        RestTestClient ada = user("Ada");
        UUID combo = addCombination(ada, unique("Hidden"), addGlaze(ada, unique("Hidden"), "Blue", "PC-20"));
        UUID first = uploadTile(ada, combo, "PUBLIC", null);
        String key = key(first);
        download(guest(), "/api/images/" + first + "/card");
        ImageView moved = body(ada.put().uri("/api/images/{id}/visibility", first).body(Map.of("visibility", "PRIVATE"))
                .exchange().expectStatus().isOk(), ImageView.class);
        assertNotEquals(first, moved.id());
        for (RestTestClient client : List.of(ada, guest()))
            for (String url : List.of("/api/images/" + first, "/api/images/" + first + "/card"))
                assertEquals(404, download(client, url).getStatus().value(), url);
        var card = download(ada, moved.cardUrl());
        assertEquals(200, card.getStatus().value());
        assertEquals("max-age=604800, private, immutable", card.getResponseHeaders().getCacheControl());
        assertEquals(404, download(guest(), moved.cardUrl()).getStatus().value());
        // The successor shares both stored objects; deleting it removes them.
        assertEquals(key, key(moved.id()));
        assertStored(storage, key, true);
        ada.delete().uri("/api/images/{id}", moved.id()).exchange().expectStatus().isNoContent();
        assertStored(storage, key, false);
        assertEquals(404, download(ada, moved.cardUrl()).getStatus().value());
    }

    @Test void aPictureWithoutACardCopyAnswersWithItsFullSize() throws IOException {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Older"), "Blue", "PC-20");
        UUID id = created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "FINISHED"), TestImages.photo(1200, 900)));
        storage.delete(ImageStorage.cardKey(key(id)));
        var card = download(guest(), "/api/images/" + id + "/card");
        assertEquals(200, card.getStatus().value());
        assertArrayEquals(download(guest(), "/api/images/" + id).getResponseBody(), card.getResponseBody());
        assertEquals("max-age=31536000, public, immutable", card.getResponseHeaders().getCacheControl());
        // Without either object the picture is gone, as for the full size.
        storage.delete(key(id));
        assertEquals(404, download(guest(), "/api/images/" + id + "/card").getStatus().value());
    }

    @Test void aSmallPictureStoresItsOwnBytesAsTheCard() throws IOException {
        RestTestClient ada = user("Ada");
        UUID combo = addCombination(ada, unique("Small"), addGlaze(ada, unique("Small"), "Blue", "PC-20"));
        UUID id = uploadTile(ada, combo, "PUBLIC", null);
        assertArrayEquals(storage.get(key(id)), storage.get(ImageStorage.cardKey(key(id))));
    }
}
