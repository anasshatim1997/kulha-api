package ma.kulha.merchant.interfaces.dto;

public record UpdateMerchantStaffRequest(
        boolean canValidatePickups,
        boolean canViewOrders,
        boolean canManageOffers) {
}
