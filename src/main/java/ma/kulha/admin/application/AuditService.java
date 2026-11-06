package ma.kulha.admin.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
class AuditService implements AuditApi {

    private final AuditLogWriter writer;
    private final Clock clock;

    AuditService(AuditLogWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    @Override
    public void append(
            UUID actorUserId,
            String action,
            String targetType,
            String targetId,
            String reason,
            String result
    ) {
        String correlationId = MDC.get("correlationId");

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        writer.insert(
                UUID.randomUUID(),
                actorUserId,
                action,
                targetType,
                targetId,
                reason,
                result,
                correlationId,
                clock.instant()
        );
    }

    @Override
    public List<AuditEntry> history(String targetType, String targetId) {
        if (targetType == null || targetType.isBlank()) {
            throw new IllegalArgumentException("targetType must not be blank");
        }

        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }

        return writer.findByTarget(targetType, targetId);
    }
}