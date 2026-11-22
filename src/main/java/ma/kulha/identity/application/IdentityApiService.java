package ma.kulha.identity.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    public Map<UUID, String> findEmailsForNotifications(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        return jdbc.sql("""
                SELECT id, email
                FROM identity.users
                WHERE id IN (:ids) AND status = 'ACTIVE'
                """).param("ids", userIds)
                .query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("email")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Map<UUID, String> findLocales(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        return jdbc.sql("""
                SELECT user_id, locale
                FROM identity.user_preferences
                WHERE user_id IN (:ids)
                """).param("ids", userIds)
                .query((rs, row) -> Map.entry(rs.getObject("user_id", UUID.class), rs.getString("locale")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public UserPreferences getUserPreferences(UUID userId, String defaultLocale) {
        jdbc.sql("""
                INSERT INTO identity.user_preferences (user_id, locale)
                VALUES (:userId, :locale) ON CONFLICT (user_id) DO NOTHING
                """).param("userId", userId).param("locale", defaultLocale).update();
        return jdbc.sql("""
                SELECT preferred_city, preferred_district, locale
                FROM identity.user_preferences WHERE user_id = :userId
                """).param("userId", userId)
                .query((rs, row) -> new UserPreferences(rs.getString("preferred_city"),
                        rs.getString("preferred_district"), rs.getString("locale")))
                .single();
    }

    @Override
    public UserPreferences updateUserPreferences(UUID userId, UserPreferences preferences) {
        jdbc.sql("""
                INSERT INTO identity.user_preferences
                    (user_id, preferred_city, preferred_district, locale, updated_at)
                VALUES (:userId, :city, :district, :locale, now())
                ON CONFLICT (user_id) DO UPDATE SET
                    preferred_city = EXCLUDED.preferred_city,
                    preferred_district = EXCLUDED.preferred_district,
                    locale = EXCLUDED.locale,
                    updated_at = now()
                """).param("userId", userId).param("city", preferences.preferredCity())
                .param("district", preferences.preferredDistrict()).param("locale", preferences.locale()).update();
        return preferences;
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
