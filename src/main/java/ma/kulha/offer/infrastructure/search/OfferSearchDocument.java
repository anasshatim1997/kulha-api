package ma.kulha.offer.infrastructure.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OfferSearchDocument(
        UUID id,
        UUID merchantId,
        String title,
        String description,
        String category,
        int priceMinor,
        Integer originalPriceMinor,
        int quantityTotal,
        int availableUnits,
        Instant pickupStart,
        Instant pickupEnd,
        Instant publishedAt,
        Instant createdAt,
        String merchantName,
        String city,
        String district,
        String merchantAddress,
        String pickupInstructions,
        List<String> allergens,
        String allergensStatus,
        String foodNotes,
        String imageUrl,
        Double latitude,
        Double longitude) {
    public OfferSearchDocument(UUID id, UUID merchantId, String title, String description, String category,
            int priceMinor, Integer originalPriceMinor, int quantityTotal, int availableUnits,
            Instant pickupStart, Instant pickupEnd, Instant publishedAt, Instant createdAt,
            String merchantName, String city, String district, String merchantAddress, String pickupInstructions,
            List<String> allergens, String allergensStatus, String foodNotes) {
        this(id, merchantId, title, description, category, priceMinor, originalPriceMinor, quantityTotal, availableUnits,
                pickupStart, pickupEnd, publishedAt, createdAt, merchantName, city, district, merchantAddress,
                pickupInstructions, allergens, allergensStatus, foodNotes, null, null, null);
    }

    public OfferSearchDocument {
        allergens = allergens == null ? List.of() : List.copyOf(allergens);
    }
}
