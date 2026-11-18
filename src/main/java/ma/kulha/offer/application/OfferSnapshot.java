package ma.kulha.offer.application;
import java.time.Instant;
import java.util.UUID;
import ma.kulha.offer.domain.model.OfferStatus;
/** Read-only offer and approved merchant data crossing the offer module boundary. */
public record OfferSnapshot(UUID offerId, UUID merchantId, int priceMinor, Instant pickupStart,
        Instant pickupEnd, OfferStatus status, int availableUnits, String title, String category,
        String merchantName, String merchantAddress, String merchantCity, String merchantPickupInstructions) {
    public OfferSnapshot(UUID offerId, UUID merchantId, int priceMinor, Instant pickupStart,
            Instant pickupEnd, OfferStatus status, int availableUnits) {
        this(offerId, merchantId, priceMinor, pickupStart, pickupEnd, status, availableUnits,
                null, null, null, null, null, null);
    }
}
