package ma.kulha.notification.application;

public interface EmailSender {

    void send(String to, String subject, String textBody, String htmlBody);
}
