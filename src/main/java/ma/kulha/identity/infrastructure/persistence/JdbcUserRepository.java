package ma.kulha.identity.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcUserRepository implements UserRepository {

    private static final String SELECT_USER = """
            SELECT id, email, password_hash, full_name, phone, status, email_verified_at, created_at
            FROM identity.users
            """;

    private final JdbcClient jdbc;

    JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void insert(User user) {
        jdbc.sql("""
                INSERT INTO identity.users
                    (id, email, password_hash, full_name, phone, status, email_verified_at, created_at, updated_at)
                VALUES
                    (:id, :email, :passwordHash, :fullName, :phone, :status, :emailVerifiedAt, :createdAt, :createdAt)
                """)
                .param("id", user.id())
                .param("email", user.email())
                .param("passwordHash", user.passwordHash())
                .param("fullName", user.fullName())
                .param("phone", user.phone(), Types.VARCHAR)
                .param("status", user.status().name())
                .param("emailVerifiedAt", toDb(user.emailVerifiedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", toDb(user.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        for (Role role : user.roles()) {
            addRole(user.id(), role);
        }
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jdbc.sql(SELECT_USER + " WHERE id = :id")
                .param("id", id)
                .query(JdbcUserRepository::mapRow)
                .optional()
                .map(this::withRoles);
    }

    @Override
    public Optional<User> findByEmail(String normalizedEmail) {
        return jdbc.sql(SELECT_USER + " WHERE email = :email")
                .param("email", normalizedEmail)
                .query(JdbcUserRepository::mapRow)
                .optional()
                .map(this::withRoles);
    }

    @Override
    public void markEmailVerified(UUID userId, Instant at) {
        jdbc.sql("""
                UPDATE identity.users
                SET email_verified_at = COALESCE(email_verified_at, :at), updated_at = :at
                WHERE id = :id
                """)
                .param("at", toDb(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", userId)
                .update();
    }

    @Override
    public void updatePasswordHash(UUID userId, String passwordHash) {
        jdbc.sql("UPDATE identity.users SET password_hash = :hash, updated_at = now() WHERE id = :id")
                .param("hash", passwordHash)
                .param("id", userId)
                .update();
    }

    @Override
    public void addRole(UUID userId, Role role) {
        jdbc.sql("INSERT INTO identity.user_roles (user_id, role) VALUES (:id, :role) ON CONFLICT DO NOTHING")
                .param("id", userId)
                .param("role", role.name())
                .update();
    }

    private User withRoles(Row row) {
        List<Role> roles = jdbc.sql("SELECT role FROM identity.user_roles WHERE user_id = :id")
                .param("id", row.id())
                .query((rs, n) -> Role.valueOf(rs.getString("role")))
                .list();
        return new User(
                row.id(), row.email(), row.passwordHash(), row.fullName(), row.phone(),
                row.status(), row.emailVerifiedAt(), Set.copyOf(roles), row.createdAt());
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime verified = rs.getObject("email_verified_at", OffsetDateTime.class);
        OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("full_name"),
                rs.getString("phone"),
                UserStatus.valueOf(rs.getString("status")),
                verified == null ? null : verified.toInstant(),
                created.toInstant());
    }

    /** PostgreSQL's JDBC driver does not bind java.time.Instant, so convert to OffsetDateTime. */
    private static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private record Row(
            UUID id, String email, String passwordHash, String fullName, String phone,
            UserStatus status, Instant emailVerifiedAt, Instant createdAt) {
    }
}
