package ma.kulha.identity.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class IdentityApiService implements IdentityApi {
    private final UserRepository users;
    private final JdbcClient jdbc;

    IdentityApiService(UserRepository users, JdbcClient jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    @Override public boolean isEmailVerified(UUID userId) {
        return users.findById(userId).map(User::isEmailVerified).orElse(false);
    }
    @Override public Optional<String> emailOf(UUID userId) {
        return users.findById(userId).map(User::email);
    }
    @Override public void grantMerchantRole(UUID userId) {
        users.addRole(userId, Role.MERCHANT);
        users.addRole(userId, Role.MERCHANT_OWNER);
    }
    @Override public void revokeMerchantRole(UUID userId) {
        users.removeRole(userId, Role.MERCHANT);
        users.removeRole(userId, Role.MERCHANT_OWNER);
    }

    @Override
    public List<SupportUserSummary> findSupportSummaries(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT id, full_name, status
                FROM identity.users
                WHERE id IN (:ids)
                """)
                .param("ids", userIds)
                .query((rs, row) -> new SupportUserSummary(rs.getObject("id", UUID.class),
                        rs.getString("full_name"), "ACTIVE".equals(rs.getString("status"))))
                .list();
    }

    @Override
    public Optional<SupportUserSummary> findSupportSummary(UUID userId) {
        return findSupportSummaries(List.of(userId)).stream().findFirst();
    }

    @Override
    public Optional<SupportUserDetails> findSupportDetails(UUID userId) {
        return jdbc.sql("""
                SELECT id, full_name, email, phone, status
                FROM identity.users
                WHERE id=:id
                """).param("id", userId)
                .query((rs, row) -> new SupportUserDetails(rs.getObject("id", UUID.class),
                        rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
                        "ACTIVE".equals(rs.getString("status"))))
                .optional();
    }

    @Override
    public Set<String> findActiveRoleNames(UUID userId) {
        return Set.copyOf(jdbc.sql("""
                SELECT r.role FROM identity.user_roles r
                JOIN identity.users u ON u.id=r.user_id
                WHERE r.user_id=:id AND u.status='ACTIVE'
                """).param("id", userId).query(String.class).list());
    }
}
