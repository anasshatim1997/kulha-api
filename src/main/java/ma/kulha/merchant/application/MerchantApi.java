package ma.kulha.merchant.application;

import java.util.Optional;
import java.util.UUID;

/** Public entry point of the merchant module. Other modules (offers, later) call only this. */
public interface MerchantApi {

    boolean isApproved(UUID merchantId);

    Optional<UUID> findMerchantIdByOwner(UUID ownerUserId);

    Optional<UUID> resolveActingMerchant(UUID userId);

    void lockForOfferMutation(UUID merchantId);
}
