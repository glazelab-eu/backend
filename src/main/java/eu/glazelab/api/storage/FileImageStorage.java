package eu.glazelab.api.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stores images as files in one directory (GLAZELAB_IMAGES); the default adapter. */
public class FileImageStorage implements ImageStorage {
    private final Path directory;
    FileImageStorage(Path directory) { this.directory = directory; }
    private Path file(String key) { return directory.resolve(ImageStorage.checkKey(key)); }
    public void put(String key, byte[] jpeg) throws IOException {
        Files.createDirectories(directory);
        Files.write(file(key), jpeg);
    }
    public byte[] get(String key) throws IOException { return Files.readAllBytes(file(key)); }
    public void delete(String key) throws IOException { Files.deleteIfExists(file(key)); }
}
