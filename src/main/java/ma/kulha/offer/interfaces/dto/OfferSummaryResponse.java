package ma.kulha.offer.interfaces.dto;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.domain.model.OfferStatus;
import ma.kulha.offer.domain.model.OfferSummary;

public record OfferSummaryResponse(UUID id, String title, OfferCategory category, int priceMinor,
                                   int quantityTotal, int quantityReserved, int availableUnits,
                                   Instant pickupStart, Instant pickupEnd, OfferStatus status,
                                   Instant createdAt, long version) {
    public static OfferSummaryResponse from(OfferSummary o) {
        return new OfferSummaryResponse(o.id(), o.title(), o.category(), o.priceMinor(), o.quantityTotal(),
                o.quantityReserved(), o.availableUnits(), o.pickupStart(), o.pickupEnd(), o.status(), o.createdAt(), o.version());
    }
}
