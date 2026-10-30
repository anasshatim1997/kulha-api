package ma.kulha.identity.domain.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A user account. Immutable. Contains no framework code.
 * Emails are always stored trimmed and in lower case.
 */
public record User(
        UUID id,
        String email,
        String passwordHash,
        String fullName,
        String phone,
        UserStatus status,
        Instant emailVerifiedAt,
        Set<Role> roles,
        Instant createdAt) {

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!email.equals(normalizeEmail(email))) {
            throw new IllegalArgumentException("email must be normalized");
        }
        if (email.length() > 254 || !email.contains("@")) {
            throw new IllegalArgumentException("invalid email");
        }
        if (fullName.isBlank() || fullName.length() > 120) {
            throw new IllegalArgumentException("invalid full name");
        }
        roles = Set.copyOf(roles);
    }

    /** Creates a new, unverified customer account. */
    public static User register(String email, String passwordHash, String fullName, String phone, Instant now) {
        String cleanPhone = (phone == null || phone.isBlank()) ? null : phone.trim();
        return new User(
                UUID.randomUUID(),
                normalizeEmail(email),
                passwordHash,
                fullName.trim(),
                cleanPhone,
                UserStatus.ACTIVE,
                null,
                Set.of(Role.CUSTOMER),
                now);
    }

    public static String normalizeEmail(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
