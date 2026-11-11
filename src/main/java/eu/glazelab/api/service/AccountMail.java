package eu.glazelab.api.service;

import eu.glazelab.api.model.AccountToken.Purpose;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class AccountMail {
    private final JavaMailSender sender;
    private final String from;
    private final String baseUrl;
    private final boolean enabled;
    public AccountMail(JavaMailSender sender, @Value("${spring.mail.host:}") String host,
            @Value("${glazelab.mail.from:}") String from, @Value("${glazelab.public-url:}") String baseUrl) {
        this.sender = sender; this.from = from; this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.enabled = !host.isBlank();
        if (enabled) {
            URI uri = URI.create(baseUrl);
            boolean local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if (from.isBlank() || uri.getHost() == null || (!"https".equals(uri.getScheme()) && !local)
                    || uri.getFragment() != null || uri.getQuery() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("SMTP requires GLAZELAB_MAIL_FROM and an HTTPS GLAZELAB_PUBLIC_URL (HTTP localhost allowed for testing)");
            }
        }
    }
    public boolean enabled() { return enabled; }
    public void send(String email, Purpose purpose, String token) {
        String subject = switch (purpose) {
            case RESET_PASSWORD -> "Reset your Glazelab password";
            case VERIFY_EMAIL -> "Verify your Glazelab email";
            case MAGIC_LOGIN -> "Sign in to Glazelab";
        };
        String action = switch (purpose) {
            case RESET_PASSWORD -> "Choose a new password";
            case VERIFY_EMAIL -> "Confirm your email address";
            case MAGIC_LOGIN -> "Sign in to your account";
        };
        // Frontend hash route (#/<route>/<token>): the fragment never reaches a server or Referer header.
        String route = switch (purpose) {
            case RESET_PASSWORD -> "reset";
            case VERIFY_EMAIL -> "verify";
            case MAGIC_LOGIN -> "login";
        };
        String expiry = switch (purpose) {
            case RESET_PASSWORD -> "30 minutes";
            case VERIFY_EMAIL -> "24 hours";
            case MAGIC_LOGIN -> "15 minutes";
        };
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(email); message.setSubject(subject);
        message.setText(action + " using this link:\n\n" + baseUrl + "/#/" + route + "/" + token
                + "\n\nThis link expires in " + expiry + ". It can be used once. "
                + "If you did not request this, you can ignore this email.\n\nGlazelab");
        sender.send(message);
    }
    /** Tells the owner that their password was changed while signed in; it carries no link or token. */
    public void sendPasswordChanged(String email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(email); message.setSubject("Your Glazelab password was changed");
        message.setText("The password of your Glazelab account was just changed, and your other devices were signed out."
                + "\n\nIf this was you, there is nothing else to do. If it was not, choose a new password with "
                + "\"Forgot password?\" in the sign-in dialog at " + baseUrl + "/ and contact the operator."
                + "\n\nGlazelab");
        sender.send(message);
    }
}
