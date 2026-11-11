package eu.glazelab.api.storage;

import java.nio.file.Path;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects the image store with GLAZELAB_STORAGE: {@code file} (default, GLAZELAB_IMAGES) or {@code s3}. */
@Configuration
@EnableConfigurationProperties(S3Settings.class)
public class ImageStorageConfig {
    @Bean ImageStorage imageStorage(@Value("${glazelab.storage:file}") String type,
            @Value("${glazelab.images.path}") String directory, S3Settings s3) {
        return new LoggedImageStorage(select(type, directory, s3));
    }

    static ImageStorage select(String type, String directory, S3Settings s3) {
        String choice = type == null || type.isBlank() ? "file" : type.trim().toLowerCase(Locale.ROOT);
        return switch (choice) {
            case "file" -> new FileImageStorage(Path.of(directory));
            case "s3" -> {
                if (!s3.missing().isEmpty())
                    throw new IllegalStateException("GLAZELAB_STORAGE=s3 requires " + String.join(", ", s3.missing()));
                yield new S3ImageStorage(s3);
            }
            default -> throw new IllegalStateException("GLAZELAB_STORAGE must be file or s3");
        };
    }
}
