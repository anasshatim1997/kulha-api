package ma.kulha.merchant.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.merchant.domain.repository.MerchantRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class MerchantApiService implements MerchantApi {
    private final MerchantRepository merchants;
    private final JdbcClient jdbc;

    MerchantApiService(MerchantRepository merchants, JdbcClient jdbc) {
        this.merchants = merchants;
        this.jdbc = jdbc;
    }

    @Override public boolean isApproved(UUID merchantId) {
        return merchants.findById(merchantId).map(Merchant::isApproved).orElse(false);
    }
    @Override public Optional<UUID> findMerchantIdByOwner(UUID ownerUserId) {
        return merchants.findByOwner(ownerUserId).map(Merchant::id);
    }
    @Override public Optional<UUID> resolveActingMerchant(UUID userId) {
        return merchants.findByOwner(userId).or(() -> merchants.findByMember(userId)).map(Merchant::id);
    }
    @Override public boolean can(UUID userId, UUID merchantId, String permission) {
        if (merchants.findByOwner(userId).map(Merchant::id).filter(merchantId::equals).isPresent()) return true;
        String column = switch (permission) {
            case "VALIDATE_PICKUPS" -> "can_validate_pickups";
            case "VIEW_ORDERS" -> "can_view_orders";
            case "MANAGE_OFFERS" -> "can_manage_offers";
            default -> null;
        };
        if (column == null) return false;
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT %s FROM merchant.merchant_memberships
                WHERE merchant_id = :merchant AND user_id = :user AND status = 'ACTIVE'
                """.formatted(column))
                .param("merchant", merchantId).param("user", userId)
                .query(Boolean.class).optional().orElse(false));
    }
    @Override public void lockForOfferMutation(UUID merchantId) { merchants.lockForOfferMutation(merchantId); }
    @Override public List<UUID> findApprovedMerchantIds(String city, String district) {
        return merchants.findApprovedIds(city, district);
    }
    @Override public List<UUID> findApprovedMerchantIdsByName(String query, String city, String district) {
        return merchants.findApprovedIdsByName(query, city, district);
    }
    @Override public List<MerchantPublicProfile> findApprovedPublicProfiles(Collection<UUID> merchantIds) {
        return merchants.findApprovedByIds(merchantIds).stream()
                .map(m -> new MerchantPublicProfile(m.id(), m.name(), m.address(), m.city(), m.district(),
                        m.pickupInstructions(), m.latitude(), m.longitude())).toList();
    }

    @Override public List<SupportMerchantProfile> findSupportProfiles(Collection<UUID> merchantIds) {
        if (merchantIds == null || merchantIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT id, owner_user_id, name, status
                FROM merchant.merchants
                WHERE id IN (:ids)
                """)
                .param("ids", merchantIds)
                .query((rs, row) -> new SupportMerchantProfile(rs.getObject("id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class), rs.getString("name"), rs.getString("status")))
                .list();
    }
}
