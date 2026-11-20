package ma.kulha.merchant.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.UserRepository;
import ma.kulha.merchant.application.MerchantApi;
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
    private final UserRepository users;

    MerchantStaffService(JdbcClient jdbc, MerchantApi merchants, UserRepository users) {
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<StaffMember> list(UUID ownerUserId) {
        UUID merchantId = ownerMerchant(ownerUserId);
        return jdbc.sql("""
                SELECT membership.user_id, users.email, users.full_name,
                       membership.membership_role, membership.created_at
                FROM merchant.merchant_memberships membership
                JOIN identity.users users ON users.id = membership.user_id
                WHERE membership.merchant_id = :merchantId AND membership.status = 'ACTIVE'
                ORDER BY membership.created_at, membership.user_id
                """)
                .param("merchantId", merchantId)
                .query((rs, row) -> new StaffMember(
                        rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("full_name"),
                        rs.getString("membership_role"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
    }

    @Transactional
    public StaffMember addPickupStaff(UUID ownerUserId, String email) {
        UUID merchantId = ownerMerchant(ownerUserId);
        User staff = users.findByEmail(User.normalizeEmail(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "KULHA account not found."));
        if (staff.id().equals(ownerUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The merchant owner is already a member.");
        }
        if (staff.status() != UserStatus.ACTIVE || !staff.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Staff accounts must be active and email-verified.");
        }
        try {
            jdbc.sql("""
                    INSERT INTO merchant.merchant_memberships
                        (id, merchant_id, user_id, membership_role, status, invited_by)
                    VALUES (:id, :merchantId, :userId, 'PICKUP_STAFF', 'ACTIVE', :ownerId)
                    ON CONFLICT (merchant_id, user_id) DO UPDATE
                    SET membership_role = 'PICKUP_STAFF', status = 'ACTIVE', updated_at = now()
                    """)
                    .param("id", UUID.randomUUID())
                    .param("merchantId", merchantId)
                    .param("userId", staff.id())
                    .param("ownerId", ownerUserId)
                    .update();
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This account already has an active merchant staff assignment.", conflict);
        }
        users.addRole(staff.id(), Role.MERCHANT_STAFF);
        return new StaffMember(staff.id(), staff.email(), staff.fullName(), "PICKUP_STAFF", Instant.now());
    }

    @Transactional
    public void remove(UUID ownerUserId, UUID staffUserId) {
        UUID merchantId = ownerMerchant(ownerUserId);
        int removed = jdbc.sql("""
                UPDATE merchant.merchant_memberships
                SET status = 'REVOKED', updated_at = now()
                WHERE merchant_id = :merchantId AND user_id = :userId AND status = 'ACTIVE'
                """)
                .param("merchantId", merchantId)
                .param("userId", staffUserId)
                .update();
        if (removed == 0) return;
        boolean hasAnotherActiveMembership = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM merchant.merchant_memberships
                    WHERE user_id = :userId AND status = 'ACTIVE'
                )
                """)
                .param("userId", staffUserId)
                .query(Boolean.class).single();
        if (!hasAnotherActiveMembership) users.removeRole(staffUserId, Role.MERCHANT_STAFF);
    }

    private UUID ownerMerchant(UUID ownerUserId) {
        UUID merchantId = merchants.findMerchantIdByOwner(ownerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant owner access required."));
        if (!merchants.isApproved(merchantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only approved merchants can manage staff.");
        }
        return merchantId;
    }

    public record StaffMember(UUID userId, String email, String fullName, String role, Instant addedAt) {}
}
