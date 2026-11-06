package ma.kulha.merchant.application;

import java.util.Optional;
import java.util.UUID;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.merchant.domain.repository.MerchantRepository;
import org.springframework.stereotype.Service;

@Service
class MerchantApiService implements MerchantApi {

    private final MerchantRepository merchants;

    MerchantApiService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Override
    public boolean isApproved(UUID merchantId) {
        return merchants.findById(merchantId).map(Merchant::isApproved).orElse(false);
    }

    @Override
    public Optional<UUID> findMerchantIdByOwner(UUID ownerUserId) {
        return merchants.findByOwner(ownerUserId).map(Merchant::id);
    }

    @Override
    public Optional<UUID> resolveActingMerchant(UUID userId) {
        return merchants.findByOwner(userId).map(Merchant::id);
    }

    @Override
    public void lockForOfferMutation(UUID merchantId) {
        merchants.lockForOfferMutation(merchantId);
    }
}
