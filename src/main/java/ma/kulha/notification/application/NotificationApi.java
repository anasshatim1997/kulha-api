package ma.kulha.notification.application;

import java.util.Map;

/** Public entry point of the notification module. Other modules call only this. */
public interface NotificationApi {

    /** Renders the template and sends the email. Never throws because of a delivery failure. */
    void sendEmail(String to, TemplateKey template, Map<String, String> params);
}
