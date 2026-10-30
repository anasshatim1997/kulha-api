package ma.kulha.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailTemplatesTest {

    @Test
    void verifyEmailContainsTheLink() {
        EmailMessage message = EmailTemplates.render(
                TemplateKey.VERIFY_EMAIL, Map.of("link", "http://localhost:3000/verify-email?token=abc"));

        assertThat(message.subject()).contains("KULHA!");
        assertThat(message.body()).contains("http://localhost:3000/verify-email?token=abc");
    }

    @Test
    void missingLinkIsRejected() {
        assertThatThrownBy(() -> EmailTemplates.render(TemplateKey.VERIFY_EMAIL, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
