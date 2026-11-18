package ma.kulha.offer.application;

import java.util.UUID;

/** Application-facing projection for public offer autocomplete results. */
public record OfferSuggestion(
        UUID id,
        String title,
        String category,
        int priceMinor,
        String merchantName,
        String city,
        String district) {
}
