package ma.kulha.merchant.interfaces.dto;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.merchant.application.AdminMerchantView;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.merchant.domain.model.MerchantStatus;

public record AdminMerchantResponse(
        UUID id,
        UUID ownerUserId,
        String ownerEmail,
        String name,
        BusinessType businessType,
        String address,
        String city,
        String phone,
        String openingHours,
        MerchantStatus status,
        String decisionReason,
        UUID decidedBy,
        Instant decidedAt,
        Instant createdAt) {

    public static AdminMerchantResponse from(AdminMerchantView view) {
        Merchant m = view.merchant();
        return new AdminMerchantResponse(m.id(), m.ownerUserId(), view.ownerEmail(), m.name(), m.businessType(),
                m.address(), m.city(), m.phone(), m.openingHours(), m.status(), m.decisionReason(),
                m.decidedBy(), m.decidedAt(), m.createdAt());
    }
}
