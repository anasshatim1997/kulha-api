package ma.kulha.shared.identity;

import java.util.Optional;
import ma.kulha.identity.domain.model.Role;
import java.util.UUID;

/** Narrow cross-module port for assigning tenant-scoped merchant pickup staff. */
public interface UserAccountDirectory {
    Optional<Account> findAccountByEmail(String email);
    Optional<Account> findAccountById(UUID userId);

    /** True only for active users authorized to handle support cases. */
    boolean isEligibleSupportCaseAssignee(UUID userId);

    /** Active-account role lookup owned by the identity module. */
    boolean hasActiveRole(UUID userId, Role role);
    void grantPickupStaff(UUID userId);
    void revokePickupStaff(UUID userId);

    record Account(UUID id, String email, String fullName, boolean active,
                   boolean emailVerified, boolean merchantOwner, boolean administrator) {}
}
