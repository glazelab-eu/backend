package eu.glazelab.api.storage;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** S3 connection settings (GLAZELAB_S3_*). Credentials come only from configuration and are never printed. */
@ConfigurationProperties("glazelab.s3")
public record S3Settings(String endpoint, String region, String bucket, String accessKey, String secretKey,
        boolean pathStyle) {

    /** Names the environment variables that are blank; never includes their values. */
    List<String> missing() {
        var missing = new ArrayList<String>();
        if (blank(endpoint)) missing.add("GLAZELAB_S3_ENDPOINT");
        if (blank(region)) missing.add("GLAZELAB_S3_REGION");
        if (blank(bucket)) missing.add("GLAZELAB_S3_BUCKET");
        if (blank(accessKey)) missing.add("GLAZELAB_S3_ACCESS_KEY");
        if (blank(secretKey)) missing.add("GLAZELAB_S3_SECRET_KEY");
        return missing;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    @Override public String toString() {
        return "S3Settings[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket
                + ", pathStyle=" + pathStyle + ", credentials=" + (blank(accessKey) ? "unset" : "set") + "]";
    }
}
