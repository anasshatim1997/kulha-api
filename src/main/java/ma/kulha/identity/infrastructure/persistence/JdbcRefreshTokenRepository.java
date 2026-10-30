package ma.kulha.identity.infrastructure.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.model.RefreshTokenRecord;
import ma.kulha.identity.domain.repository.RefreshTokenRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private final JdbcClient jdbc;

    JdbcRefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID id, UUID userId, UUID familyId, String tokenHash, Instant expiresAt, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO identity.refresh_tokens (id, user_id, family_id, token_hash, expires_at, created_at)
                VALUES (:id, :userId, :familyId, :tokenHash, :expiresAt, :createdAt)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("familyId", familyId)
                .param("tokenHash", tokenHash)
                .param("expiresAt", toDb(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", toDb(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public Optional<RefreshTokenRecord> consume(String tokenHash, Instant now) {
        return jdbc.sql("""
                UPDATE identity.refresh_tokens
                SET revoked_at = :now
                WHERE token_hash = :tokenHash
                  AND revoked_at IS NULL
                  AND expires_at > :now
                RETURNING id, user_id, family_id
                """)
                .param("now", toDb(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> new RefreshTokenRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class)))
                .optional();
    }

    @Override
    public Optional<UUID> findFamilyByHash(String tokenHash) {
        return jdbc.sql("SELECT family_id FROM identity.refresh_tokens WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> rs.getObject("family_id", UUID.class))
                .optional();
    }

    @Override
    public void markReplaced(UUID oldTokenId, UUID newTokenId) {
        jdbc.sql("UPDATE identity.refresh_tokens SET replaced_by = :newId WHERE id = :oldId")
                .param("newId", newTokenId)
                .param("oldId", oldTokenId)
                .update();
    }

    @Override
    public int revokeFamily(UUID familyId, Instant now) {
        return jdbc.sql("""
                UPDATE identity.refresh_tokens
                SET revoked_at = :now
                WHERE family_id = :familyId AND revoked_at IS NULL
                """)
                .param("now", toDb(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("familyId", familyId)
                .update();
    }

    private static OffsetDateTime toDb(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
