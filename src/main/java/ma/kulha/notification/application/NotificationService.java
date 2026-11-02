package ma.kulha.notification.application;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class NotificationService implements NotificationApi {

    private final EmailSender sender;

    NotificationService(EmailSender sender) {
        this.sender = sender;
    }

    @Override
    public void sendEmail(String to, TemplateKey template, Map<String, String> params) {
        EmailMessage message = EmailTemplates.render(template, params);
        sender.send(to, message.subject(), message.body(), message.htmlBody());
    }
}
