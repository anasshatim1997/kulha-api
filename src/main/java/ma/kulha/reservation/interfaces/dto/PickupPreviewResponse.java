package ma.kulha.reservation.interfaces.dto;

import java.time.Instant;

public record PickupPreviewResponse(
        String reference, String merchantName, String offerTitle, int quantity,
        int totalPriceMinor, String currency, Instant pickupStart, Instant pickupEnd,
        String status, String validationState, boolean canConfirm) {
}
