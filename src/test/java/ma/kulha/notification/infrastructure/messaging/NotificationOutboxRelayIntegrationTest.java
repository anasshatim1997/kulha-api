package ma.kulha.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationOutboxRelayIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void nackSchedulesRetryAndLaterAckMarksEventPublished() {
        jdbc.sql("DELETE FROM notification.outbox_events").update();
        UUID eventId = insertOutboxEvent("notification.created", 0);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        NotificationOutboxRelay relay = new NotificationOutboxRelay(jdbc, rabbit);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));

        transactions.executeWithoutResult(status -> relay.publishPending());

        var retry = jdbc.sql("""
                SELECT attempt_count, published_at, dead_lettered_at, available_at, last_error
                FROM notification.outbox_events WHERE id=:id
                """).param("id", eventId)
                .query((rs, row) -> new RetryState(rs.getInt("attempt_count"), rs.getObject("published_at"),
                        rs.getObject("dead_lettered_at"), rs.getObject("available_at", java.time.OffsetDateTime.class),
                        rs.getString("last_error"))).single();
        assertThat(retry.attemptCount()).isEqualTo(1);
        assertThat(retry.publishedAt()).isNull();
        assertThat(retry.deadLetteredAt()).isNull();
        assertThat(retry.availableAt().toInstant()).isAfter(java.time.Instant.now());
        assertThat(retry.lastError()).isEqualTo("IllegalStateException");

        jdbc.sql("UPDATE notification.outbox_events SET available_at=now()-interval '1 second' WHERE id=:id")
                .param("id", eventId).update();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ack"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));

        transactions.executeWithoutResult(status -> relay.publishPending());

        var published = jdbc.sql("""
                SELECT attempt_count, published_at, last_error
                FROM notification.outbox_events WHERE id=:id
                """).param("id", eventId)
                .query((rs, row) -> new PublishedState(rs.getInt("attempt_count"), rs.getObject("published_at"),
                        rs.getString("last_error"))).single();
        assertThat(published.attemptCount()).isEqualTo(2);
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.lastError()).isNull();
    }

    @Test
    void tenthFailedAttemptMovesEmailPayloadToDeadLetterAndScrubsIt() {
        jdbc.sql("DELETE FROM notification.outbox_events").update();
        UUID eventId = insertOutboxEvent("email.requested", 9);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> new NotificationOutboxRelay(jdbc, rabbit).publishPending());

        var deadLetter = jdbc.sql("""
                SELECT attempt_count, dead_lettered_at, payload::text AS payload
                FROM notification.outbox_events WHERE id=:id
                """).param("id", eventId)
                .query((rs, row) -> new DeadLetterState(rs.getInt("attempt_count"),
                        rs.getObject("dead_lettered_at"), rs.getString("payload"))).single();
        assertThat(deadLetter.attemptCount()).isEqualTo(10);
        assertThat(deadLetter.deadLetteredAt()).isNotNull();
        assertThat(deadLetter.payload()).isEqualTo("{}");
    }

    private UUID insertOutboxEvent(String eventType, int attempts) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification.outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload, attempt_count)
                VALUES (:id, 'USER', :aggregateId, :eventType, CAST(:payload AS jsonb), :attempts)
                """).param("id", id).param("aggregateId", UUID.randomUUID()).param("eventType", eventType)
                .param("payload", "{\"email\":\"private@example.test\",\"body\":\"secret\"}")
                .param("attempts", attempts).update();
        return id;
    }

    private record RetryState(int attemptCount, Object publishedAt, Object deadLetteredAt,
                              java.time.OffsetDateTime availableAt, String lastError) {}
    private record PublishedState(int attemptCount, Object publishedAt, String lastError) {}
    private record DeadLetterState(int attemptCount, Object deadLetteredAt, String payload) {}
}
