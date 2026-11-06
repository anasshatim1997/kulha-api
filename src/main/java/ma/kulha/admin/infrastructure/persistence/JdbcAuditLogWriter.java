package ma.kulha.admin.infrastructure.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import ma.kulha.admin.application.AuditEntry;
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
    public void insert(
            UUID id,
            UUID actorUserId,
            String action,
            String targetType,
            String targetId,
            String reason,
            String result,
            String correlationId,
            Instant createdAt
    ) {
        jdbc.sql("""
                INSERT INTO admin.audit_log (
                    id,
                    actor_user_id,
                    action,
                    target_type,
                    target_id,
                    reason,
                    result,
                    correlation_id,
                    created_at
                )
                VALUES (
                    :id,
                    :actor,
                    :action,
                    :targetType,
                    :targetId,
                    :reason,
                    :result,
                    :correlationId,
                    :createdAt
                )
                """)
                .param("id", id)
                .param("actor", actorUserId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("reason", reason, Types.VARCHAR)
                .param("result", result)
                .param("correlationId", correlationId, Types.VARCHAR)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public List<AuditEntry> findByTarget(String targetType, String targetId) {
        return jdbc.sql("""
                SELECT
                    id,
                    actor_user_id,
                    action,
                    target_type,
                    target_id,
                    reason,
                    result,
                    correlation_id,
                    created_at
                FROM admin.audit_log
                WHERE target_type = :targetType
                  AND target_id = :targetId
                ORDER BY created_at ASC, id ASC
                """)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .query((rs, rowNum) -> new AuditEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("reason"),
                        rs.getString("result"),
                        rs.getString("correlation_id"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()
                ))
                .list();
    }
}