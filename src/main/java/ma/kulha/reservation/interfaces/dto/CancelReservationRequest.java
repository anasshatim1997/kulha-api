package ma.kulha.reservation.interfaces.dto;

import jakarta.validation.constraints.Size;

public record CancelReservationRequest(String reasonCode, @Size(max = 500) String comment) {
}
