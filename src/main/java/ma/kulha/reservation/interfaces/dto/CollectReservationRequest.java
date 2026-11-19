package ma.kulha.reservation.interfaces.dto;

import jakarta.validation.constraints.Size;

public record CollectReservationRequest(
        @Size(max = 100) String qrPayload,
        @Size(max = 40) String reference,
        @Size(max = 12) String pickupCode) {
}
