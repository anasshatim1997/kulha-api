package ma.kulha.merchant.interfaces.dto;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.merchant.domain.model.MerchantStatus;

public record MerchantResponse(
        UUID id,
        String name,
        BusinessType businessType,
        String address,
        String city,
        String phone,
        String openingHours,
        MerchantStatus status,
        String decisionReason,
        Instant createdAt) {

    public static MerchantResponse from(Merchant m) {
        return new MerchantResponse(m.id(), m.name(), m.businessType(), m.address(), m.city(), m.phone(),
                m.openingHours(), m.status(), m.decisionReason(), m.createdAt());
    }
}
