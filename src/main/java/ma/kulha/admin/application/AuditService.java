package ma.kulha.admin.application;

import java.time.Clock;
import java.util.UUID;
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
    public void append(UUID actorUserId, String action, String targetType, String targetId,
                       String reason, String result) {
        writer.insert(UUID.randomUUID(), actorUserId, action, targetType, targetId, reason, result, clock.instant());
    }
}
