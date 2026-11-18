package ma.kulha.reservation.interfaces.dto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record ReservationAvailabilityResponse(UUID offerId, String offerTitle, UUID merchantId,
        String merchantName, int availableUnits, int maxQuantity, int selectedQuantity,
        Integer unitPriceMinor, Integer totalPriceMinor, String currency, List<PickupSlot> slots) {
    public ReservationAvailabilityResponse {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
    public record PickupSlot(Instant start, Instant end, int availableUnits) { }
}
