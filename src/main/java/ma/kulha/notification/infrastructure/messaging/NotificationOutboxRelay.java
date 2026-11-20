package ma.kulha.notification.infrastructure.messaging;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import ma.kulha.notification.infrastructure.messaging.NotificationMessagingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
class NotificationOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxRelay.class);
    private static final int BATCH_SIZE = 25;
    private static final int MAX_ATTEMPTS = 10;
    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;

    NotificationOutboxRelay(JdbcClient jdbc, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${app.notification.outbox.poll-ms:1000}")
    @Transactional
    void publishPending() {
        List<OutboxRow> pending = jdbc.sql("""
                SELECT id, event_type, payload::text AS payload, attempt_count
                FROM notification.outbox_events
                WHERE published_at IS NULL AND dead_lettered_at IS NULL AND available_at <= now()
                ORDER BY occurred_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
                """)
                .param("batchSize", BATCH_SIZE)
                .query((rs, row) -> new OutboxRow(
                        rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getInt("attempt_count")))
                .list();

        for (OutboxRow row : pending) {
            try {
                CorrelationData correlation = new CorrelationData(row.id().toString());
                rabbit.convertAndSend(NotificationMessagingConfig.NOTIFICATION_EXCHANGE,
                        row.eventType(), row.payload(), correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ negatively acknowledged event");
                if (correlation.getReturned() != null) {
                    throw new IllegalStateException("RabbitMQ returned an unroutable event");
                }
                jdbc.sql("""
                        UPDATE notification.outbox_events
                        SET published_at = now(), attempt_count = attempt_count + 1, last_error = NULL
                        WHERE id = :id
                        """).param("id", row.id()).update();
            } catch (Exception failure) {
                int attempts = row.attemptCount() + 1;
                String message = failure.getClass().getSimpleName();
                if (attempts >= MAX_ATTEMPTS) {
                    jdbc.sql("""
                            UPDATE notification.outbox_events
                            SET attempt_count = :attempts, dead_lettered_at = now(), last_error = :error
                            WHERE id = :id
                            """).param("attempts", attempts).param("error", message).param("id", row.id()).update();
                    log.error("Outbox event {} moved to dead letter after {} attempts ({})",
                            row.id(), attempts, message);
                } else {
                    long delaySeconds = Math.min(900, 1L << Math.min(attempts, 9));
                    jdbc.sql("""
                            UPDATE notification.outbox_events
                            SET attempt_count = :attempts,
                                available_at = now() + (:delaySeconds * interval '1 second'),
                                last_error = :error
                            WHERE id = :id
                            """).param("attempts", attempts).param("delaySeconds", delaySeconds)
                            .param("error", message).param("id", row.id()).update();
                    log.warn("Outbox event {} will retry in {} seconds ({})", row.id(), delaySeconds, message);
                }
            }
        }
    }

    private record OutboxRow(UUID id, String eventType, String payload, int attemptCount) {}
}
