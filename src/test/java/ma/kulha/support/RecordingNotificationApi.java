package ma.kulha.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import ma.kulha.notification.application.NotificationApi;
import ma.kulha.notification.application.TemplateKey;

/** Test double: records the emails instead of sending them. */
public class RecordingNotificationApi implements NotificationApi {

    public record SentEmail(String to, TemplateKey template, Map<String, String> params) {
    }

    private final List<SentEmail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendEmail(String to, TemplateKey template, Map<String, String> params) {
        sent.add(new SentEmail(to, template, Map.copyOf(params)));
    }

    public List<SentEmail> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }

    /** The one-time token at the end of the "link" parameter of an email. */
    public static String tokenFrom(SentEmail email) {
        String link = email.params().get("link");
        return link.substring(link.indexOf("token=") + "token=".length());
    }
}
