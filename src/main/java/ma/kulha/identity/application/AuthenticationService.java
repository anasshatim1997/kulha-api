package ma.kulha.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.domain.model.RefreshTokenRecord;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.RefreshTokenRepository;
import ma.kulha.identity.domain.repository.UserRepository;
import ma.kulha.shared.ratelimit.RateLimitExceededException;
import ma.kulha.shared.ratelimit.RateLimitResult;
import ma.kulha.shared.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthenticationService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration refreshTtl;
    private final String dummyHash;

    AuthenticationService(UserRepository users,
                          RefreshTokenRepository refreshTokens,
                          PasswordEncoder passwordEncoder,
                          AccessTokenIssuer accessTokens,
                          RateLimiter rateLimiter,
                          TransactionTemplate tx,
                          Clock clock,
                          @Value("${app.security.refresh-ttl-days:30}") long refreshTtlDays) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.rateLimiter = rateLimiter;
        this.tx = tx;
        this.clock = clock;
        this.refreshTtl = Duration.ofDays(refreshTtlDays);
        // Checked when the email is unknown, so a wrong email and a wrong password take the same time.
        this.dummyHash = passwordEncoder.encode("kulha-dummy-password");
    }

    /**
     * At most 10 attempts per 15 minutes per account (counted for any email, known or not).
     * This is a temporary slowdown, never a permanent lock that a stranger could trigger.
     */
    public TokenPair login(String email, String password) {
        Instant now = clock.instant();
        String normalized = User.normalizeEmail(email);

        RateLimitResult limit = rateLimiter.tryAcquire(
                "login:acct:" + OpaqueTokens.hash(normalized), 10, Duration.ofMinutes(15));
        if (!limit.allowed()) {
            throw new RateLimitExceededException(limit.retryAfterSeconds());
        }

        User user = users.findByEmail(normalized).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(password, user != null ? user.passwordHash() : dummyHash);
        if (user == null || !passwordMatches || user.status() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }
        return tx.execute(status -> issuePair(user, UUID.randomUUID(), null, now));
    }

    /** Issues the existing KULHA session format for a previously verified external identity. */
    public TokenPair issueExternalLogin(User user) {
        if (user.status() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }
        Instant now = clock.instant();
        return tx.execute(status -> issuePair(user, UUID.randomUUID(), null, now));
    }

    /**
     * Exchanges a refresh token for a new pair. The old token is revoked, so each token works once.
     * If a token that was already used shows up again, someone may have copied it: the whole family
     * of tokens is revoked and the user has to log in again.
     */
    public TokenPair refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        String hash = OpaqueTokens.hash(rawRefreshToken);

        TokenPair pair = tx.execute(status -> {
            RefreshTokenRecord used = refreshTokens.consume(hash, now).orElse(null);
            if (used == null) {
                return null;
            }
            User user = users.findById(used.userId())
                    .filter(found -> found.status() == UserStatus.ACTIVE)
                    .orElse(null);
            if (user == null) {
                refreshTokens.revokeFamily(used.familyId(), now);
                return null;
            }
            return issuePair(user, used.familyId(), used.id(), now);
        });
        if (pair != null) {
            return pair;
        }

        // Not valid: if the token is known (already used, revoked or expired), end its whole family.
        refreshTokens.findFamilyByHash(hash).ifPresent(family -> refreshTokens.revokeFamily(family, now));
        throw new InvalidRefreshTokenException();
    }

    /** Ends the login session behind a refresh token. Always succeeds, so nothing is revealed. */
    public void logout(String rawRefreshToken) {
        Instant now = clock.instant();
        refreshTokens.findFamilyByHash(OpaqueTokens.hash(rawRefreshToken))
                .ifPresent(family -> refreshTokens.revokeFamily(family, now));
    }

    private TokenPair issuePair(User user, UUID familyId, UUID replacedTokenId, Instant now) {
        String rawRefreshToken = OpaqueTokens.generate();
        UUID newTokenId = UUID.randomUUID();
        refreshTokens.insert(newTokenId, user.id(), familyId,
                OpaqueTokens.hash(rawRefreshToken), now.plus(refreshTtl), now);
        if (replacedTokenId != null) {
            refreshTokens.markReplaced(replacedTokenId, newTokenId);
        }
        AccessToken access = accessTokens.issue(user.id(), now);
        return new TokenPair(access.value(), rawRefreshToken, access.expiresInSeconds());
    }
}
