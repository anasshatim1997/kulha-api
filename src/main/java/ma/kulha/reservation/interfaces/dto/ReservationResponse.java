package ma.kulha.reservation.interfaces.dto;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import ma.kulha.reservation.domain.model.Reservation;
public record ReservationResponse(String reference, UUID offerId, String offerTitle, String offerCategory,
        UUID merchantId, String merchantName, String merchantAddress, String merchantCity,
        String merchantPickupInstructions, Instant pickupStart, Instant pickupEnd, String pickupCode,
        String pickupQrPayload, int quantity, int unitPriceMinor, int totalPriceMinor, String currency,
        String status, String statusLabel, String classification, boolean canCancel,
        String pickupInstructions, Instant createdAt) {
    public static ReservationResponse from(Reservation r, Locale locale, Instant now) {
        return from(r, locale, now, null);
    }
    public static ReservationResponse from(Reservation r, Locale locale, Instant now, String pickupQrPayload) {
        String language = locale == null ? "fr" : locale.getLanguage();
        return new ReservationResponse(r.reference(), r.offerId(), r.offerTitle(), r.offerCategory(),
                r.merchantId(), r.merchantName(), r.merchantAddress(), r.merchantCity(),
                r.merchantPickupInstructions(), r.pickupStart(), r.pickupEnd(), r.pickupCode(), pickupQrPayload,
                r.quantity(), r.unitPriceMinor(), r.totalPriceMinor(), "MAD", r.status().name(),
                statusLabel(r.status().name(), language), r.group(now).name(), r.canCancel(now),
                pickupInstructions(language), r.createdAt());
    }
    private static String statusLabel(String status, String language) {
        return switch (language) {
            case "en" -> switch (status) {
                case "PENDING" -> "Pending"; case "CONFIRMED" -> "Confirmed"; case "COMPLETED" -> "Collected";
                case "CANCELLED" -> "Cancelled"; default -> "Expired";
            };
            case "ar" -> switch (status) {
                case "PENDING" -> "قيد الانتظار"; case "CONFIRMED" -> "مؤكدة"; case "COMPLETED" -> "تم الاستلام";
                case "CANCELLED" -> "ملغاة"; default -> "منتهية";
            };
            default -> switch (status) {
                case "PENDING" -> "En attente"; case "CONFIRMED" -> "Confirmée"; case "COMPLETED" -> "Récupérée";
                case "CANCELLED" -> "Annulée"; default -> "Expirée";
            };
        };
    }
    private static String pickupInstructions(String language) {
        return switch (language) {
            case "en" -> "Show your pickup QR or code at the shop during the selected pickup window.";
            case "ar" -> "أظهر رمز QR أو رمز الاستلام في المتجر خلال الفترة المحددة.";
            default -> "Présentez votre QR ou code de retrait au commerce pendant le créneau choisi.";
        };
    }
}
