package ma.kulha.merchant.interfaces.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddMerchantStaffRequest(
        @NotBlank @Email @Size(max = 254) String email) {
    public AddMerchantStaffRequest {
        email = email == null ? null : email.trim();
    }
}
