package ma.kulha.notification.application;

/** A multipart email with a readable text fallback and a branded HTML presentation. */
record EmailMessage(String subject, String body, String htmlBody) {
}
