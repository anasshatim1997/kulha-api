package ma.kulha.merchant.application;

import java.util.UUID;

public record MerchantPublicProfile(UUID merchantId, String name, String address, String city,
                                    String district, String pickupInstructions, Double latitude, Double longitude) {
    public MerchantPublicProfile(UUID merchantId, String name, String address, String city,
                                 String district, String pickupInstructions) {
        this(merchantId, name, address, city, district, pickupInstructions, null, null);
    }
}
