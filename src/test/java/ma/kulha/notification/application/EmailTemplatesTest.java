package ma.kulha.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailTemplatesTest {

    @Test
    void verificationEmailUsesABrandedButtonWithoutPrintingTheTokenUrl() {
        String link = "https://kulha.example/verify-email#token=secret-token";
        EmailMessage message = EmailTemplates.render(TemplateKey.VERIFY_EMAIL, Map.of("link", link));

        assertThat(message.subject()).contains("KULHA!").contains("Confirmez votre adresse");
        assertThat(message.body()).contains("Confirmer mon adresse e-mail").contains("24 heures")
                .doesNotContain("secret-token", link);
        assertThat(message.htmlBody()).contains("KULHA").contains("bgcolor=\"#f4c542\"")
                .contains("href=\"https://kulha.example/verify-email#token=secret-token\"")
                .contains("Confirmer mon adresse e-mail");
        assertThat(message.htmlBody().replace("href=\"" + link + "\"", "href=\"\""))
                .doesNotContain("secret-token");
    }

    @Test
    void missingActionLinkIsRejected() {
        assertThatThrownBy(() -> EmailTemplates.render(TemplateKey.VERIFY_EMAIL, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailTemplates.render(TemplateKey.RESET_PASSWORD, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetPasswordEmailUsesAButtonAndDoesNotPrintTheResetToken() {
        String link = "https://kulha.example/reset-password#token=secret-reset-token";
        EmailMessage message = EmailTemplates.render(TemplateKey.RESET_PASSWORD, Map.of("link", link));

        assertThat(message.subject()).contains("mot de passe");
        assertThat(message.body()).contains("Réinitialiser mon mot de passe").contains("1 heure")
                .doesNotContain("secret-reset-token", link);
        assertThat(message.htmlBody()).contains("href=\"https://kulha.example/reset-password#token=secret-reset-token\"");
        assertThat(message.htmlBody().replace("href=\"" + link + "\"", "href=\"\""))
                .doesNotContain("secret-reset-token");
    }

    @Test
    void passwordChangedNeedsNoParameters() {
        EmailMessage message = EmailTemplates.render(TemplateKey.PASSWORD_CHANGED, Map.of());

        assertThat(message.subject()).contains("modifié");
        assertThat(message.body()).isNotBlank();
        assertThat(message.htmlBody()).contains("<html");
    }

    @Test
    void merchantApprovalNamesTheShopAndEscapesHtml() {
        EmailMessage message = EmailTemplates.render(
                TemplateKey.MERCHANT_APPROVED, Map.of("merchantName", "Boulangerie <Atlas>"));

        assertThat(message.subject()).contains("approuvé");
        assertThat(message.body()).contains("Boulangerie <Atlas>");
        assertThat(message.htmlBody()).contains("Boulangerie &lt;Atlas&gt;");
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
