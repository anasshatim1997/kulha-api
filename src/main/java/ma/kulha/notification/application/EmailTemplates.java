package ma.kulha.notification.application;

import java.util.Map;

/** Branded, multipart transactional email templates. Token-bearing links are only button targets. */
final class EmailTemplates {

    private EmailTemplates() {
    }

    static EmailMessage render(TemplateKey key, Map<String, String> params) {
        return switch (key) {
            case VERIFY_EMAIL -> {
                String link = require(params, "link");
                yield action(
                        "Confirmez votre adresse e-mail - KULHA!",
                        "Votre compte KULHA! est presque prêt.",
                        "Merci d’avoir créé un compte KULHA!. Pour finaliser votre inscription et sécuriser votre accès, confirmez votre adresse e-mail.",
                        "Confirmer mon adresse e-mail", link,
                        "Ce bouton est valable pendant 24 heures. Si vous n’êtes pas à l’origine de cette inscription, vous pouvez ignorer cet e-mail.",
                        "La confirmation est nécessaire avant de déposer une demande pour votre commerce.");
            }
            case RESET_PASSWORD -> {
                String link = require(params, "link");
                yield action(
                        "Réinitialisation de votre mot de passe - KULHA!",
                        "Choisissez un nouveau mot de passe.",
                        "Nous avons reçu une demande de réinitialisation du mot de passe associé à votre compte KULHA!. Utilisez le bouton ci-dessous pour définir un nouveau mot de passe.",
                        "Réinitialiser mon mot de passe", link,
                        "Ce bouton est valable pendant 1 heure. Si vous n’êtes pas à l’origine de cette demande, ignorez cet e-mail : votre mot de passe ne sera pas modifié.",
                        "Pour votre sécurité, vos sessions seront fermées après la modification du mot de passe.");
            }
            case PASSWORD_CHANGED -> notice(
                    "Votre mot de passe a été modifié - KULHA!",
                    "Votre mot de passe a bien été modifié.",
                    "Le mot de passe de votre compte KULHA! vient d’être modifié et vos sessions ont été fermées.",
                    "Si cette modification ne vient pas de vous, utilisez la procédure « Mot de passe oublié » pour sécuriser votre compte.");
            case MERCHANT_APPROVED -> notice(
                    "Votre commerce est approuvé - KULHA!",
                    "Bienvenue parmi les commerces KULHA!.",
                    "Bonne nouvelle : « " + require(params, "merchantName") + " » a été approuvé sur KULHA!. Les droits commerçant sont maintenant activés sur votre compte.",
                    "Vous pourrez accéder aux outils commerçant à mesure de leur mise à disposition.");
            case MERCHANT_REJECTED -> notice(
                    "Mise à jour de votre demande commerçant - KULHA!",
                    "Votre demande nécessite une correction.",
                    "La demande concernant « " + require(params, "merchantName") + " » n’a pas été acceptée en l’état.",
                    "Motif : " + require(params, "reason") + " Vous pouvez corriger les informations de votre commerce depuis votre espace commerçant et soumettre à nouveau votre demande.");
            case MERCHANT_SUSPENDED -> notice(
                    "Statut de votre commerce - KULHA!",
                    "Votre espace commerçant est temporairement suspendu.",
                    "« " + require(params, "merchantName") + " » est suspendu sur KULHA!. Les offres de ce commerce ne sont plus visibles.",
                    "Motif : " + require(params, "reason") + " Contactez l’équipe KULHA! pour obtenir des précisions.");
        };
    }

    private static EmailMessage action(String subject, String heading, String intro, String buttonLabel,
                                       String link, String validity, String note) {
        String text = "Bonjour,\n\n" + intro + "\n\n" + buttonLabel
                + "\n\n" + validity + "\n\n" + note + "\n\nL’équipe KULHA!\nMoins de gaspillage, plus de goût.";
        String html = layout(heading,
                paragraph(intro)
                        + button(buttonLabel, link)
                        + paragraph(validity)
                        + paragraph(note)
                        + "<p style=\"margin:26px 0 0;color:#738276;font-size:12px;line-height:1.6\">Vous n’avez pas demandé cette action ? Aucune action ne sera effectuée sans votre confirmation.</p>");
        return new EmailMessage(subject, text, html);
    }

    private static EmailMessage notice(String subject, String heading, String intro, String note) {
        String text = "Bonjour,\n\n" + intro + "\n\n" + note + "\n\nL’équipe KULHA!\nMoins de gaspillage, plus de goût.";
        String html = layout(heading, paragraph(intro) + paragraph(note));
        return new EmailMessage(subject, text, html);
    }

    private static String layout(String heading, String content) {
        return """
                <!doctype html>
                <html lang="fr">
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f4f2e9;font-family:Arial,Helvetica,sans-serif;color:#173d2e">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f2e9;padding:32px 12px">
                    <tr><td align="center">
                      <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:600px;background:#fffdf8;border:1px solid #e7e1d4;border-radius:18px;overflow:hidden">
                        <tr><td style="padding:26px 32px;background:#073324;color:#fff8eb">
                          <div style="font-size:27px;font-weight:900;letter-spacing:-1px">KULHA<span style="color:#f7c542">!</span></div>
                          <div style="margin-top:5px;color:#d9e5d8;font-size:12px;letter-spacing:1.2px">MOINS DE GASPILLAGE, PLUS DE GOÛT.</div>
                        </td></tr>
                        <tr><td style="padding:34px 32px 30px">
                          <h1 style="margin:0 0 18px;color:#073324;font-size:24px;line-height:1.25">%s</h1>
                          %s
                          <p style="margin:30px 0 0;color:#173d2e;font-size:14px;line-height:1.6">L’équipe KULHA!</p>
                        </td></tr>
                        <tr><td style="padding:18px 32px;background:#faf7ef;border-top:1px solid #eee8dc;color:#738276;font-size:11px;line-height:1.6">
                          Cet e-mail concerne votre compte KULHA!. Ne partagez jamais un lien de confirmation ou de réinitialisation.
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body></html>
                """.formatted(escapeHtml(heading), content);
    }

    private static String paragraph(String text) {
        return "<p style=\"margin:0 0 18px;color:#42584a;font-size:15px;line-height:1.7\">"
                + escapeHtml(text) + "</p>";
    }

    private static String button(String label, String link) {
        return "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" style=\"margin:26px 0\"><tr><td bgcolor=\"#f4c542\" style=\"border-radius:10px\">"
                + "<a href=\"" + escapeHtml(link) + "\" style=\"display:inline-block;padding:15px 24px;color:#073324;text-decoration:none;font-size:15px;font-weight:800;border-radius:10px\">"
                + escapeHtml(label) + "</a></td></tr></table>";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String require(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing template parameter: " + name);
        }
        return value;
    }
}
