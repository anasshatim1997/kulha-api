package ma.kulha.identity.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.UserRepository;
import ma.kulha.shared.identity.UserAccountDirectory;
import org.springframework.stereotype.Component;

@Component
public class IdentityUserAccountDirectory implements UserAccountDirectory {
    private final UserRepository users;

    public IdentityUserAccountDirectory(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<Account> findAccountByEmail(String email) {
        return users.findByEmail(User.normalizeEmail(email)).map(this::toAccount);
    }

    @Override
    public Optional<Account> findAccountById(UUID userId) {
        return users.findById(userId).map(this::toAccount);
    }

    @Override
    public boolean isEligibleSupportCaseAssignee(UUID userId) {
        return users.findById(userId)
                .filter(user -> user.status() == UserStatus.ACTIVE)
                .filter(user -> user.hasRole(Role.SUPPORT) || user.hasRole(Role.ADMIN) || user.hasRole(Role.SUPER_ADMIN))
                .isPresent();
    }

    @Override
    public Set<Role> findActiveRoles(UUID userId) {
        return users.findById(userId)
                .filter(user -> user.status() == UserStatus.ACTIVE)
                .map(User::roles)
                .orElseGet(Set::of);
    }

    private Account toAccount(User user) {
        return new Account(user.id(), user.email(), user.fullName(),
                user.status() == UserStatus.ACTIVE, user.isEmailVerified(),
                user.hasRole(Role.MERCHANT) || user.hasRole(Role.MERCHANT_OWNER),
                user.hasRole(Role.ADMIN) || user.hasRole(Role.SUPER_ADMIN));
    }

    @Override
    public void grantPickupStaff(UUID userId) {
        users.addRole(userId, Role.MERCHANT_STAFF);
    }

    @Override
    public void revokePickupStaff(UUID userId) {
        users.removeRole(userId, Role.MERCHANT_STAFF);
    }
}
