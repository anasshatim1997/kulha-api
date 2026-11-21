package ma.kulha.merchant.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.kulha.shared.identity.UserAccountDirectory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantStaffService {
    private final JdbcClient jdbc;
    private final MerchantApi merchants;
    private final UserAccountDirectory users;

    MerchantStaffService(JdbcClient jdbc, MerchantApi merchants, UserAccountDirectory users) {
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<StaffMember> list(UUID ownerUserId) {
        UUID merchantId = ownerMerchant(ownerUserId);
        List<MembershipRow> memberships = jdbc.sql("""
                SELECT user_id, membership_role, created_at, can_validate_pickups, can_view_orders, can_manage_offers
                FROM merchant.merchant_memberships
                WHERE merchant_id = :merchantId AND status = 'ACTIVE'
                ORDER BY created_at, user_id
                """).param("merchantId", merchantId)
                .query((rs, row) -> new MembershipRow(rs.getObject("user_id", UUID.class),
                        rs.getString("membership_role"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getBoolean("can_validate_pickups"), rs.getBoolean("can_view_orders"),
                        rs.getBoolean("can_manage_offers"))).list();

        List<StaffMember> team = new java.util.ArrayList<>();
        users.findAccountById(ownerUserId).ifPresent(owner -> team.add(new StaffMember(
                owner.id(), owner.email(), owner.fullName(), "OWNER", Instant.EPOCH, true, true, true)));
        memberships.stream()
                .flatMap(membership -> users.findAccountById(membership.userId()).stream()
                        .map(account -> new StaffMember(account.id(), account.email(), account.fullName(),
                                membership.role(), membership.addedAt(), membership.canValidatePickups(),
                                membership.canViewOrders(), membership.canManageOffers())))
                .forEach(team::add);
        return List.copyOf(team);
    }

    @Transactional
    public StaffMember addPickupStaff(UUID ownerUserId, String email, Boolean canValidatePickups,
                                      Boolean canViewOrders, Boolean canManageOffers) {
        UUID merchantId = ownerMerchant(ownerUserId);
        UserAccountDirectory.Account staff = users.findAccountByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "KULHA account not found."));
        if (staff.id().equals(ownerUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The merchant owner is already a member.");
        }
        if (!staff.active() || !staff.emailVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Staff accounts must be active and email-verified.");
        }
        if (staff.merchantOwner() || staff.administrator()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Owner and administrator accounts cannot be assigned as pickup staff.");
        }
        boolean validate = canValidatePickups == null || canValidatePickups;
        boolean view = canViewOrders == null || canViewOrders;
        boolean offers = Boolean.TRUE.equals(canManageOffers);
        try {
            jdbc.sql("""
                    INSERT INTO merchant.merchant_memberships
                        (id, merchant_id, user_id, membership_role, status, invited_by,
                         can_validate_pickups, can_view_orders, can_manage_offers)
                    VALUES (:id, :merchantId, :userId, 'PICKUP_STAFF', 'ACTIVE', :ownerId,
                            :validate, :view, :offers)
                    ON CONFLICT (merchant_id, user_id) DO UPDATE
                    SET membership_role = 'PICKUP_STAFF', status = 'ACTIVE', updated_at = now(),
                        can_validate_pickups = :validate, can_view_orders = :view, can_manage_offers = :offers
                    """)
                    .param("id", UUID.randomUUID()).param("merchantId", merchantId)
                    .param("userId", staff.id()).param("ownerId", ownerUserId)
                    .param("validate", validate).param("view", view).param("offers", offers).update();
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This account already has an active merchant staff assignment.", conflict);
        }
        users.grantPickupStaff(staff.id());
        return new StaffMember(staff.id(), staff.email(), staff.fullName(), "PICKUP_STAFF", Instant.now(),
                validate, view, offers);
    }

    @Transactional
    public StaffMember updatePermissions(UUID ownerUserId, UUID staffUserId, boolean canValidatePickups,
                                         boolean canViewOrders, boolean canManageOffers) {
        UUID merchantId = ownerMerchant(ownerUserId);
        int updated = jdbc.sql("""
                UPDATE merchant.merchant_memberships
                SET can_validate_pickups=:validate, can_view_orders=:view,
                    can_manage_offers=:offers, updated_at=now()
                WHERE merchant_id=:merchant AND user_id=:user AND status='ACTIVE'
                """).param("validate", canValidatePickups).param("view", canViewOrders)
                .param("offers", canManageOffers).param("merchant", merchantId)
                .param("user", staffUserId).update();
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active staff member not found.");
        return list(ownerUserId).stream().filter(member -> member.userId().equals(staffUserId))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public void remove(UUID ownerUserId, UUID staffUserId) {
        UUID merchantId = ownerMerchant(ownerUserId);
        int removed = jdbc.sql("""
                UPDATE merchant.merchant_memberships
                SET status = 'REVOKED', updated_at = now()
                WHERE merchant_id = :merchantId AND user_id = :userId AND status = 'ACTIVE'
                """).param("merchantId", merchantId).param("userId", staffUserId).update();
        if (removed == 0) return;
        boolean hasAnotherActiveMembership = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM merchant.merchant_memberships
                               WHERE user_id = :userId AND status = 'ACTIVE')
                """).param("userId", staffUserId).query(Boolean.class).single();
        if (!hasAnotherActiveMembership) users.revokePickupStaff(staffUserId);
    }

    private UUID ownerMerchant(UUID ownerUserId) {
        UUID merchantId = merchants.findMerchantIdByOwner(ownerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant owner access required."));
        if (!merchants.isApproved(merchantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only approved merchants can manage staff.");
        }
        return merchantId;
    }

    private record MembershipRow(UUID userId, String role, Instant addedAt, boolean canValidatePickups,
                                 boolean canViewOrders, boolean canManageOffers) {}
    public record StaffMember(UUID userId, String email, String fullName, String role, Instant addedAt,
                              boolean canValidatePickups, boolean canViewOrders, boolean canManageOffers) {}
}
