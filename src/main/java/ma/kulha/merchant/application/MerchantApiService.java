package ma.kulha.merchant.application;

import java.util.Collection;
import java.util.List;
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

    @Override
    public List<UUID> findApprovedMerchantIds(String city, String district) {
        return merchants.findApprovedIds(city, district);
    }

    @Override
    public List<UUID> findApprovedMerchantIdsByName(String query, String city, String district) {
        return merchants.findApprovedIdsByName(query, city, district);
    }

    @Override
    public List<MerchantPublicProfile> findApprovedPublicProfiles(Collection<UUID> merchantIds) {
        return merchants.findApprovedByIds(merchantIds).stream()
                .map(m -> new MerchantPublicProfile(m.id(), m.name(), m.address(), m.city(), m.district(),
                        m.pickupInstructions(), m.latitude(), m.longitude()))
                .toList();
    }
}
