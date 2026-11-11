package eu.glazelab.api.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * How a log line names a client (backend guide "Logs"): the first 8 hex digits of an HMAC-SHA256 of its address part
 * under a key drawn at process start, so one client keeps its label for the run (lines can be correlated) and the
 * address (IPv4, IPv6 or /64) never reaches the log; a new process gives every client a new label. The account ID of a
 * signed-in budget stays as it is (a row ID).
 */
public final class ClientLabel {
    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] KEY = new byte[32];
    static { new SecureRandom().nextBytes(KEY); }

    private ClientLabel() {}

    /** The label of a {@link RateLimiter#clientKey} ("address" or "address accountId"). */
    public static String of(String clientKey) {
        int space = clientKey.indexOf(' ');
        String address = space < 0 ? clientKey : clientKey.substring(0, space);
        String label = hash(address);
        return space < 0 ? label : label + " account" + clientKey.substring(space);
    }

    private static String hash(String address) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(KEY, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(address.getBytes(StandardCharsets.UTF_8)), 0, 4);
        } catch (GeneralSecurityException impossible) {
            // Every Java runtime ships HmacSHA256; never fall back to the address.
            throw new IllegalStateException(ALGORITHM + " unavailable", impossible);
        }
    }
}
