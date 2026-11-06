package ma.kulha.offer.domain.model;

import java.time.Instant;
import java.util.UUID;

public record OfferSummary(UUID id, String title, OfferCategory category, int priceMinor,
                           int quantityTotal, int quantityReserved, Instant pickupStart,
                           Instant pickupEnd, OfferStatus status, Instant createdAt, long version) {
    public int availableUnits() { return quantityTotal - quantityReserved; }
}
