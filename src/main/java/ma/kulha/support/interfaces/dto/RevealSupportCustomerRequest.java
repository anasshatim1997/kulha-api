package ma.kulha.support.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RevealSupportCustomerRequest(
        @NotBlank @Size(max = 500) String reason) {
}
