package ma.kulha.shared.identity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ma.kulha.identity.domain.model.Role;
import java.util.UUID;

/** Narrow cross-module port for assigning tenant-scoped merchant pickup staff. */
public interface UserAccountDirectory {
    Optional<Account> findAccountByEmail(String email);
    Optional<Account> findAccountById(UUID userId);

    /** True only for active users authorized to handle support cases. */
    boolean isEligibleSupportCaseAssignee(UUID userId);

    /** Active-account roles, resolved in one identity lookup. */
    Set<Role> findActiveRoles(UUID userId);

    /** Batch lookup used by tenant-scoped staff lists to avoid one identity query per member. */
    default List<Account> findAccountsByIds(Collection<UUID> userIds) {
        return userIds.stream().distinct().map(this::findAccountById)
                .flatMap(Optional::stream).toList();
    }
    void grantPickupStaff(UUID userId);
    void revokePickupStaff(UUID userId);

    record Account(UUID id, String email, String fullName, boolean active,
                   boolean emailVerified, boolean merchantOwner, boolean administrator) {}
}
