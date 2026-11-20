package ma.kulha.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record NotificationEvent(
        UUID id,
        UUID userId,
        String type,
        String title,
        String message,
        String actionLabel,
        String actionUrl,
        Instant createdAt) {
}
