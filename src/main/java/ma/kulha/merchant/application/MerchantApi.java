package ma.kulha.merchant.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public entry point of the merchant module. Other modules call only this. */
public interface MerchantApi extends MerchantPublicApi {
    boolean isApproved(UUID merchantId);
    Optional<UUID> findMerchantIdByOwner(UUID ownerUserId);
    Optional<UUID> resolveActingMerchant(UUID userId);
    boolean can(UUID userId, UUID merchantId, String permission);
    void lockForOfferMutation(UUID merchantId);

    /** Batch-resolves historical merchant references, including non-approved merchants. */
    List<SupportMerchantProfile> findSupportProfiles(Collection<UUID> merchantIds);

    record SupportMerchantProfile(UUID id, UUID ownerUserId, String name, String city, String status) {}
}
