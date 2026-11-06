package ma.kulha.admin.application;

import java.time.Instant;
import java.util.UUID;

public record AuditEntry(
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
}