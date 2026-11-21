package ma.kulha.support.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OpenSupportCaseRequest(
        @NotBlank @Pattern(regexp = "FOOD_SAFETY|ORDER_ISSUE|ACCOUNT|PRIVACY") String category,
        @NotBlank @Size(max = 180) String subject,
        @NotBlank @Size(max = 3000) String description,
        @Size(max = 40) String reservationReference) {
}
