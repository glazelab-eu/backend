package eu.glazelab.api.service;

import eu.glazelab.api.model.AccountToken.Purpose;
import eu.glazelab.api.support.ApiTestBase;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Account mail is sent after its token committed, with no account row lock held (backend guide "Registration"): a slow
 * SMTP server never makes the owner's own writes wait, and a failed send takes its token back. The mail sender is a
 * mock (nothing is sent anywhere); {@code AccountMailLockPostgresApiTest} runs the same tests on PostgreSQL under the
 * production lock timeout.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mail-lock-api;DB_CLOSE_DELAY=-1",
        "glazelab.require-email-verification=true", "spring.mail.host=127.0.0.1",
        "glazelab.mail.from=test@example.test", "glazelab.public-url=http://localhost" })
class AccountMailLockApiTest extends ApiTestBase {
    @MockitoBean JavaMailSender sender;

    /** A verified, signed-in account; its registration mail went to the mock sender before the stub below. */
    private RestTestClient verifiedAccount(String email) throws InterruptedException {
        var client = guest();
        register(client, email, "Owner").expectStatus().isOk();
        verify(sender, timeout(10_000).atLeast(1)).send(any(SimpleMailMessage.class));
        // Only this test's own registration mailed so far: its token row is in place, so the replacement below is the only one.
        await(() -> tokens(email, Purpose.VERIFY_EMAIL) == 1);
        UUID id = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, email);
        activate(client, accountToken(id, Purpose.VERIFY_EMAIL), PASSWORD, "Owner").expectStatus().isOk();
        login(client, email, PASSWORD).expectStatus().isOk();
        reset(sender);
        return client;
    }

    @Test void theOwnersWritesNeverWaitForTheirMail() throws Exception {
        String email = email("SlowSmtp");
        var owner = verifiedAccount(email);
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        doAnswer(invocation -> {
            sending.countDown();
            // An SMTP server that takes 15 seconds, longer than the production lock timeout (10 s).
            release.await(15, TimeUnit.SECONDS);
            return null;
        }).when(sender).send(sent.capture());
        try {
            guest().post().uri("/api/auth/password/request").body(Map.of("email", email)).exchange().expectStatus().isOk();
            assertTrue(sending.await(10, TimeUnit.SECONDS));
            // The mail task is inside the send; a write that locks the owner's account row completes meanwhile.
            long started = System.nanoTime();
            var write = CompletableFuture.supplyAsync(() -> owner.post().uri("/api/auth/terms")
                    .body(Map.of("termsVersion", AccountService.TERMS_VERSION)).exchange().returnResult().getStatus().value());
            assertEquals(200, write.get(8, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
            assertEquals(1, release.getCount(), "the send was still held");
        } finally {
            release.countDown();
        }
        // The token committed before the send: the link in the mail works.
        var match = Pattern.compile("/#/reset/([A-Za-z0-9_-]{43})\\s").matcher(sent.getValue().getText());
        assertTrue(match.find());
        guest().post().uri("/api/auth/password/reset").body(Map.of("token", match.group(1), "password", "fresh-password-789"))
                .exchange().expectStatus().isOk();
        login(guest(), email, "fresh-password-789").expectStatus().isOk();
    }

    @Test void aFailedSendTakesItsTokenBackAndNeverThrottles() throws Exception {
        String email = email("FailedSmtp");
        verifiedAccount(email);
        doThrow(new MailSendException("SMTP down")).when(sender).send(any(SimpleMailMessage.class));
        guest().post().uri("/api/auth/magic/request").body(Map.of("email", email)).exchange().expectStatus().isOk();
        verify(sender, timeout(10_000)).send(any(SimpleMailMessage.class));
        await(() -> tokens(email, Purpose.MAGIC_LOGIN) == 0);

        // An earlier link (issued two minutes ago) survives a failed renewal unchanged, issue time included.
        reset(sender);
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        guest().post().uri("/api/auth/magic/request").body(Map.of("email", email)).exchange().expectStatus().isOk();
        verify(sender, timeout(10_000)).send(sent.capture());
        await(() -> tokens(email, Purpose.MAGIC_LOGIN) == 1);
        var earlier = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2).withNano(0);
        jdbc.update("UPDATE account_token SET issued_at = ? WHERE purpose = 'MAGIC_LOGIN' AND user_id = (SELECT id FROM app_user WHERE email = ?)",
                earlier, email);
        String hash = tokenHash(email);
        reset(sender);
        doThrow(new MailSendException("SMTP down")).when(sender).send(any(SimpleMailMessage.class));
        guest().post().uri("/api/auth/magic/request").body(Map.of("email", email)).exchange().expectStatus().isOk();
        verify(sender, timeout(10_000)).send(any(SimpleMailMessage.class));
        await(() -> hash.equals(tokenHash(email)));
        assertEquals(earlier, jdbc.queryForObject("SELECT issued_at FROM account_token WHERE purpose = 'MAGIC_LOGIN'"
                + " AND user_id = (SELECT id FROM app_user WHERE email = ?)", LocalDateTime.class, email));
        var match = Pattern.compile("/#/login/([A-Za-z0-9_-]{43})\\s").matcher(sent.getValue().getText());
        assertTrue(match.find());

        // Not held back by the mail that never left: the next request within the minute mails at once.
        reset(sender);
        doThrow(new MailSendException("SMTP down")).when(sender).send(any(SimpleMailMessage.class));
        guest().post().uri("/api/auth/password/request").body(Map.of("email", email)).exchange().expectStatus().isOk();
        verify(sender, timeout(10_000)).send(any(SimpleMailMessage.class));
        await(() -> tokens(email, Purpose.RESET_PASSWORD) == 0);
        reset(sender);
        guest().post().uri("/api/auth/password/request").body(Map.of("email", email)).exchange().expectStatus().isOk();
        verify(sender, timeout(10_000)).send(any(SimpleMailMessage.class));
        await(() -> tokens(email, Purpose.RESET_PASSWORD) == 1);
        // The earlier magic link still signs in.
        var client = guest();
        client.post().uri("/api/auth/magic/confirm").body(Map.of("token", match.group(1))).exchange().expectStatus().isOk();
        assertEquals(email, me(client).email());
    }

    private int tokens(String email, Purpose purpose) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_token t JOIN app_user u ON u.id = t.user_id"
                + " WHERE u.email = ? AND t.purpose = ?", Integer.class, email, purpose.name());
    }

    private String tokenHash(String email) {
        return jdbc.queryForList("SELECT token_hash FROM account_token t JOIN app_user u ON u.id = t.user_id"
                + " WHERE u.email = ? AND t.purpose = 'MAGIC_LOGIN'", String.class, email).stream().findFirst().orElse("");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "the expected state did not arrive");
    }
}
