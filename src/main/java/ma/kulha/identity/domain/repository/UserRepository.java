package ma.kulha.identity.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;

public interface UserRepository {

    /** Inserts a new user with its roles. Fails if the email already exists. */
    void insert(User user);

    Optional<User> findById(UUID id);

    /** The email must already be normalized (trimmed, lower case). */
    Optional<User> findByEmail(String normalizedEmail);

    /** Sets the verification time only if it was not set before. */
    void markEmailVerified(UUID userId, Instant at);

    void updatePasswordHash(UUID userId, String passwordHash);

    /** Adds a role. Does nothing if the user already has it. */
    void addRole(UUID userId, Role role);

    /** Removes a role. Does nothing if the user does not have it. */
    void removeRole(UUID userId, Role role);

    boolean existsByRole(Role role);
}
