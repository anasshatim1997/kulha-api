package ma.kulha.notification.infrastructure.messaging;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!test")
class NotificationOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxRelay.class);
    private static final int BATCH_SIZE = 10;
    private static final int MAX_ATTEMPTS = 10;
    private static final int CLAIM_LEASE_MINUTES = 3;
    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;

    NotificationOutboxRelay(JdbcClient jdbc, RabbitTemplate rabbit, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.notification.outbox.poll-ms:1000}")
    void publishPending() {
        List<OutboxRow> pending = transactions.execute(status -> claimPending());
        if (pending == null || pending.isEmpty()) return;

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
                transactions.executeWithoutResult(status -> markPublished(row.id()));
            } catch (Exception failure) {
                int attempts = row.attemptCount() + 1;
                String message = failure.getClass().getSimpleName();
                if (attempts >= MAX_ATTEMPTS) {
                    transactions.executeWithoutResult(status -> deadLetter(row.id(), attempts));
                    log.error("Outbox event {} moved to dead letter after {} attempts ({})",
                            row.id(), attempts, message, failure);
                } else {
                    long delaySeconds = Math.min(900, 1L << Math.min(attempts, 9));
                    transactions.executeWithoutResult(status -> scheduleRetry(row.id(), attempts, delaySeconds));
                    log.warn("Outbox event {} will retry in {} seconds ({})",
                            row.id(), delaySeconds, message, failure);
                }
            }
        }
    }

    private List<OutboxRow> claimPending() {
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
        if (!pending.isEmpty()) {
            jdbc.sql("""
                    UPDATE notification.outbox_events
                    SET available_at = now() + (:leaseMinutes * interval '1 minute')
                    WHERE id IN (:ids) AND published_at IS NULL AND dead_lettered_at IS NULL
                    """).param("leaseMinutes", CLAIM_LEASE_MINUTES)
                    .param("ids", pending.stream().map(OutboxRow::id).toList()).update();
        }
        return pending;
    }

    private void markPublished(UUID id) {
        jdbc.sql("""
                UPDATE notification.outbox_events
                SET published_at = now(), attempt_count = attempt_count + 1,
                    payload = CASE WHEN event_type = 'email.requested' THEN '{}'::jsonb ELSE payload END
                WHERE id = :id AND published_at IS NULL AND dead_lettered_at IS NULL
                """).param("id", id).update();
    }

    private void scheduleRetry(UUID id, int attempts, long delaySeconds) {
        jdbc.sql("""
                UPDATE notification.outbox_events
                SET attempt_count = :attempts,
                    available_at = now() + (:delaySeconds * interval '1 second')
                WHERE id = :id AND published_at IS NULL AND dead_lettered_at IS NULL
                """).param("attempts", attempts).param("delaySeconds", delaySeconds).param("id", id).update();
    }

    private void deadLetter(UUID id, int attempts) {
        jdbc.sql("""
                UPDATE notification.outbox_events
                SET attempt_count = :attempts, dead_lettered_at = now(),
                    payload = CASE WHEN event_type = 'email.requested' THEN '{}'::jsonb ELSE payload END
                WHERE id = :id AND published_at IS NULL AND dead_lettered_at IS NULL
                """).param("attempts", attempts).param("id", id).update();
    }

    private record OutboxRow(UUID id, String eventType, String payload, int attemptCount) {}
}
