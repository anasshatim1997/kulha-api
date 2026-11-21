package ma.kulha.support.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupportCaseMessageRequest(
        @NotBlank @Size(max = 3000) String message,
        boolean sendToCustomer) {
}
