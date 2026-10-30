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
import ma.kulha.identity.domain.repository.RefreshTokenRepository;
import ma.kulha.identity.domain.repository.UserRepository;
import ma.kulha.notification.application.NotificationApi;
import ma.kulha.notification.application.TemplateKey;
import ma.kulha.shared.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration RESET_TOKEN_TTL = Duration.ofHours(1);

    private final UserRepository users;
    private final EmailTokenRepository emailTokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final NotificationApi notifications;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String webBaseUrl;

    PasswordResetService(UserRepository users,
                         EmailTokenRepository emailTokens,
                         RefreshTokenRepository refreshTokens,
                         PasswordEncoder passwordEncoder,
                         NotificationApi notifications,
                         RateLimiter rateLimiter,
                         TransactionTemplate tx,
                         Clock clock,
                         @Value("${app.web-base-url}") String webBaseUrl) {
        this.users = users;
        this.emailTokens = emailTokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.tx = tx;
        this.clock = clock;
        this.webBaseUrl = webBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Sends a reset link if the account exists. Reveals nothing to the caller.
     * A new request cancels the previous link. At most 3 requests per hour per address;
     * beyond that the request is silently ignored, which also prevents mail flooding.
     */
    public void requestReset(String email) {
        String normalized = User.normalizeEmail(email);
        if (!rateLimiter.tryAcquire("forgot:acct:" + OpaqueTokens.hash(normalized), 3, Duration.ofHours(1)).allowed()) {
            log.warn("Password reset skipped: too many requests for one account");
            return;
        }
        users.findByEmail(normalized)
                .filter(user -> user.status() == UserStatus.ACTIVE)
                .ifPresent(user -> {
                    Instant now = clock.instant();
                    String rawToken = tx.execute(status -> {
                        emailTokens.invalidateUnused(user.id(), EmailTokenType.RESET, now);
                        String token = OpaqueTokens.generate();
                        emailTokens.insert(UUID.randomUUID(), user.id(), EmailTokenType.RESET,
                                OpaqueTokens.hash(token), now.plus(RESET_TOKEN_TTL), now);
                        return token;
                    });
                    String link = webBaseUrl + "/reset-password?token=" + rawToken;
                    notifications.sendEmail(user.email(), TemplateKey.RESET_PASSWORD, Map.of("link", link));
                });
    }

    /**
     * Sets a new password with a valid reset token (single use). All sessions of the user are closed.
     * The user is told by email that the password changed.
     */
    public void resetPassword(String rawToken, String newPassword) {
        Instant now = clock.instant();
        UUID userId = tx.execute(status -> {
            UUID id = emailTokens.consume(OpaqueTokens.hash(rawToken), EmailTokenType.RESET, now)
                    .orElseThrow(InvalidTokenException::new);
            users.updatePasswordHash(id, passwordEncoder.encode(newPassword));
            refreshTokens.revokeAllForUser(id, now);
            return id;
        });
        users.findById(userId).ifPresent(user ->
                notifications.sendEmail(user.email(), TemplateKey.PASSWORD_CHANGED, Map.of()));
    }
}
