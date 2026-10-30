package ma.kulha.identity.infrastructure.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.model.EmailTokenType;
import ma.kulha.identity.domain.repository.EmailTokenRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcEmailTokenRepository implements EmailTokenRepository {

    private final JdbcClient jdbc;

    JdbcEmailTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID id, UUID userId, EmailTokenType type, String tokenHash,
                       Instant expiresAt, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO identity.email_tokens (id, user_id, type, token_hash, expires_at, created_at)
                VALUES (:id, :userId, :type, :tokenHash, :expiresAt, :createdAt)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("type", type.name())
                .param("tokenHash", tokenHash)
                .param("expiresAt", toDb(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", toDb(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public Optional<UUID> consume(String tokenHash, EmailTokenType type, Instant now) {
        return jdbc.sql("""
                UPDATE identity.email_tokens
                SET used_at = :now
                WHERE token_hash = :tokenHash
                  AND type = :type
                  AND used_at IS NULL
                  AND expires_at > :now
                RETURNING user_id
                """)
                .param("now", toDb(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("tokenHash", tokenHash)
                .param("type", type.name())
                .query((rs, rowNum) -> rs.getObject("user_id", UUID.class))
                .optional();
    }

    @Override
    public void invalidateUnused(UUID userId, EmailTokenType type, Instant now) {
        jdbc.sql("""
                UPDATE identity.email_tokens
                SET used_at = :now
                WHERE user_id = :userId AND type = :type AND used_at IS NULL
                """)
                .param("now", toDb(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("userId", userId)
                .param("type", type.name())
                .update();
    }

    private static OffsetDateTime toDb(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
