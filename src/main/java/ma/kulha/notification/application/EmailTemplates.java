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
            case RESET_PASSWORD -> new EmailMessage(
                    "Réinitialisation de votre mot de passe - KULHA!",
                    """
                    Bonjour,

                    Vous avez demandé à réinitialiser votre mot de passe. Ouvrez ce lien (valable 1 heure) :
                    %s

                    Si vous n'êtes pas à l'origine de cette demande, ignorez ce message : votre mot de passe ne change pas.

                    L'équipe KULHA!
                    """.formatted(require(params, "link")));
            case PASSWORD_CHANGED -> new EmailMessage(
                    "Votre mot de passe a été modifié - KULHA!",
                    """
                    Bonjour,

                    Le mot de passe de votre compte KULHA! vient d'être modifié et vos sessions ont été fermées.

                    Si ce n'est pas vous, utilisez « Mot de passe oublié » pour reprendre le contrôle de votre compte.

                    L'équipe KULHA!
                    """);
            case MERCHANT_APPROVED -> new EmailMessage(
                    "Votre commerce est approuvé - KULHA!",
                    """
                    Bonjour,

                    Bonne nouvelle : « %s » est approuvé sur KULHA!. Vous pouvez maintenant publier des offres.

                    L'équipe KULHA!
                    """.formatted(require(params, "merchantName")));
            case MERCHANT_REJECTED -> new EmailMessage(
                    "Votre demande n'a pas été acceptée - KULHA!",
                    """
                    Bonjour,

                    Votre demande pour « %s » n'a pas été acceptée.

                    Motif : %s

                    Vous pouvez corriger les informations de votre commerce et soumettre à nouveau votre demande.

                    L'équipe KULHA!
                    """.formatted(require(params, "merchantName"), require(params, "reason")));
            case MERCHANT_SUSPENDED -> new EmailMessage(
                    "Votre commerce est suspendu - KULHA!",
                    """
                    Bonjour,

                    « %s » est suspendu sur KULHA!. Vos offres ne sont plus visibles.

                    Motif : %s

                    Contactez-nous pour en discuter.

                    L'équipe KULHA!
                    """.formatted(require(params, "merchantName"), require(params, "reason")));
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
