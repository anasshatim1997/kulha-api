package ma.kulha.offer.interfaces.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantPublicProfile;
import ma.kulha.offer.domain.model.PublicOfferRow;

public record PublicOfferDetailResponse(UUID id, String title, String description, String category,
                                         int priceMinor, Integer originalPriceMinor, int quantityTotal,
                                         int availableUnits, Instant pickupStart, Instant pickupEnd,
                                         String allergensStatus, List<String> allergens, String foodNotes, String imageUrl,
                                         PublicMerchantResponse merchant) {
    public static PublicOfferDetailResponse from(PublicOfferRow offer, MerchantPublicProfile merchant) {
        return new PublicOfferDetailResponse(offer.id(), offer.title(), offer.description(), offer.category().name(),
                offer.priceMinor(), offer.originalPriceMinor(), offer.quantityTotal(), offer.availableUnits(),
                offer.pickupStart(), offer.pickupEnd(), offer.allergensStatus().name(), offer.allergens(),
                offer.foodNotes(), offer.imageUrl(), PublicMerchantResponse.from(merchant));
    }
}
