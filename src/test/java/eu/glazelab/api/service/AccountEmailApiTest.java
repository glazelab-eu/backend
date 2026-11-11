package eu.glazelab.api.service;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import eu.glazelab.api.dto.AuthDtos.AuthOptionsView;
import eu.glazelab.api.dto.AuthDtos.RegistrationView;
import eu.glazelab.api.security.LoginSessions;
import eu.glazelab.api.support.ApiTestBase;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static eu.glazelab.api.support.ApiTestBase.*;
import static org.junit.jupiter.api.Assertions.*;

/** Email flows over HTTP against a local GreenMail SMTP server; no external mail is sent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:email;DB_CLOSE_DELAY=-1",
        "glazelab.images.path=target/test-images",
        "glazelab.rate-limit.enabled=false",
        "glazelab.mail.from=test@example.test",
        "glazelab.public-url=http://localhost",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "spring.mail.properties.mail.smtp.ssl.enable=false" })
class AccountEmailApiTest {
    private static final GreenMail SMTP = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
    private static final String EMAIL = "email-test@example.test";
    static { SMTP.start(); }

    @LocalServerPort int port;

    @DynamicPropertySource static void smtp(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> SMTP.getSmtp().getPort());
    }

    @AfterAll static void stopSmtp() {
        SMTP.stop();
    }

    /** Each test counts its own messages from the first. */
    @BeforeEach void emptyMailbox() throws Exception {
        SMTP.purgeEmailFromAllMailboxes();
    }

    private RestTestClient client() {
        return ApiTestBase.client("http://localhost:" + port, true);
    }

    private static RestTestClient.ResponseSpec post(RestTestClient client, String path, Map<String, ?> body) {
        return client.post().uri("/api/auth/" + path).body(body).exchange();
    }

    private static String text(MimeMessage message) throws Exception {
        return (String) message.getContent();
    }

    private static List<MimeMessage> mailsTo(String email) throws Exception {
        List<MimeMessage> found = new ArrayList<>();
        for (MimeMessage message : SMTP.getReceivedMessages()) {
            if (message.getAllRecipients()[0].toString().equals(email)) found.add(message);
        }
        return found;
    }

    /** Polls (bounded, generous) for the mail to this recipient whose body links to the route; independent of arrival order and of other mails. */
    private static MimeMessage awaitLink(String email, String route) throws Exception {
        Pattern link = Pattern.compile("http://localhost/#/" + route + "/([A-Za-z0-9_-]{43})\\s");
        long deadline = System.nanoTime() + 60_000_000_000L;
        do {
            for (MimeMessage message : mailsTo(email)) {
                if (link.matcher(text(message)).find()) return message;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No " + route + " mail for " + email);
    }

    private static String token(String email, String route) throws Exception {
        var match = Pattern.compile("http://localhost/#/" + route + "/([A-Za-z0-9_-]{43})\\s").matcher(text(awaitLink(email, route)));
        assertTrue(match.find());
        return match.group(1);
    }

    @Test void verificationResetAndMagicLoginTokensAreSingleUseAndPurposeBound() throws Exception {
        RestTestClient a = client(), b = client(), guest = client();
        assertTrue(body(guest.get().uri("/api/auth/options").exchange(), AuthOptionsView.class).emailEnabled());

        var registration = post(a, "register", Map.of("termsAccepted", true, "termsVersion", eu.glazelab.api.service.AccountService.TERMS_VERSION, "email", EMAIL, "password", "oldsecret123", "displayName", "Email test"));
        // An older client's password is ignored: the token goes by mail only, and nothing signs in before activation.
        var answer = body(registration.expectStatus().isOk(), RegistrationView.class);
        assertTrue(answer.verificationRequired());
        assertNull(answer.setupToken());
        login(a, EMAIL, "oldsecret123").expectStatus().isUnauthorized();
        String verify = token(EMAIL, "verify");
        post(guest, "password/reset", Map.of("token", verify, "password", "newsecret123")).expectStatus().isBadRequest();
        post(guest, "verification/confirm", activation(verify, "verifiedsecret123", "Mailbox owner")).expectStatus().isOk();
        post(guest, "verification/confirm", Map.of("token", verify)).expectStatus().isBadRequest();
        login(a, EMAIL, "verifiedsecret123").expectStatus().isOk();
        login(b, EMAIL, "verifiedsecret123").expectStatus().isOk();

        // Unknown accounts get the same acknowledgement; repeated requests are throttled per account.
        post(guest, "password/request", Map.of("email", "unknown@example.test")).expectStatus().isOk().expectBody().isEmpty();
        post(guest, "password/request", Map.of("email", EMAIL)).expectStatus().isOk();
        String reset = token(EMAIL, "reset");
        post(guest, "password/request", Map.of("email", EMAIL)).expectStatus().isOk();
        Thread.sleep(1000);
        assertEquals(1, mailsTo(EMAIL).stream().filter(m -> { try { return text(m).contains("/#/reset/"); } catch (Exception e) { throw new IllegalStateException(e); } }).count(),
                "Repeated requests must be throttled per account");

        post(guest, "verification/confirm", Map.of("token", reset)).expectStatus().isBadRequest();
        post(guest, "password/reset", Map.of("token", reset, "password", "short")).expectStatus().isBadRequest();
        post(guest, "password/reset", Map.of("token", reset, "password", "newsecret123")).expectStatus().isOk();
        // A reset revokes every existing session.
        a.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        b.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        post(guest, "password/reset", Map.of("token", reset, "password", "anothersecret")).expectStatus().isBadRequest();
        login(a, EMAIL, "oldsecret123").expectStatus().isUnauthorized();
        String previousSession = login(a, EMAIL, "newsecret123").expectStatus().isOk()
                .returnResult().getResponseCookies().getFirst(LoginSessions.COOKIE).getValue();

        post(guest, "magic/request", Map.of("email", "unknown@example.test")).expectStatus().isOk();
        post(guest, "magic/request", Map.of("email", EMAIL)).expectStatus().isOk();
        String magic = token(EMAIL, "login");
        assertTrue(text(awaitLink(EMAIL, "login")).contains("15 minutes"));
        post(guest, "verification/confirm", Map.of("token", magic)).expectStatus().isBadRequest();
        post(guest, "password/reset", Map.of("token", magic, "password", "wrongpurpose123")).expectStatus().isBadRequest();

        String previousCsrf = csrf(a);
        post(a, "magic/confirm", Map.of("token", magic)).expectStatus().isOk()
                .expectCookie().value(LoginSessions.COOKIE, session -> assertNotEquals(previousSession, session));
        assertEquals(EMAIL, me(a).email());
        a.post().uri("/api/auth/logout").header(CSRF_HEADER, previousCsrf).exchange()
                .expectStatus().isForbidden();
        post(guest, "magic/confirm", Map.of("token", magic)).expectStatus().isBadRequest();
    }

    @Test void aPasswordChangeMailsTheOwnerANoticeWithoutALink() throws Exception {
        String email = "change-notice@example.test";
        RestTestClient member = client(), other = client();
        post(member, "register", Map.of("termsAccepted", true, "termsVersion", AccountService.TERMS_VERSION, "email", email, "displayName", "Notice"))
                .expectStatus().isOk();
        post(member, "verification/confirm", activation(token(email, "verify"), "firstsecret123", "Notice"))
                .expectStatus().isOk();
        login(member, email, "firstsecret123").expectStatus().isOk();
        login(other, email, "firstsecret123").expectStatus().isOk();

        // A wrong current password sends nothing.
        post(member, "password/change", Map.of("currentPassword", "wrong-guess", "newPassword", "secondsecret123")).expectStatus().isBadRequest();
        post(member, "password/change", Map.of("currentPassword", "firstsecret123", "newPassword", "secondsecret123")).expectStatus().isOk();
        MimeMessage notice = null;
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (notice == null && System.nanoTime() < deadline) {
            for (MimeMessage m : mailsTo(email)) if ("Your Glazelab password was changed".equals(m.getSubject())) notice = m;
            if (notice == null) Thread.sleep(50);
        }
        assertNotNull(notice, "Expected the change notice");
        String text = text(notice);
        assertTrue(text.contains("Forgot password?") && text.contains("http://localhost/"), text);
        assertFalse(text.contains("/#/"), "The notice carries no account link: " + text);
        assertEquals(2, mailsTo(email).size());
        assertEquals(email, me(member).email());
        other.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
    }
}
