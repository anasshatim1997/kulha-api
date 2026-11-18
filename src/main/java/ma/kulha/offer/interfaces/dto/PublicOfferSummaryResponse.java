package ma.kulha.offer.interfaces.dto;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantPublicProfile;
import ma.kulha.offer.domain.model.PublicOfferRow;

public record PublicOfferSummaryResponse(UUID id, String title, String category, int priceMinor,
                                         Integer originalPriceMinor, int quantityTotal, int availableUnits,
                                         Instant pickupStart, Instant pickupEnd, PublicMerchantResponse merchant,
                                         Double distanceKm, Instant publishedAt) {
    public static PublicOfferSummaryResponse from(PublicOfferRow offer, MerchantPublicProfile merchant) {
        return from(offer, merchant, null);
    }

    public static PublicOfferSummaryResponse from(PublicOfferRow offer, MerchantPublicProfile merchant, Double distanceKm) {
        return new PublicOfferSummaryResponse(offer.id(), offer.title(), offer.category().name(),
                offer.priceMinor(), offer.originalPriceMinor(), offer.quantityTotal(), offer.availableUnits(),
                offer.pickupStart(), offer.pickupEnd(), PublicMerchantResponse.from(merchant), distanceKm,
                offer.publishedAt());
    }
}
