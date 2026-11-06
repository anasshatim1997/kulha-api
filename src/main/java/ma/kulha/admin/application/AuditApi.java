package ma.kulha.admin.application;

import java.util.List;
import java.util.UUID;

public interface AuditApi {

    void append(
            UUID actorUserId,
            String action,
            String targetType,
            String targetId,
            String reason,
            String result
    );

    List<AuditEntry> history(String targetType, String targetId);
}