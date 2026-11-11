package eu.glazelab.api.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageStorageConfigTest {
    static final S3Settings S3 = new S3Settings("https://fsn1.your-objectstorage.com", "fsn1", "glazelab-images",
            "ACCESS-KEY-VALUE", "SECRET-KEY-VALUE", false);
    static final S3Settings UNSET = new S3Settings("", "", "", "", "", false);

    @Test void fileStorageIsTheDefault() {
        assertInstanceOf(FileImageStorage.class, ImageStorageConfig.select(null, "target/images", UNSET));
        assertInstanceOf(FileImageStorage.class, ImageStorageConfig.select("", "target/images", UNSET));
        assertInstanceOf(FileImageStorage.class, ImageStorageConfig.select("file", "target/images", UNSET));
    }

    @Test void s3StorageIsSelectedByConfiguration() throws Exception {
        try (var storage = (S3ImageStorage) ImageStorageConfig.select(" S3 ", "target/images", S3)) {
            assertNotNull(storage);
        }
    }

    @Test void s3StorageNamesMissingSettingsWithoutPrintingValues() {
        var partial = new S3Settings("https://fsn1.your-objectstorage.com", "fsn1", "", "ACCESS-KEY-VALUE", " ", true);
        var error = assertThrows(IllegalStateException.class, () -> ImageStorageConfig.select("s3", "x", partial));
        assertEquals("GLAZELAB_STORAGE=s3 requires GLAZELAB_S3_BUCKET, GLAZELAB_S3_SECRET_KEY", error.getMessage());
        assertThrows(IllegalStateException.class, () -> ImageStorageConfig.select("s3", "x", UNSET));
    }

    @Test void unknownStorageTypesAreRejected() {
        var error = assertThrows(IllegalStateException.class, () -> ImageStorageConfig.select("minio", "x", S3));
        assertEquals("GLAZELAB_STORAGE must be file or s3", error.getMessage());
    }

    @Test void settingsNeverPrintCredentials() {
        String text = S3.toString();
        assertFalse(text.contains("ACCESS-KEY-VALUE"), text);
        assertFalse(text.contains("SECRET-KEY-VALUE"), text);
        assertTrue(text.contains("glazelab-images"), text);
    }

    @Test void storageKeysAreValidatedBeforeAnyAccess() {
        assertEquals("0b6f7a4e-1d2c-4f3b-9a8e-123456789abc.jpg",
                ImageStorage.checkKey("0b6f7a4e-1d2c-4f3b-9a8e-123456789abc.jpg"));
        for (String key : new String[] {null, "../etc/passwd", "a.jpg", "0b6f7a4e-1d2c-4f3b-9a8e-123456789abc.png"})
            assertThrows(IllegalArgumentException.class, () -> ImageStorage.checkKey(key));
    }
}
