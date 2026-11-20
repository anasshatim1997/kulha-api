package ma.kulha.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ma.kulha.notification.domain.NotificationEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Writes notification events in the caller's business transaction. */
@Component
public class NotificationOutbox {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public NotificationOutbox(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
            return eventId;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize notification event", exception);
        }
    }
}
