package ma.kulha.merchant.interfaces.dto;

import jakarta.validation.constraints.Size;

public record MerchantApprovalRequest(@Size(max = 1000) String internalNote) {
}
