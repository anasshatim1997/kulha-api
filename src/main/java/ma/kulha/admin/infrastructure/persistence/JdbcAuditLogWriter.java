package ma.kulha.admin.infrastructure.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import ma.kulha.admin.application.AuditLogWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuditLogWriter implements AuditLogWriter {

    private final JdbcClient jdbc;

    JdbcAuditLogWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID id, UUID actorUserId, String action, String targetType, String targetId,
                       String reason, String result, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO admin.audit_log (id, actor_user_id, action, target_type, target_id, reason, result, created_at)
                VALUES (:id, :actor, :action, :targetType, :targetId, :reason, :result, :createdAt)
                """)
                .param("id", id)
                .param("actor", actorUserId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("reason", reason, Types.VARCHAR)
                .param("result", result)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }
}
