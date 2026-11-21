package ma.kulha.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record EmailDeliveryEvent(
        UUID id,
        String recipient,
        String subject,
        String textBody,
        String htmlBody,
        Instant createdAt) {
}
