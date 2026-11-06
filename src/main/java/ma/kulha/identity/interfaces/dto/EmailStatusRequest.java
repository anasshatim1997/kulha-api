package ma.kulha.identity.interfaces.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailStatusRequest(
        @NotBlank @Email @Size(max = 254) String email) {
}
