package ma.kulha.shared.identity;

import java.util.Optional;
import java.util.UUID;

/** Narrow cross-module port for assigning tenant-scoped merchant pickup staff. */
public interface UserAccountDirectory {
    Optional<Account> findAccountByEmail(String email);
    void grantPickupStaff(UUID userId);
    void revokePickupStaff(UUID userId);

    record Account(UUID id, String email, String fullName, boolean active,
                   boolean emailVerified, boolean merchantOwner, boolean administrator) {}
}
