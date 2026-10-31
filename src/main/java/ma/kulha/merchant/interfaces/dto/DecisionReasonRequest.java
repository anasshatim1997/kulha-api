package ma.kulha.merchant.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DecisionReasonRequest(@NotBlank @Size(max = 500) String reason) {
}
