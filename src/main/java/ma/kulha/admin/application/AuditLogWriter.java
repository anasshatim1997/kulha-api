package ma.kulha.admin.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogWriter {

    void insert(
            UUID id,
            UUID actorUserId,
            String action,
            String targetType,
            String targetId,
            String reason,
            String result,
            String correlationId,
            Instant createdAt
    );

    List<AuditEntry> findByTarget(String targetType, String targetId);
}