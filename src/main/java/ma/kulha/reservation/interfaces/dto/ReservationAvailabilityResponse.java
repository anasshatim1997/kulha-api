package ma.kulha.reservation.interfaces.dto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record ReservationAvailabilityResponse(UUID offerId, String offerTitle, String offerCategory,
        UUID merchantId, String merchantName, String merchantAddress, String merchantCity,
        String merchantPickupInstructions, int availableUnits, int maxQuantity, int selectedQuantity,
        Integer unitPriceMinor, Integer originalPriceMinor, Integer totalPriceMinor,
        Integer originalTotalPriceMinor, Integer savingsMinor, String currency, List<PickupSlot> slots) {
    public ReservationAvailabilityResponse {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
    public record PickupSlot(Instant start, Instant end, int availableUnits) { }
}
