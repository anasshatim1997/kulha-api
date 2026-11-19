package ma.kulha.offer.application;
import java.time.Instant;
import java.util.UUID;
/** Read-only offer and approved merchant data crossing the offer module boundary. */
public record OfferSnapshot(UUID offerId, UUID merchantId, int priceMinor, Integer originalPriceMinor,
        Instant pickupStart, Instant pickupEnd, boolean published, int availableUnits, String title,
        String category, String merchantName, String merchantAddress, String merchantCity,
        String merchantPickupInstructions) {
}
