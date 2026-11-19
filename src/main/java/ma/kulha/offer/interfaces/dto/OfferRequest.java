package ma.kulha.offer.interfaces.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.OfferCategory;

public record OfferRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 1000) String description,
        @NotNull OfferCategory category,
        @Min(100) @Max(200000) int priceMinor,
        @Min(100) @Max(200000) Integer originalPriceMinor,
        @Min(1) @Max(500) int quantityTotal,
        Instant pickupStart,
        Instant pickupEnd,
        @NotNull AllergensStatus allergensStatus,
        List<String> allergens,
        @Size(max = 500) String foodNotes,
        Long version,
        @jakarta.validation.constraints.Size(max = 2048) String imageUrl) {
    public OfferRequest(String title, String description, OfferCategory category, int priceMinor,
                        Integer originalPriceMinor, int quantityTotal, Instant pickupStart, Instant pickupEnd,
                        AllergensStatus allergensStatus, List<String> allergens, String foodNotes, Long version) {
        this(title, description, category, priceMinor, originalPriceMinor, quantityTotal, pickupStart, pickupEnd,
                allergensStatus, allergens, foodNotes, version, null);
    }
}
