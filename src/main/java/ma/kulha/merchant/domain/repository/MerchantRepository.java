package ma.kulha.merchant.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.merchant.domain.model.MerchantStatus;

public interface MerchantRepository {

    /** Fails if the owner already has a merchant. */
    void insert(Merchant merchant);

    Optional<Merchant> findById(UUID id);

    Optional<Merchant> findByOwner(UUID ownerUserId);
    void lockForOfferMutation(UUID merchantId);

    /**
     * Saves the merchant only if it still has the expected status, in one atomic statement.
     * Returns false if someone else changed it first.
     */
    boolean updateIfStatus(Merchant merchant, MerchantStatus expectedStatus);

    /** Oldest first. A null status means all statuses. */
    List<Merchant> list(MerchantStatus statusOrNull, int limit, int offset);

    long count(MerchantStatus statusOrNull);
}
