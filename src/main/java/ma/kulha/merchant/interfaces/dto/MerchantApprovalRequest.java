package ma.kulha.merchant.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MerchantApprovalRequest(@NotBlank @Size(max = 500) String reason, @Size(max = 1000) String internalNote) {
}
