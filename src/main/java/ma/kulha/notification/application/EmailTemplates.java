package ma.kulha.notification.application;

import java.util.Map;

final class EmailTemplates {

    private EmailTemplates() {
    }

    static EmailMessage render(TemplateKey key, Map<String, String> params) {
        return switch (key) {
            case VERIFY_EMAIL -> new EmailMessage(
                    "Confirmez votre adresse e-mail - KULHA!",
                    """
                    Bonjour,

                    Pour confirmer votre adresse e-mail, ouvrez ce lien (valable 24 heures) :
                    %s

                    Si vous n'avez pas créé de compte KULHA!, ignorez ce message.

                    L'équipe KULHA!
                    """.formatted(require(params, "link")));
        };
    }

    private static String require(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing template parameter: " + name);
        }
        return value;
    }
}
