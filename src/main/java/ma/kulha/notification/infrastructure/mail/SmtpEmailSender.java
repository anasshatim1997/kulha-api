package ma.kulha.notification.infrastructure.mail;

import java.nio.charset.StandardCharsets;
import ma.kulha.notification.application.EmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String textBody, String htmlBody) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper message = new MimeMessageHelper(
                    mimeMessage, true, StandardCharsets.UTF_8.name());
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(textBody, htmlBody);
            mailSender.send(mimeMessage);
        } catch (MailException | MessagingException e) {
            // Never log either body: transactional emails can contain one-time credentials.
            log.error("Email delivery failed (subject: {}): {}", subject, e.getClass().getSimpleName());
        }
    }
}
