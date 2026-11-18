package ma.kulha.offer.interfaces.dto;

import ma.kulha.merchant.application.MerchantPublicProfile;

public record PublicMerchantResponse(String name, String address, String city, String district,
                                     String pickupInstructions, Double latitude, Double longitude) {
    public static PublicMerchantResponse from(MerchantPublicProfile merchant) {
        return new PublicMerchantResponse(merchant.name(), merchant.address(), merchant.city(),
                merchant.district(), merchant.pickupInstructions(), merchant.latitude(), merchant.longitude());
    }
}
