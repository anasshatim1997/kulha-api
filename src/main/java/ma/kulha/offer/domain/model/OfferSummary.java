package ma.kulha.offer.domain.model;

import java.time.Instant;
import java.util.UUID;

public record OfferSummary(UUID id, String title, OfferCategory category, int priceMinor,
                           int quantityTotal, int quantityReserved, Instant pickupStart,
                           Instant pickupEnd, OfferStatus status, Instant createdAt, long version, String imageUrl) {
    public OfferSummary(UUID id, String title, OfferCategory category, int priceMinor,
                        int quantityTotal, int quantityReserved, Instant pickupStart,
                        Instant pickupEnd, OfferStatus status, Instant createdAt, long version) {
        this(id, title, category, priceMinor, quantityTotal, quantityReserved, pickupStart,
                pickupEnd, status, createdAt, version, null);
    }
    public int availableUnits() { return quantityTotal - quantityReserved; }
}
