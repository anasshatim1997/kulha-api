package ma.kulha.offer.interfaces.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import ma.kulha.offer.domain.model.OfferCategory;

public record PublicOfferNearbyRequest(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @Min(100) @Max(25000) Integer radiusMeters,
        @Size(max = 80) String q,
        OfferCategory category,
        @Min(100) @Max(200000) Integer maxPriceMinor,
        Instant pickupFrom,
        Instant pickupTo,
        Boolean availableOnly,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size,
        @Size(max = 80) String city,
        @Size(max = 80) String district) {
}
