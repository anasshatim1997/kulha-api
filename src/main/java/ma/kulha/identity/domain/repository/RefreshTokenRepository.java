package ma.kulha.identity.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.model.RefreshTokenRecord;

public interface RefreshTokenRepository {

    /** Stores only the hash of the token. All tokens of one login share a family id. */
    void insert(UUID id, UUID userId, UUID familyId, String tokenHash, Instant expiresAt, Instant createdAt);

    /**
     * Atomically revokes a token that is still valid (not revoked, not expired) and returns it.
     * Returns empty if the token is unknown, already used, revoked or expired.
     */
    Optional<RefreshTokenRecord> consume(String tokenHash, Instant now);

    /** The family of a token in any state (used or not), to detect reuse. */
    Optional<UUID> findFamilyByHash(String tokenHash);

    void markReplaced(UUID oldTokenId, UUID newTokenId);

    /** Revokes every token of the family that is still active. Returns how many were revoked. */
    int revokeFamily(UUID familyId, Instant now);

    /** Revokes every active token of a user, on every device. Returns how many were revoked. */
    int revokeAllForUser(UUID userId, Instant now);
}
