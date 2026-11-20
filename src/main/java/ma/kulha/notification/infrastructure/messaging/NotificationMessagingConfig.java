package ma.kulha.notification.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Profile("!test")
class NotificationMessagingConfig {
    static final String NOTIFICATION_EXCHANGE = "kulha.events";
    static final String NOTIFICATION_QUEUE = "kulha.notifications";
    static final String NOTIFICATION_ROUTING_KEY = "notification.created";

    @Bean
    DirectExchange kulhaEventsExchange() {
        return new DirectExchange(NOTIFICATION_EXCHANGE, true, false);
    }

    @Bean
    Queue notificationsQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE).build();
    }

    @Bean
    Binding notificationsBinding(Queue notificationsQueue, DirectExchange kulhaEventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(kulhaEventsExchange).with(NOTIFICATION_ROUTING_KEY);
    }
}
