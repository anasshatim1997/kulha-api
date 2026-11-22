package ma.kulha.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ma.kulha.notification.application.EmailSender;
import ma.kulha.notification.domain.EmailDeliveryEvent;
import ma.kulha.notification.domain.NotificationEvent;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class EmailDeliveryConsumerIdempotencyIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    ObjectMapper mapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void concurrentRedeliveryOfSameEmailEventSendsOnlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        EmailDeliveryEvent event = new EmailDeliveryEvent(eventId, "customer@example.test",
                "Reservation confirmed", "Your basket is booked.", "<p>Your basket is booked.</p>", Instant.now());
        String payload = mapper.writeValueAsString(event);
        EmailSender sender = mock(EmailSender.class);
        AtomicInteger sendCount = new AtomicInteger();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch allowFirstSendToFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendCount.incrementAndGet();
            firstSendStarted.countDown();
            if (!allowFirstSendToFinish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish the first email send.");
            }
            return null;
        }).when(sender).send(anyString(), anyString(), anyString(), anyString());

        EmailDeliveryConsumer consumer = new EmailDeliveryConsumer(mapper, sender, jdbc);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transactions.executeWithoutResult(status -> deliver(consumer, payload)));
            assertThat(firstSendStarted.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                transactions.executeWithoutResult(status -> deliver(consumer, payload));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();

            allowFirstSendToFinish.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            assertThat(sendCount).hasValue(1);
            assertThat(jdbc.sql("SELECT count(*) FROM notification.email_delivery_receipts WHERE event_id=:eventId")
                    .param("eventId", eventId).query(Long.class).single()).isEqualTo(1L);
        } finally {
            allowFirstSendToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateNotificationEventIsPersistedOnlyOnce() throws Exception {
        var customer = createUser("notification-redelivery@example.test", true);
        UUID eventId = UUID.randomUUID();
        NotificationEvent event = new NotificationEvent(eventId, customer.id(), "RESERVATION_CONFIRMED",
                "Reservation confirmed", "Your basket is booked.", "View reservation",
                "/reservations", Instant.now());
        String payload = mapper.writeValueAsString(event);
        NotificationEventConsumer consumer = new NotificationEventConsumer(mapper, jdbc);

        consumer.consume(payload);
        consumer.consume(payload);

        assertThat(jdbc.sql("SELECT count(*) FROM notification.notifications WHERE id=:id")
                .param("id", eventId).query(Long.class).single()).isEqualTo(1L);
    }

    private static void deliver(EmailDeliveryConsumer consumer, String payload) {
        try {
            consumer.deliver(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("Email event delivery failed.", failure);
        }
    }
}
