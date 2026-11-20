package ma.kulha.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import ma.kulha.notification.domain.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
class NotificationEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;

    NotificationEventConsumer(ObjectMapper mapper, JdbcClient jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = NotificationMessagingConfig.NOTIFICATION_QUEUE)
    @Transactional
    void consume(String payload) throws Exception {
        NotificationEvent event = mapper.readValue(payload, NotificationEvent.class);
        jdbc.sql("""
                INSERT INTO notification.notifications
                    (id, user_id, type, title, message, action_label, action_url, created_at)
                VALUES (:id, :userId, :type, :title, :message, :actionLabel, :actionUrl, :createdAt)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", event.id())
                .param("userId", event.userId())
                .param("type", event.type())
                .param("title", event.title())
                .param("message", event.message())
                .param("actionLabel", event.actionLabel())
                .param("actionUrl", event.actionUrl())
                .param("createdAt", event.createdAt().atOffset(java.time.ZoneOffset.UTC))
                .update();
        log.debug("Persisted notification event {}", event.id());
    }
}
