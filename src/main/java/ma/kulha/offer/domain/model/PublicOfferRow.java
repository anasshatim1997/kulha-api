package ma.kulha.offer.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicOfferRow(UUID id, UUID merchantId, String title, String description,
                             OfferCategory category, int priceMinor, Integer originalPriceMinor,
                             int quantityTotal, int availableUnits, Instant pickupStart, Instant pickupEnd,
                             AllergensStatus allergensStatus, List<String> allergens, String foodNotes,
                             Instant publishedAt, String imageUrl) {
    /** Compatibility constructor for callers which do not need publish-time discovery metadata. */
    public PublicOfferRow(UUID id, UUID merchantId, String title, String description, OfferCategory category,
                          int priceMinor, Integer originalPriceMinor, int quantityTotal, int availableUnits,
                          Instant pickupStart, Instant pickupEnd, AllergensStatus allergensStatus,
                          List<String> allergens, String foodNotes) {
        this(id, merchantId, title, description, category, priceMinor, originalPriceMinor, quantityTotal,
                availableUnits, pickupStart, pickupEnd, allergensStatus, allergens, foodNotes, null, null);
    }

    public PublicOfferRow(UUID id, UUID merchantId, String title, String description, OfferCategory category,
                          int priceMinor, Integer originalPriceMinor, int quantityTotal, int availableUnits,
                          Instant pickupStart, Instant pickupEnd, AllergensStatus allergensStatus,
                          List<String> allergens, String foodNotes, Instant publishedAt) {
        this(id, merchantId, title, description, category, priceMinor, originalPriceMinor, quantityTotal,
                availableUnits, pickupStart, pickupEnd, allergensStatus, allergens, foodNotes, publishedAt, null);
    }

    public PublicOfferRow {
        allergens = allergens == null ? List.of() : List.copyOf(allergens);
    }
}
