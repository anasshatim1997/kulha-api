package ma.kulha.identity.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.UserRepository;
import ma.kulha.shared.identity.UserAccountDirectory;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;

@Component
public class IdentityUserAccountDirectory implements UserAccountDirectory {
    private final UserRepository users;
    private final JdbcClient jdbc;

    public IdentityUserAccountDirectory(UserRepository users, JdbcClient jdbc) {
        this.users = users;
        this.jdbc = jdbc;
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
    public List<Account> findAccountsByIds(Collection<UUID> userIds) {
        List<UUID> ids = userIds.stream().distinct().toList();
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT u.id, u.email, u.full_name, u.status, u.email_verified_at,
                       COALESCE(bool_or(r.role IN ('MERCHANT', 'MERCHANT_OWNER')), FALSE) AS merchant_owner,
                       COALESCE(bool_or(r.role IN ('ADMIN', 'SUPER_ADMIN')), FALSE) AS administrator
                FROM identity.users u
                LEFT JOIN identity.user_roles r ON r.user_id = u.id
                WHERE u.id IN (:ids)
                GROUP BY u.id, u.email, u.full_name, u.status, u.email_verified_at
                """)
                .param("ids", ids)
                .query((rs, row) -> new Account(
                        rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"),
                        "ACTIVE".equals(rs.getString("status")), rs.getObject("email_verified_at") != null,
                        rs.getBoolean("merchant_owner"), rs.getBoolean("administrator")))
                .list();
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
