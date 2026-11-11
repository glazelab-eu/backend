package eu.glazelab.api.config;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The AGPL source offer (section 13): where this server's source code is published ({@code GLAZELAB_SOURCE_URL}, no
 * default, so a fork never points at another project's code) and which build runs here. An unset URL hides the app's
 * link; a URL that is not absolute http(s) stops the start, since the app would put it into a link.
 */
@Component
public class SourceCode {
    private final String url;
    private final String build;

    public SourceCode(@Value("${glazelab.source-url:}") String url, ObjectProvider<BuildProperties> buildInfo) {
        this.url = checked(url.strip());
        BuildProperties info = buildInfo.getIfAvailable();
        String label = info == null ? null : info.get("label");
        String version = info == null ? null : info.getVersion();
        this.build = label != null && !label.isBlank() ? label.strip() : version != null && !version.isBlank() ? version : "unknown";
    }

    /** The published source code, or null while none is configured. */
    public String url() { return url; }

    /** The build label of this jar (the commit or label given at build time, else the project version). */
    public String build() { return build; }

    private static String checked(String url) {
        if (url.isEmpty()) return null;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) && uri.getHost() != null) return url;
        } catch (URISyntaxException ignored) {
            // Refused below without echoing the value.
        }
        throw new IllegalStateException("glazelab.source-url (GLAZELAB_SOURCE_URL) must be an absolute http or https URL.");
    }
}
