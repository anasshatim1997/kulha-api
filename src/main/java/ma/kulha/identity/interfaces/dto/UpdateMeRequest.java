package ma.kulha.identity.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMeRequest(
        @NotBlank @Size(max = 120) String fullName,
        @Size(max = 30) String phone) {

    public UpdateMeRequest {
        fullName = fullName == null ? null : fullName.trim();
        phone = phone == null || phone.isBlank() ? null : phone.trim();
    }
}
