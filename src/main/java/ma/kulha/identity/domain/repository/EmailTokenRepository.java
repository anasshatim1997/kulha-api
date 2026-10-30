package ma.kulha.identity.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.model.EmailTokenType;

public interface EmailTokenRepository {

    /** Stores only the hash of the token, never the token itself. */
    void insert(UUID id, UUID userId, EmailTokenType type, String tokenHash, Instant expiresAt, Instant createdAt);

    /**
     * Atomically marks a valid token (right type, not used, not expired) as used and returns its user.
     * Returns empty if the token is unknown, already used or expired. A token can succeed only once.
     */
    Optional<UUID> consume(String tokenHash, EmailTokenType type, Instant now);
}
