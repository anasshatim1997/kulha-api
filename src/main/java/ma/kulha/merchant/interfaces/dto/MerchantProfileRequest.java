package ma.kulha.merchant.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;

public record MerchantProfileRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull BusinessType businessType,
        @NotBlank @Size(max = 255) String address,
        @NotBlank @Size(max = 80) String city,
        @NotBlank @Size(max = 30) @Pattern(regexp = "^[+0-9 ()-]{6,30}$") String phone,
        @NotBlank @Size(max = 500) String openingHours) {

    public Merchant.Profile toProfile() {
        return new Merchant.Profile(name, businessType, address, city, phone, openingHours);
    }
}
