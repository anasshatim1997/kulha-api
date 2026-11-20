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

@Component
@Profile("!test")
class EmailDeliveryConsumer {
    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryConsumer.class);
    private static final int MAX_ATTEMPTS = 3;
    private final ObjectMapper mapper;
    private final EmailSender sender;

    EmailDeliveryConsumer(ObjectMapper mapper, EmailSender sender) {
        this.mapper = mapper;
        this.sender = sender;
    }

    @RabbitListener(queues = NotificationMessagingConfig.EMAIL_QUEUE)
    void deliver(String payload) throws Exception {
        EmailDeliveryEvent event = mapper.readValue(payload, EmailDeliveryEvent.class);
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                sender.send(event.recipient(), event.subject(), event.textBody(), event.htmlBody());
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
