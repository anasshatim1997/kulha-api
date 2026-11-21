package ma.kulha.notification.application;

import tools.jackson.core.JacksonException;
import java.time.Instant;
import java.util.UUID;
import ma.kulha.notification.domain.EmailDeliveryEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class EmailOutbox {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public EmailOutbox(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public UUID enqueue(String recipient, EmailMessage message) {
        UUID eventId = UUID.randomUUID();
        EmailDeliveryEvent event = new EmailDeliveryEvent(eventId, recipient, message.subject(),
                message.body(), message.htmlBody(), Instant.now());
        try {
            jdbc.sql("""
                    INSERT INTO notification.outbox_events
                        (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (:id, 'EMAIL', :aggregateId, 'email.requested', CAST(:payload AS jsonb))
                    """)
                    .param("id", eventId)
                    .param("aggregateId", eventId)
                    .param("payload", mapper.writeValueAsString(event))
                    .update();
            return eventId;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize email delivery event", exception);
        }
    }
}
