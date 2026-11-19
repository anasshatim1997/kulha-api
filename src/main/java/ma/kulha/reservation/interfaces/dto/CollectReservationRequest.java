package ma.kulha.reservation.interfaces.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CollectReservationRequest(
        @Size(max = 80) String token,
        @Size(max = 40) String reference,
        @Pattern(regexp = "[A-Za-z0-9]{6}", message = "Pickup code must contain six letters or digits.")
        String pickupCode) {

    public void validateCredential() {
        boolean hasToken = token != null && !token.isBlank();
        boolean hasManualCode = reference != null && !reference.isBlank()
                && pickupCode != null && !pickupCode.isBlank();
        if (hasToken == hasManualCode) {
            throw new IllegalArgumentException("Provide either a QR token or a reservation reference and pickup code.");
        }
        if (!hasToken && ((reference == null || reference.isBlank()) || pickupCode == null || pickupCode.isBlank())) {
            throw new IllegalArgumentException("A reservation reference and pickup code are both required.");
        }
    }
}
