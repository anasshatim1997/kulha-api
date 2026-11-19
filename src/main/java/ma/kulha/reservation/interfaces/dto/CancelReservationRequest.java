package ma.kulha.reservation.interfaces.dto;

import jakarta.validation.constraints.Size;

public record CancelReservationRequest(
        @Size(max = 40) String reasonCode,
        @Size(max = 500) String comment) { }
