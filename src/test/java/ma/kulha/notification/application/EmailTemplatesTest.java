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
        assertThatThrownBy(() -> EmailTemplates.render(TemplateKey.RESET_PASSWORD, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetPasswordContainsTheLinkAndTheValidity() {
        EmailMessage message = EmailTemplates.render(
                TemplateKey.RESET_PASSWORD, Map.of("link", "http://localhost:3000/reset-password?token=xyz"));

        assertThat(message.subject()).contains("mot de passe");
        assertThat(message.body()).contains("http://localhost:3000/reset-password?token=xyz").contains("1 heure");
    }

    @Test
    void passwordChangedNeedsNoParameters() {
        EmailMessage message = EmailTemplates.render(TemplateKey.PASSWORD_CHANGED, Map.of());

        assertThat(message.subject()).contains("modifié");
        assertThat(message.body()).isNotBlank();
    }

    @Test
    void merchantApprovedNamesTheShop() {
        EmailMessage message = EmailTemplates.render(
                TemplateKey.MERCHANT_APPROVED, Map.of("merchantName", "Boulangerie Atlas"));

        assertThat(message.subject()).contains("approuvé");
        assertThat(message.body()).contains("Boulangerie Atlas");
    }

    @Test
    void merchantRejectedAndSuspendedExplainTheReason() {
        Map<String, String> params = Map.of("merchantName", "Boulangerie Atlas", "reason", "Adresse introuvable");

        assertThat(EmailTemplates.render(TemplateKey.MERCHANT_REJECTED, params).body())
                .contains("Boulangerie Atlas").contains("Adresse introuvable");
        assertThat(EmailTemplates.render(TemplateKey.MERCHANT_SUSPENDED, params).body())
                .contains("Boulangerie Atlas").contains("Adresse introuvable");
        assertThatThrownBy(() -> EmailTemplates.render(
                TemplateKey.MERCHANT_REJECTED, Map.of("merchantName", "Boulangerie Atlas")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
