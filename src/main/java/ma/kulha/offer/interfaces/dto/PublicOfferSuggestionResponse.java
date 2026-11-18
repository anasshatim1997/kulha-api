package ma.kulha.offer.interfaces.dto;

import java.util.UUID;
import ma.kulha.offer.application.OfferSuggestion;

public record PublicOfferSuggestionResponse(
        UUID id,
        String title,
        String category,
        int priceMinor,
        String merchantName,
        String city,
        String district) {

    public static PublicOfferSuggestionResponse from(OfferSuggestion row) {
        return new PublicOfferSuggestionResponse(row.id(), row.title(), row.category(), row.priceMinor(),
                row.merchantName(), row.city(), row.district());
    }
}
