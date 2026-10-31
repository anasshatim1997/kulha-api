package ma.kulha.admin.application;

import java.util.UUID;

/** Public entry point of the admin module for the audit log. Entries can only be added. */
public interface AuditApi {

    /**
     * Records a sensitive action. Call it inside the same transaction as the action itself,
     * so the action and its audit entry are saved together or not at all.
     */
    void append(UUID actorUserId, String action, String targetType, String targetId, String reason, String result);
}
