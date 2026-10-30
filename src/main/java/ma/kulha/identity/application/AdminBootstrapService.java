package ma.kulha.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates the first administrator when none exists. Used at startup from environment variables. */
@Service
public class AdminBootstrapService {

    public enum Result {
        NOT_CONFIGURED, ADMIN_EXISTS, INVALID_EMAIL, WEAK_PASSWORD, CREATED, PROMOTED
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Clock clock;

    AdminBootstrapService(UserRepository users, PasswordEncoder passwordEncoder,
                          TransactionTemplate tx, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.clock = clock;
    }

    public Result ensureAdmin(String rawEmail, String password) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return Result.NOT_CONFIGURED;
        }
        if (users.existsByRole(Role.ADMIN)) {
            return Result.ADMIN_EXISTS;
        }
        String email = User.normalizeEmail(rawEmail);
        if (!email.contains("@") || email.length() > 254) {
            return Result.INVALID_EMAIL;
        }
        if (password == null || password.length() < 10 || password.length() > 128) {
            return Result.WEAK_PASSWORD;
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            Optional<User> existing = users.findByEmail(email);
            if (existing.isPresent()) {
                users.addRole(existing.get().id(), Role.ADMIN);
                users.markEmailVerified(existing.get().id(), now);
                return Result.PROMOTED;
            }
            User admin = new User(UUID.randomUUID(), email, passwordEncoder.encode(password),
                    "Administrator", null, UserStatus.ACTIVE, now, Set.of(Role.ADMIN), now);
            users.insert(admin);
            return Result.CREATED;
        });
    }
}
