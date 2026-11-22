package ma.kulha.notification.infrastructure.messaging;

import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import ma.kulha.notification.application.EmailSender;
import ma.kulha.notification.domain.EmailDeliveryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.simple.JdbcClient;

@Component
@Profile("!test")
class EmailDeliveryConsumer {
    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryConsumer.class);
    private static final int MAX_ATTEMPTS = 3;
    private final ObjectMapper mapper;
    private final EmailSender sender;
    private final JdbcClient jdbc;

    EmailDeliveryConsumer(ObjectMapper mapper, EmailSender sender, JdbcClient jdbc) {
        this.mapper = mapper;
        this.sender = sender;
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = NotificationMessagingConfig.EMAIL_QUEUE)
    @Transactional
    void deliver(String payload) throws Exception {
        EmailDeliveryEvent event = mapper.readValue(payload, EmailDeliveryEvent.class);
        jdbc.sql("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:eventId, 0))) event_delivery_lock
                """).param("eventId", event.id().toString()).query(Integer.class).single();
        boolean alreadyDelivered = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM notification.email_delivery_receipts WHERE event_id = :eventId)
                """).param("eventId", event.id()).query(Boolean.class).single();
        if (alreadyDelivered) return;
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                sender.send(event.recipient(), event.subject(), event.textBody(), event.htmlBody());
                jdbc.sql("""
                        INSERT INTO notification.email_delivery_receipts (event_id)
                        VALUES (:eventId) ON CONFLICT (event_id) DO NOTHING
                        """).param("eventId", event.id()).update();
                log.debug("Delivered transactional email event {}", event.id());
                return;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        TimeUnit.SECONDS.sleep(1L << (attempt - 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        }
        log.error("Email event {} failed after {} attempts; it will be dead-lettered",
                event.id(), MAX_ATTEMPTS);
        throw lastFailure == null ? new IllegalStateException("Email delivery failed") : lastFailure;
    }
}
