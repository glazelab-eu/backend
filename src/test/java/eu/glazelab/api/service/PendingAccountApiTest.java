package eu.glazelab.api.service;

import eu.glazelab.api.model.EmailAddresses;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import eu.glazelab.api.dto.AuthDtos.RegistrationView;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.support.ApiTestBase;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Mail is captured locally. No messages leave this test server. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pending-api;DB_CLOSE_DELAY=-1",
        "glazelab.require-email-verification=true",
        "glazelab.mail.from=test@example.test", "glazelab.public-url=http://localhost",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "spring.mail.properties.mail.smtp.ssl.enable=false" })
class PendingAccountApiTest extends ApiTestBase {
    private static final GreenMail SMTP = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
    private static final String ATTACKER_PASSWORD = "attacker-chosen-123";
    private static final String OWNER_PASSWORD = "owner-fresh-456";
    static { SMTP.start(); }
    @MockitoSpyBean AppUserRepository users;
    @Autowired ThreadPoolTaskExecutor mailExecutor;
    @Autowired PendingAccounts pending;
    @MockitoSpyBean JavaMailSender sender;
    @MockitoSpyBean PasswordEncoder passwords;

    @DynamicPropertySource static void smtp(DynamicPropertyRegistry properties) {
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", () -> SMTP.getSmtp().getPort());
    }
    @AfterAll static void stopMail() { SMTP.stop(); }

    private String token(String email, String route) throws Exception {
        for (var message : SMTP.getReceivedMessages()) {
            if (!java.util.Arrays.stream(message.getAllRecipients()).anyMatch(a -> a.toString().equalsIgnoreCase(email))) continue;
            var match = Pattern.compile("/#/" + route + "/([A-Za-z0-9_-]{43})\\s").matcher((String) message.getContent());
            if (match.find()) return match.group(1);
        }
        throw new AssertionError("No captured " + route + " link for the test account");
    }

    @ParameterizedTest @ValueSource(strings = {"verify", "login", "reset"})
    void mailboxRecoveryRequiresFreshCredentialsBeforeFirstActivation(String route) throws Exception {
        var owner = guest();
        String email = email("Squatting");
        var registration = body(oldClientRegister(owner, email, "Squatter").expectStatus().isOk(), RegistrationView.class);
        assertTrue(registration.verificationRequired());
        assertNull(registration.setupToken());
        var id = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow().getId();
        // Mail to this address: another test's late mail must not stand in for it.
        await(() -> mailsTo(email) == 1);
        String verification = token(email, "verify");
        // A second registration within the minute neither mails nor replaces the link.
        assertNull(setupToken(oldClientRegister(guest(), email, "Squatter")));
        login(guest(), email, ATTACKER_PASSWORD).expectStatus().isUnauthorized();
        owner.get().uri("/api/stock").exchange().expectStatus().isUnauthorized();
        assertFalse(users.findById(id).orElseThrow().isEmailVerified());

        String endpoint = "/api/auth/verification/confirm";
        String raw = verification;
        if (!route.equals("verify")) {
            int count = mailsTo(email);
            owner.post().uri(route.equals("login") ? "/api/auth/magic/request" : "/api/auth/password/request")
                    .body(Map.of("email", email)).exchange().expectStatus().isOk();
            await(() -> mailsTo(email) == count + 1);
            raw = token(email, route);
            endpoint = route.equals("login") ? "/api/auth/magic/confirm" : "/api/auth/password/reset";
        }
        var incomplete = route.equals("reset") ? Map.of("token", raw, "password", OWNER_PASSWORD) : Map.of("token", raw);
        owner.post().uri(endpoint).body(incomplete).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo(AccountService.ACTIVATION_REQUIRED);
        assertFalse(users.findById(id).orElseThrow().isEmailVerified());
        owner.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        guestWithoutCsrf().post().uri(endpoint).body(Map.of("token", raw, "password", OWNER_PASSWORD, "displayName", "Mailbox owner"))
                .exchange().expectStatus().isForbidden();
        owner.post().uri(endpoint).body(Map.of("token", raw, "password", OWNER_PASSWORD, "displayName", "   "))
                .exchange().expectStatus().isBadRequest();
        // Every link that activates asks the mailbox owner for the terms; without them nothing changes.
        owner.post().uri(endpoint).body(Map.of("token", raw, "password", OWNER_PASSWORD, "displayName", "Mailbox owner"))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").isEqualTo(AccountService.TERMS_NOT_ACCEPTED);
        assertFalse(users.findById(id).orElseThrow().isEmailVerified());
        // A registration from before activation recorded consent left the registrant's choice on the row: activation replaces it.
        var registered = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
        jdbc.update("UPDATE app_user SET accepted_terms_version = ?, terms_accepted_at = ?, diagnostics_opt_in = TRUE WHERE id = ?",
                AccountService.TERMS_VERSION, registered, id);
        owner.post().uri(endpoint).body(activation(raw, OWNER_PASSWORD, "Mailbox owner")).exchange().expectStatus().isOk();

        var account = users.findById(id).orElseThrow();
        assertTrue(account.isEmailVerified());
        assertNull(account.getVerificationExpiresAt());
        assertEquals("Mailbox owner", account.getDisplayName());
        assertEquals(1, account.getSessionVersion());
        assertEquals(AccountService.TERMS_VERSION, account.getAcceptedTermsVersion());
        assertTrue(account.getTermsAcceptedAt().isAfter(registered.plusHours(1)));
        assertFalse(account.isDiagnosticsOptIn());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_token WHERE user_id = ?", Integer.class, account.getId()));
        login(guest(), email, ATTACKER_PASSWORD).expectStatus().isUnauthorized();
        var signedIn = guest();
        login(signedIn, email, OWNER_PASSWORD).expectStatus().isOk();
        assertEquals("Mailbox owner", me(signedIn).displayName());
        if (route.equals("login")) assertEquals(account.getId(), me(owner).id());
        else owner.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        owner.post().uri(endpoint).body(Map.of("token", raw, "password", OWNER_PASSWORD, "displayName", "Replay"))
                .exchange().expectStatus().isBadRequest();
        owner.post().uri("/api/auth/verification/confirm").body(Map.of("token", verification))
                .exchange().expectStatus().isBadRequest();
    }

    @Test void expiredRegistrationsReleaseTheirEmailAndOldLinksCannotActivateTheReplacement() throws Exception {
        String email = email("ExpiredSignup");
        var client = guest();
        int before = SMTP.getReceivedMessages().length;
        register(client, email, "Squatter").expectStatus().isOk();
        var original = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow();
        assertTrue(SMTP.waitForIncomingEmail(10_000, before + 1));
        String oldLink = token(email, "verify");
        assertTrue(original.getVerificationExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(23)));
        jdbc.update("UPDATE app_user SET verification_expires_at = ? WHERE id = ?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), original.getId());
        client.post().uri("/api/auth/verification/confirm").body(Map.of("token", oldLink, "password", OWNER_PASSWORD, "displayName", "Owner"))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error")
                .isEqualTo("This unverified account has expired. Please register again.");
        register(client, email, "Squatter").expectStatus().isOk();
        var replacement = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow();
        assertNotEquals(original.getId(), replacement.getId());
        assertFalse(users.existsById(original.getId()));
        client.post().uri("/api/auth/verification/confirm").body(Map.of("token", oldLink, "password", OWNER_PASSWORD, "displayName", "Owner"))
                .exchange().expectStatus().isBadRequest();
        jdbc.update("UPDATE app_user SET verification_expires_at = ? WHERE id = ?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), replacement.getId());
        pending.expireBatch();
        assertFalse(users.existsById(replacement.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_token WHERE user_id = ?", Integer.class, replacement.getId()));
    }

    @Test void cleanupNeverRemovesVerifiedAccounts() throws Exception {
        var client = guest();
        String email = email("VerifiedKept");
        int count = SMTP.getReceivedMessages().length;
        register(client, email, "Squatter").expectStatus().isOk();
        var account = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow();
        assertTrue(SMTP.waitForIncomingEmail(10_000, count + 1));
        activate(client, token(email, "verify"), OWNER_PASSWORD, "Owner").expectStatus().isOk();
        jdbc.update("UPDATE app_user SET verification_expires_at = ? WHERE id = ?", LocalDateTime.now(ZoneOffset.UTC).minusDays(1), account.getId());
        pending.expireBatch();
        assertTrue(users.existsById(account.getId()));
        login(client, email, OWNER_PASSWORD).expectStatus().isOk();
    }

    /** An older client still sends the password it used to choose at registration; it must be ignored. */
    private static org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec oldClientRegister(
            org.springframework.test.web.servlet.client.RestTestClient client, String email, String displayName) {
        return client.post().uri("/api/auth/register").body(Map.of("termsAccepted", true, "termsVersion", AccountService.TERMS_VERSION,
                "email", email, "displayName", displayName, "password", ATTACKER_PASSWORD)).exchange();
    }

    private String registrationAnswer(String email) {
        return oldClientRegister(guest(), email, "Prober").expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    /** Lets the per-minute mail throttle pass for the account's earlier links. */
    private void earlierLinksAreOld(String email) {
        jdbc.update("UPDATE account_token SET issued_at = ? WHERE user_id = (SELECT id FROM app_user WHERE email = ?)",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2), email);
    }

    private int mailsTo(String email) {
        return (int) java.util.Arrays.stream(SMTP.getReceivedMessages()).filter(message -> {
            try { return java.util.Arrays.stream(message.getAllRecipients()).anyMatch(a -> a.toString().equalsIgnoreCase(email)); }
            catch (jakarta.mail.MessagingException ex) { throw new IllegalStateException(ex); }
        }).count();
    }

    @Test void registrationAnswersAlikeForNewPendingAndVerifiedAddresses() throws Exception {
        String fresh = email("Fresh"), pendingEmail = email("Pending"), verified = email("Verified");
        oldClientRegister(guest(), pendingEmail, "Owner").expectStatus().isOk();
        var verifiedOwner = guest();
        oldClientRegister(verifiedOwner, verified, "Owner").expectStatus().isOk();
        await(() -> mailsTo(pendingEmail) == 1 && mailsTo(verified) == 1);
        activate(verifiedOwner, token(verified, "verify"), OWNER_PASSWORD, "Owner").expectStatus().isOk();
        String pendingLink = token(pendingEmail, "verify");
        var pendingAccount = users.findByEmailKey(EmailAddresses.key(pendingEmail)).orElseThrow();
        earlierLinksAreOld(pendingEmail);

        // Same status and body for all three; each address gets exactly one mail, sent after the answer.
        String expected = "{\"verificationRequired\":true,\"setupToken\":null}";
        assertEquals(expected, registrationAnswer(fresh));
        assertEquals(expected, registrationAnswer(pendingEmail));
        assertEquals(expected, registrationAnswer(verified));
        await(() -> mailsTo(fresh) == 1 && mailsTo(pendingEmail) == 2 && mailsTo(verified) == 2);
        assertNotNull(token(fresh, "verify"));
        // The pending owner gets a fresh confirmation link (the old one is replaced); the account is untouched.
        String renewed = lastToken(pendingEmail, "verify");
        assertNotEquals(pendingLink, renewed);
        var after = users.findByEmailKey(EmailAddresses.key(pendingEmail)).orElseThrow();
        assertEquals(pendingAccount.getId(), after.getId());
        assertEquals("Owner", after.getDisplayName());
        assertFalse(after.isEmailVerified());
        // The verified owner gets a password-reset link instead; the account and its password are untouched.
        assertNotNull(lastToken(verified, "reset"));
        login(guest(), verified, OWNER_PASSWORD).expectStatus().isOk();
        login(guest(), verified, ATTACKER_PASSWORD).expectStatus().isUnauthorized();
        for (String email : new String[] {fresh, pendingEmail})
            for (String guess : new String[] {ATTACKER_PASSWORD, ""}) login(guest(), email, guess).expectStatus().isUnauthorized();
    }

    @Test void anOlderClientsSignUpConsentChangesNoAnswerAndNoAccount() throws Exception {
        String[] existing = pendingAndVerified("Diagnostics");
        String fresh = email("DiagnosticsFresh");
        String expected = "{\"verificationRequired\":true,\"setupToken\":null}";
        for (String email : new String[] {fresh, existing[0], existing[1]})
            for (Boolean choice : new Boolean[] {true, false, null}) {
                var body = new java.util.HashMap<String, Object>(Map.of("termsAccepted", true,
                        "termsVersion", AccountService.TERMS_VERSION, "email", email, "displayName", "Prober"));
                if (choice != null) body.put("diagnosticsOptIn", choice);
                assertEquals(expected, guest().post().uri("/api/auth/register").body(body).exchange().expectStatus().isOk()
                        .expectBody(String.class).returnResult().getResponseBody());
            }
        // The new pending account holds neither consent: both wait for the mailbox owner's activation.
        var created = users.findByEmailKey(EmailAddresses.key(fresh)).orElseThrow();
        assertFalse(created.isDiagnosticsOptIn());
        assertNull(created.getAcceptedTermsVersion());
        assertFalse(users.findByEmailKey(EmailAddresses.key(existing[0])).orElseThrow().isDiagnosticsOptIn());
        assertFalse(users.findByEmailKey(EmailAddresses.key(existing[1])).orElseThrow().isDiagnosticsOptIn());
        mailIdle();
    }

    @Test void aResetOfAnActiveAccountIgnoresConsentFields() throws Exception {
        String email = pendingAndVerified("ResetConsent")[1];
        var account = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow();
        var acceptedAt = account.getTermsAcceptedAt();
        var body = new java.util.HashMap<String, Object>(activation(accountToken(account.getId(), eu.glazelab.api.model.AccountToken.Purpose.RESET_PASSWORD),
                "reset-secret-789", "Ignored"));
        body.put("diagnosticsOptIn", true);
        body.put("termsVersion", "outdated");
        guest().post().uri("/api/auth/password/reset").body(body).exchange().expectStatus().isOk();
        var after = users.findById(account.getId()).orElseThrow();
        assertFalse(after.isDiagnosticsOptIn());
        assertEquals(acceptedAt, after.getTermsAcceptedAt());
        assertEquals("Owner", after.getDisplayName());
        login(guest(), email, "reset-secret-789").expectStatus().isOk();
        mailIdle();
    }

    /** Sets up a pending address with an old link and a verified address without links; mail is idle afterwards. */
    private String[] pendingAndVerified(String prefix) throws Exception {
        String pendingEmail = email(prefix + "Pending"), verified = email(prefix + "Verified");
        oldClientRegister(guest(), pendingEmail, "Owner").expectStatus().isOk();
        var verifiedOwner = guest();
        oldClientRegister(verifiedOwner, verified, "Owner").expectStatus().isOk();
        await(() -> mailsTo(pendingEmail) == 1 && mailsTo(verified) == 1);
        activate(verifiedOwner, token(verified, "verify"), OWNER_PASSWORD, "Owner").expectStatus().isOk();
        earlierLinksAreOld(pendingEmail);
        return new String[] {pendingEmail, verified};
    }

    /** Returns once every mail task submitted so far has finished: each pool thread must take one of these. */
    private void mailIdle() throws Exception {
        int threads = mailExecutor.getMaxPoolSize();
        var barrier = new CyclicBarrier(threads + 1);
        for (int i = 0; i < threads; i++) mailExecutor.execute(() -> {
            try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        barrier.await(10, TimeUnit.SECONDS);
    }

    private int tokensOf(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_token t JOIN app_user u ON u.id = t.user_id WHERE u.email = ?",
                Integer.class, email);
    }

    @Test void registrationAnswersBeforeAnyMailIsSent() throws Exception {
        String[] plain = pendingAndVerified("Plain"), primed = pendingAndVerified("Primed");
        String fresh = email("PlainFresh"), primedFresh = email("PrimedFresh");
        // The attacker's priming: always-200 requests give each existing primed address a link of its registration purpose.
        var attacker = guest();
        for (String email : new String[] {primed[1], primedFresh})
            attacker.post().uri("/api/auth/password/request").body(Map.of("email", email)).exchange().expectStatus().isOk();
        attacker.post().uri("/api/auth/verification/request").body(Map.of("email", primed[0])).exchange().expectStatus().isOk();
        await(() -> mailsTo(primed[0]) == 2 && mailsTo(primed[1]) == 2);
        mailIdle();

        var release = new CountDownLatch(1);
        var senders = new CopyOnWriteArrayList<String>();
        var sent = new AtomicInteger();
        doAnswer(invocation -> {
            senders.add(Thread.currentThread().getName());
            // A send on the request thread must not hang the test: it is recorded and fails the thread check below.
            if (Thread.currentThread().getName().startsWith("glazelab-mail-")) assertTrue(release.await(30, TimeUnit.SECONDS));
            invocation.callRealMethod();
            sent.incrementAndGet();
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));
        int before = SMTP.getReceivedMessages().length;
        try {
            String expected = "{\"verificationRequired\":true,\"setupToken\":null}";
            for (String email : new String[] {fresh, plain[0], plain[1], primedFresh, primed[0], primed[1]})
                assertEquals(expected, registrationAnswer(email), email);
            // Every answer is here while the mail sender is still held: none of them waited for SMTP.
            assertEquals(0, sent.get());
            assertEquals(before, SMTP.getReceivedMessages().length);
        } finally {
            release.countDown();
        }
        await(() -> sent.get() == 4);
        mailIdle();
        reset(sender);
        assertEquals(4, senders.size(), senders.toString());
        assertTrue(senders.stream().allMatch(name -> name.startsWith("glazelab-mail-")), senders.toString());
        // Exactly the owed mails: new and pending addresses a confirmation link, verified ones a reset link, none when throttled.
        assertEquals(1, mailsTo(fresh));
        assertNotNull(token(fresh, "verify"));
        assertEquals(2, mailsTo(plain[0]));
        assertNotEquals(token(plain[0], "verify"), lastToken(plain[0], "verify"));
        assertEquals(2, mailsTo(plain[1]));
        assertNotNull(lastToken(plain[1], "reset"));
        assertEquals(1, mailsTo(primedFresh));
        assertNotNull(token(primedFresh, "verify"));
        assertEquals(2, mailsTo(primed[0]));
        assertEquals(2, mailsTo(primed[1]));
    }

    /**
     * The attacker's other probe: an always-200 request starts a mail to the address's account while SMTP is slow. A
     * registration that waited for that mail would answer late for exactly the addresses that have an account.
     */
    @Test void registrationNeverWaitsForAMailBeingSentToTheAddress() throws Exception {
        String[] existing = pendingAndVerified("Busy");
        String fresh = email("BusyFresh");
        var sending = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(sender).send(any(SimpleMailMessage.class));
        int pendingMails = mailsTo(existing[0]), verifiedMails = mailsTo(existing[1]);
        var attacker = guest();
        try {
            attacker.post().uri("/api/auth/verification/request").body(Map.of("email", existing[0])).exchange().expectStatus().isOk();
            attacker.post().uri("/api/auth/password/request").body(Map.of("email", existing[1])).exchange().expectStatus().isOk();
            // Both mail threads are now inside a send to an existing address.
            assertTrue(sending.await(10, TimeUnit.SECONDS));
            String expected = "{\"verificationRequired\":true,\"setupToken\":null}";
            for (String email : new String[] {existing[0], existing[1], fresh}) {
                var answer = CompletableFuture.supplyAsync(() -> registrationAnswer(email));
                assertEquals(expected, answer.get(10, TimeUnit.SECONDS), email);
            }
        } finally {
            release.countDown();
        }
        mailIdle();
        reset(sender);
        // The registrations' own mails are held back by the links the requests just sent.
        await(() -> mailsTo(existing[0]) == pendingMails + 1 && mailsTo(existing[1]) == verifiedMails + 1 && mailsTo(fresh) == 1);
        mailIdle();
        assertEquals(pendingMails + 1, mailsTo(existing[0]));
        assertEquals(verifiedMails + 1, mailsTo(existing[1]));
    }

    @Test void failedMailKeepsTheRegistrationAndAnswersAlike() throws Exception {
        String[] existing = pendingAndVerified("Down");
        String pendingEmail = existing[0], verified = existing[1], fresh = email("FreshDown");
        String pendingHash = jdbc.queryForObject("SELECT token_hash FROM account_token t JOIN app_user u ON u.id = t.user_id WHERE u.email = ?",
                String.class, pendingEmail);
        doThrow(new MailSendException("SMTP down")).when(sender).send(any(SimpleMailMessage.class));
        try {
            String expected = "{\"verificationRequired\":true,\"setupToken\":null}";
            for (String email : new String[] {fresh, pendingEmail, verified}) assertEquals(expected, registrationAnswer(email), email);
            mailIdle();
        } finally {
            reset(sender);
        }
        // The new account stays pending; a failed send stores no link, so it neither replaces nor throttles anything.
        var created = users.findByEmailKey(EmailAddresses.key(fresh)).orElseThrow();
        assertFalse(created.isEmailVerified());
        assertEquals(0, tokensOf(fresh));
        assertEquals(pendingHash, jdbc.queryForObject("SELECT token_hash FROM account_token t JOIN app_user u ON u.id = t.user_id WHERE u.email = ?",
                String.class, pendingEmail));
        assertEquals(0, tokensOf(verified));
        // Resend right away: not held back by the mail that never left.
        guest().post().uri("/api/auth/verification/request").body(Map.of("email", fresh)).exchange().expectStatus().isOk();
        await(() -> mailsTo(fresh) == 1);
        activate(guest(), token(fresh, "verify"), OWNER_PASSWORD, "Owner").expectStatus().isOk();
        assertTrue(users.findByEmailKey(EmailAddresses.key(fresh)).orElseThrow().isEmailVerified());
    }

    /** BCrypt dwarfs every other step: each registration pays for exactly one hash, whatever the address. */
    @Test void everyRegistrationHashesOnce() throws Exception {
        String[] existing = pendingAndVerified("Hashed");
        for (String email : new String[] {email("HashedFresh"), existing[0], existing[1]}) {
            clearInvocations(passwords);
            registrationAnswer(email);
            verify(passwords, times(1)).encode(any());
        }
        mailIdle();
    }

    /** SMTP exceptions name the failed recipients; the log keeps class names only, never an address or a link. */
    @Test void aFailedSendLogsNeitherAddressNorLink() throws Exception {
        String[] existing = pendingAndVerified("Logged");
        String fresh = email("LoggedFresh");
        var log = new ListAppender<ILoggingEvent>();
        log.start();
        var root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(log);
        var links = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            var message = (SimpleMailMessage) invocation.getArgument(0);
            links.add(message.getText());
            String failed = "failed for " + String.join(",", message.getTo()) + ": " + message.getText();
            throw new MailSendException(failed, new jakarta.mail.SendFailedException(failed));
        }).when(sender).send(any(SimpleMailMessage.class));
        try {
            for (String email : new String[] {fresh, existing[0], existing[1]}) registrationAnswer(email);
            guest().post().uri("/api/auth/magic/request").body(Map.of("email", existing[1])).exchange().expectStatus().isOk();
            mailIdle();
        } finally {
            reset(sender);
            root.detachAppender(log);
        }
        assertEquals(4, links.size());
        assertEquals(4, log.list.stream().filter(event -> event.getFormattedMessage().startsWith("Account email delivery failed")).count());
        for (var event : log.list) {
            var text = new StringBuilder(event.getFormattedMessage());
            for (var thrown = event.getThrowableProxy(); thrown != null; thrown = thrown.getCause()) text.append(' ').append(thrown.getMessage());
            for (String secret : new String[] {fresh, existing[0], existing[1], "/#/"})
                assertFalse(text.toString().contains(secret), text.toString());
        }
    }

    @Test void aConcurrentRegistrationOfTheSameNewAddressAnswersAlike() throws Exception {
        String email = email("Race");
        oldClientRegister(guest(), email, "First").expectStatus().isOk();
        await(() -> mailsTo(email) == 1);
        var first = users.findByEmailKey(EmailAddresses.key(email)).orElseThrow();
        earlierLinksAreOld(email);
        // The other registration has not committed when this one looks: it inserts too and hits the unique address.
        // A repository interface has no real method to call: the retry's lookup reads the table.
        var looked = new AtomicInteger();
        doAnswer(call -> looked.getAndIncrement() > 0
                && jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email) > 0)
                .when(users).existsByEmailKey(EmailAddresses.key(email));
        try {
            assertEquals("{\"verificationRequired\":true,\"setupToken\":null}", registrationAnswer(email));
            // Answered as the existing address it now is: one account, its owner mailed a fresh link.
            await(() -> mailsTo(email) == 2);
            mailIdle();
            assertEquals(2, looked.get(), "each attempt looks once");
        } finally {
            reset(users);
        }
        assertEquals(first.getId(), users.findByEmailKey(EmailAddresses.key(email)).orElseThrow().getId());
        assertEquals("First", users.findByEmailKey(EmailAddresses.key(email)).orElseThrow().getDisplayName());
    }

    /** A verified, signed-in account made through the mail flow (this class sends real mail to GreenMail). */
    private org.springframework.test.web.servlet.client.RestTestClient verifiedAccount(String email) throws Exception {
        var client = guest();
        oldClientRegister(client, email, "Owner").expectStatus().isOk();
        await(() -> mailsTo(email) == 1);
        activate(client, token(email, "verify"), OWNER_PASSWORD, "Owner").expectStatus().isOk();
        login(client, email, OWNER_PASSWORD).expectStatus().isOk();
        return client;
    }

    /**
     * Group 34: a banned address (here in another writing of the same mailbox too) registers like any other address:
     * the same status and body as a new, a pending and a verified address, one password hash, no account and no mail.
     * Every always-200 mail request for it sends nothing either.
     */
    @Test void registrationAnswersABannedAddressLikeEveryOtherAndSendsItNothing() throws Exception {
        String[] existing = pendingAndVerified("Ban");
        String fresh = email("BanFresh");
        String adminEmail = email("BanAdmin");
        var admin = verifiedAccount(adminEmail);
        assertEquals(1, jdbc.update("UPDATE app_user SET site_admin = TRUE WHERE email = ?", adminEmail));
        String bannedEmail = email("Banned");
        var banned = verifiedAccount(bannedEmail);
        UUID bannedId = me(banned).id();
        admin.post().uri("/api/admin/users/{id}/ban", bannedId).body(Map.of("reason", "Spam")).exchange().expectStatus().isNoContent();
        mailIdle();
        int bannedMails = mailsTo(bannedEmail);
        String alias = bannedEmail.replace("@", "+again@").toUpperCase(Locale.ROOT);

        String expected = "{\"verificationRequired\":true,\"setupToken\":null}";
        for (String email : new String[] {fresh, existing[0], existing[1], bannedEmail, alias}) {
            clearInvocations(passwords);
            assertEquals(expected, registrationAnswer(email), email);
            verify(passwords, times(1)).encode(any());
        }
        await(() -> mailsTo(fresh) == 1);
        mailIdle();
        assertEquals(bannedMails, mailsTo(bannedEmail));
        assertEquals(0, mailsTo(alias));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE LOWER(email) IN (?, ?)", Integer.class,
                bannedEmail, alias.toLowerCase(Locale.ROOT)));
        // An account for the banned address (one a registration racing the ban created, or an alias account from
        // before it; a row inserted here stands in) gets no link: neither from the registration's mail task, which reads
        // the blocklist again after commit, nor from any always-200 mail request.
        jdbc.update("INSERT INTO app_user (id, email, email_key, password_hash, display_name, email_verified, verification_expires_at,"
                + " accepted_terms_version, terms_accepted_at) VALUES (?, ?, ?, 'unusable', 'Racer', FALSE, ?, ?, ?)", UUID.randomUUID(),
                bannedEmail, EmailAddresses.key(bannedEmail), LocalDateTime.now(ZoneOffset.UTC).plusHours(1), AccountService.TERMS_VERSION, LocalDateTime.now(ZoneOffset.UTC));
        assertEquals(expected, registrationAnswer(bannedEmail));
        var attacker = guest();
        for (String path : new String[] {"password/request", "verification/request", "magic/request"})
            for (String email : new String[] {bannedEmail, alias})
                attacker.post().uri("/api/auth/" + path).body(Map.of("email", email)).exchange().expectStatus().isOk().expectBody().isEmpty();
        mailIdle();
        assertEquals(bannedMails, mailsTo(bannedEmail));
        assertEquals(0, mailsTo(alias));
    }

    private String lastToken(String email, String route) throws Exception {
        String found = null;
        for (var message : SMTP.getReceivedMessages()) {
            if (!java.util.Arrays.stream(message.getAllRecipients()).anyMatch(a -> a.toString().equalsIgnoreCase(email))) continue;
            var match = Pattern.compile("/#/" + route + "/([A-Za-z0-9_-]{43})\\s").matcher((String) message.getContent());
            if (match.find()) found = match.group(1);
        }
        if (found == null) throw new AssertionError("No captured " + route + " link for the test account");
        return found;
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "Expected mail did not arrive");
    }
}
