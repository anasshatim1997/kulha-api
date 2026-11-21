package ma.kulha.notification.application;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.notification.domain.NotificationEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Writes notification and optional email events in the caller's business transaction. */
@Component
public class NotificationOutbox {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final EmailOutbox emailOutbox;

    public NotificationOutbox(JdbcClient jdbc, ObjectMapper objectMapper, EmailOutbox emailOutbox) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.emailOutbox = emailOutbox;
    }

    @Transactional
    public UUID enqueue(UUID userId, String type, String title, String message,
                        String actionLabel, String actionUrl) {
        UUID eventId = UUID.randomUUID();
        NotificationEvent event = new NotificationEvent(eventId, userId, type, title, message,
                actionLabel, actionUrl, Instant.now());
        try {
            String payload = objectMapper.writeValueAsString(event);
            jdbc.sql("""
                    INSERT INTO notification.outbox_events
                        (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (:id, 'USER', :userId, 'notification.created', CAST(:payload AS jsonb))
                    """)
                    .param("id", eventId)
                    .param("userId", userId)
                    .param("payload", payload)
                    .update();
            if (shouldEmail(userId, type)) {
                String email = jdbc.sql("SELECT email FROM identity.users WHERE id = :userId")
                        .param("userId", userId)
                        .query(String.class)
                        .single();
                emailOutbox.enqueue(email, EmailTemplates.notification(title, message));
            }
            return eventId;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize notification event", exception);
        }
    }

    private boolean shouldEmail(UUID userId, String type) {
        return jdbc.sql("""
                SELECT COALESCE(p.email_enabled, TRUE)
                    AND CASE :type
                        WHEN 'RESERVATION_CONFIRMED' THEN COALESCE(p.reservation_confirmations, TRUE)
                        WHEN 'PICKUP_REMINDER' THEN COALESCE(p.pickup_reminders, TRUE)
                        WHEN 'RESERVATION_CANCELLED' THEN COALESCE(p.cancellations_and_status_changes, TRUE)
                        WHEN 'RESERVATION_COLLECTED' THEN COALESCE(p.cancellations_and_status_changes, TRUE)
                        WHEN 'RESERVATION_EXPIRED' THEN COALESCE(p.cancellations_and_status_changes, TRUE)
                        ELSE TRUE
                    END
                FROM identity.users u
                LEFT JOIN notification.notification_preferences p ON p.user_id = u.id
                WHERE u.id = :userId
                """)
                .param("userId", userId)
                .param("type", type)
                .query(Boolean.class)
                .single();
    }
}
