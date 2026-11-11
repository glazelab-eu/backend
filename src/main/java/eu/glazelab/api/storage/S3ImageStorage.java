package eu.glazelab.api.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Stores images as objects in an S3-compatible bucket such as Hetzner Object Storage. Object keys equal the
 * file names of {@link FileImageStorage}, so existing files can be copied into the bucket root unchanged.
 */
public class S3ImageStorage implements ImageStorage, AutoCloseable {
    private final S3Client s3;
    private final String bucket;

    S3ImageStorage(S3Settings settings) {
        this.bucket = settings.bucket();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(settings.endpoint()))
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .forcePathStyle(settings.pathStyle())
                // Plain signed uploads: images are small byte arrays, and several S3-compatible stores reject
                // aws-chunked bodies or the CRC trailers newer SDKs send by default.
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(60)))
                .build();
    }

    public void put(String key, byte[] jpeg) throws IOException {
        String object = ImageStorage.checkKey(key);
        call(() -> s3.putObject(request -> request.bucket(bucket).key(object).contentType("image/jpeg"),
                RequestBody.fromBytes(jpeg)));
    }

    public byte[] get(String key) throws IOException {
        String object = ImageStorage.checkKey(key);
        try {
            return s3.getObjectAsBytes(request -> request.bucket(bucket).key(object)).asByteArray();
        } catch (NoSuchKeyException missing) {
            throw new NoSuchFileException(object);
        } catch (S3Exception error) {
            if (error.statusCode() == 404) throw new NoSuchFileException(object);
            throw failure(error);
        } catch (SdkException error) {
            throw failure(error);
        }
    }

    public void delete(String key) throws IOException {
        String object = ImageStorage.checkKey(key);
        call(() -> s3.deleteObject(request -> request.bucket(bucket).key(object)));
    }

    @Override public void close() { s3.close(); }

    private static void call(Runnable request) throws IOException {
        try {
            request.run();
        } catch (SdkException error) {
            throw failure(error);
        }
    }

    /** A generic message for API error bodies; the SDK detail stays in the cause for server logs. */
    private static IOException failure(SdkException error) {
        return new IOException("Image storage is unavailable", error);
    }
}
