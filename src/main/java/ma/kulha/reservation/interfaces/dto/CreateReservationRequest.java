package ma.kulha.reservation.interfaces.dto;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
public record CreateReservationRequest(@NotNull UUID offerId, @NotNull @Min(1) Integer quantity,
        @NotNull Instant pickupStart, @NotNull Instant pickupEnd, Integer priceMinor, Integer totalPriceMinor) {
}
