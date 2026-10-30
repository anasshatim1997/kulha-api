package ma.kulha.identity.interfaces.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 10, max = 128) String password,
        @NotBlank @Size(max = 120) String fullName,
        @Size(max = 30) String phone) {
}
