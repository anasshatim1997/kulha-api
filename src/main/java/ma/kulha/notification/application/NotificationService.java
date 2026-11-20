package ma.kulha.notification.application;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService implements NotificationApi {
    private final EmailOutbox outbox;

    NotificationService(EmailOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public void sendEmail(String to, TemplateKey template, Map<String, String> params) {
        EmailMessage message = EmailTemplates.render(template, params);
        outbox.enqueue(to, message);
    }
}
