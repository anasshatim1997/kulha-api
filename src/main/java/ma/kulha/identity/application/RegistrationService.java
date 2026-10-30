package ma.kulha.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ma.kulha.identity.domain.model.EmailTokenType;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.EmailTokenRepository;
import ma.kulha.identity.domain.repository.UserRepository;
import ma.kulha.notification.application.NotificationApi;
import ma.kulha.notification.application.TemplateKey;
import ma.kulha.shared.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final Duration VERIFY_TOKEN_TTL = Duration.ofHours(24);

    private final UserRepository users;
    private final EmailTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final NotificationApi notifications;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String webBaseUrl;

    RegistrationService(UserRepository users,
                        EmailTokenRepository tokens,
                        PasswordEncoder passwordEncoder,
                        NotificationApi notifications,
                        RateLimiter rateLimiter,
                        TransactionTemplate tx,
                        Clock clock,
                        @Value("${app.web-base-url}") String webBaseUrl) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.tx = tx;
        this.clock = clock;
        this.webBaseUrl = webBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Creates an unverified customer account and sends the verification email.
     * If the email is already registered, nothing happens and nothing is revealed to the caller.
     */
    public void register(RegisterCommand command) {
        Instant now = clock.instant();
        // Hash first, so a new and an existing email take a similar amount of time.
        String passwordHash = passwordEncoder.encode(command.password());
        User user = User.register(command.email(), passwordHash, command.fullName(), command.phone(), now);

        String rawToken;
        try {
            rawToken = tx.execute(status -> {
                users.insert(user);
                return issueVerificationToken(user.id(), now);
            });
        } catch (DataIntegrityViolationException alreadyRegistered) {
            log.info("Registration ignored: the email is already registered");
            return;
        }
        sendVerificationEmail(user.email(), rawToken);
    }

    /** Verifies the email behind a token. A token works once. */
    public void verifyEmail(String rawToken) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            UUID userId = tokens.consume(OpaqueTokens.hash(rawToken), EmailTokenType.VERIFY, now)
                    .orElseThrow(InvalidTokenException::new);
            users.markEmailVerified(userId, now);
        });
    }

    /**
     * Sends a new verification email if the account exists and is not verified. Reveals nothing.
     * At most 3 emails per hour per address; beyond that the request is silently ignored.
     */
    public void resendVerification(String email) {
        String normalized = User.normalizeEmail(email);
        if (!rateLimiter.tryAcquire("resend:acct:" + OpaqueTokens.hash(normalized), 3, Duration.ofHours(1)).allowed()) {
            log.warn("Verification email skipped: too many requests for one account");
            return;
        }
        users.findByEmail(normalized)
                .filter(user -> !user.isEmailVerified() && user.status() == UserStatus.ACTIVE)
                .ifPresent(user -> {
                    Instant now = clock.instant();
                    String rawToken = tx.execute(status -> issueVerificationToken(user.id(), now));
                    sendVerificationEmail(user.email(), rawToken);
                });
    }

    private String issueVerificationToken(UUID userId, Instant now) {
        String rawToken = OpaqueTokens.generate();
        tokens.insert(UUID.randomUUID(), userId, EmailTokenType.VERIFY,
                OpaqueTokens.hash(rawToken), now.plus(VERIFY_TOKEN_TTL), now);
        return rawToken;
    }

    private void sendVerificationEmail(String email, String rawToken) {
        String link = webBaseUrl + "/verify-email?token=" + rawToken;
        notifications.sendEmail(email, TemplateKey.VERIFY_EMAIL, Map.of("link", link));
    }
}
