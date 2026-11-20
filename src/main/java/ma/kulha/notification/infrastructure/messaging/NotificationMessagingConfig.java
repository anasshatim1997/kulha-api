package ma.kulha.notification.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Profile("!test")
class NotificationMessagingConfig {
    static final String NOTIFICATION_EXCHANGE = "kulha.events";
    static final String NOTIFICATION_QUEUE = "kulha.notifications";
    static final String NOTIFICATION_ROUTING_KEY = "notification.created";
    static final String EMAIL_QUEUE = "kulha.emails";
    static final String EMAIL_ROUTING_KEY = "email.requested";
    static final String DEAD_LETTER_EXCHANGE = "kulha.events.dlx";
    static final String EMAIL_DEAD_LETTER_QUEUE = "kulha.emails.dead";
    static final String NOTIFICATION_DEAD_LETTER_QUEUE = "kulha.notifications.dead";
    static final String EMAIL_DEAD_LETTER_ROUTING_KEY = "email.dead";

    @Bean
    DirectExchange kulhaEventsExchange() {
        return new DirectExchange(NOTIFICATION_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange kulhaDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue notificationsQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE).deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey("notification.dead").build();
    }

    @Bean
    Queue emailsQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE).deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(EMAIL_DEAD_LETTER_ROUTING_KEY).build();
    }

    @Bean
    Queue emailDeadLetterQueue() {
        return QueueBuilder.durable(EMAIL_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(NOTIFICATION_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding notificationsBinding(@Qualifier("notificationsQueue") Queue notificationsQueue,
                                @Qualifier("kulhaEventsExchange") DirectExchange kulhaEventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(kulhaEventsExchange).with(NOTIFICATION_ROUTING_KEY);
    }

    @Bean
    Binding emailsBinding(@Qualifier("emailsQueue") Queue emailsQueue,
                          @Qualifier("kulhaEventsExchange") DirectExchange kulhaEventsExchange) {
        return BindingBuilder.bind(emailsQueue).to(kulhaEventsExchange).with(EMAIL_ROUTING_KEY);
    }

    @Bean
    Binding emailDeadLetterBinding(@Qualifier("emailDeadLetterQueue") Queue emailDeadLetterQueue,
                                   @Qualifier("kulhaDeadLetterExchange") DirectExchange kulhaDeadLetterExchange) {
        return BindingBuilder.bind(emailDeadLetterQueue).to(kulhaDeadLetterExchange)
                .with(EMAIL_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    Binding notificationDeadLetterBinding(@Qualifier("notificationDeadLetterQueue") Queue notificationDeadLetterQueue,
                                          @Qualifier("kulhaDeadLetterExchange") DirectExchange kulhaDeadLetterExchange) {
        return BindingBuilder.bind(notificationDeadLetterQueue).to(kulhaDeadLetterExchange)
                .with("notification.dead");
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
