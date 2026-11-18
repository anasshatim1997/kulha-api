package ma.kulha.merchant.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.AssertTrue;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;

public record MerchantProfileRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull BusinessType businessType,
        @NotBlank @Size(max = 255) String address,
        @NotBlank @Size(max = 80) String city,
        @Size(max = 80) String district,
        @NotBlank @Size(max = 30) @Pattern(regexp = "^[+0-9 ()-]{6,30}$") String phone,
        @NotBlank @Size(max = 500) String openingHours,
        @Size(max = 500) String pickupInstructions,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude) {

    @AssertTrue(message = "latitude and longitude must be provided together and must be finite")
    public boolean isCoordinatePairValid() {
        return (latitude == null && longitude == null)
                || (latitude != null && longitude != null
                    && Double.isFinite(latitude) && Double.isFinite(longitude));
    }

    public Merchant.Profile toProfile() {
        return new Merchant.Profile(name, businessType, address, city, district, phone, openingHours, pickupInstructions, latitude, longitude);
    }
}
