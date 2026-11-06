package ma.kulha.offer.interfaces.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.domain.model.OfferStatus;

public record OfferResponse(UUID id, UUID merchantId, String title, String description, OfferCategory category,
                            int priceMinor, Integer originalPriceMinor, int quantityTotal, int quantityReserved,
                            int availableUnits, Instant pickupStart, Instant pickupEnd,
                            AllergensStatus allergensStatus, List<String> allergens, String foodNotes,
                            OfferStatus status, Instant publishedAt, long version, Instant createdAt, Instant updatedAt) {
    public static OfferResponse from(Offer o) {
        return new OfferResponse(o.id(), o.merchantId(), o.title(), o.description(), o.category(), o.priceMinor(),
                o.originalPriceMinor(), o.quantityTotal(), o.quantityReserved(), o.availableUnits(), o.pickupStart(),
                o.pickupEnd(), o.allergensStatus(), o.allergens(), o.foodNotes(), o.status(), o.publishedAt(),
                o.version(), o.createdAt(), o.updatedAt());
    }
}
