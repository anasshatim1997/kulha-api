package ma.kulha.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class JdbcUserRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        // roles and tokens are removed by ON DELETE CASCADE
        jdbc.sql("DELETE FROM identity.users").update();
    }

    private static User newUser(String email) {
        return User.register(email, "not-a-real-hash", "Test User", null, Instant.now());
    }

    @Test
    void insertedUserCanBeFoundByEmailWithItsRoles() {
        User user = newUser("Alice@Example.com"); // normalized to lower case

        users.insert(user);

        Optional<User> found = users.findByEmail("alice@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(user.id());
        assertThat(found.get().fullName()).isEqualTo("Test User");
        assertThat(found.get().status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(found.get().roles()).containsExactly(Role.CUSTOMER);
        assertThat(found.get().isEmailVerified()).isFalse();
        assertThat(found.get().phone()).isNull();
    }

    @Test
    void insertedUserCanBeFoundById() {
        User user = newUser("bob@example.com");
        users.insert(user);

        assertThat(users.findById(user.id())).isPresent();
        assertThat(users.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void unknownEmailReturnsEmpty() {
        assertThat(users.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void duplicateEmailIsRejected() {
        users.insert(newUser("dup@example.com"));

        assertThatThrownBy(() -> users.insert(newUser("dup@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailVerificationTimeIsSetOnlyOnce() {
        User user = newUser("verify@example.com");
        users.insert(user);
        Instant first = Instant.parse("2026-10-29T10:00:00Z");

        users.markEmailVerified(user.id(), first);
        users.markEmailVerified(user.id(), Instant.parse("2026-10-30T10:00:00Z"));

        User reloaded = users.findById(user.id()).orElseThrow();
        assertThat(reloaded.isEmailVerified()).isTrue();
        assertThat(reloaded.emailVerifiedAt()).isEqualTo(first);
    }

    @Test
    void addingTheSameRoleTwiceIsHarmless() {
        User user = newUser("roles@example.com");
        users.insert(user);

        users.addRole(user.id(), Role.MERCHANT);
        users.addRole(user.id(), Role.MERCHANT);

        assertThat(users.findById(user.id()).orElseThrow().roles())
                .containsExactlyInAnyOrder(Role.CUSTOMER, Role.MERCHANT);
    }

    @Test
    void passwordHashCanBeUpdated() {
        User user = newUser("pwd@example.com");
        users.insert(user);

        users.updatePasswordHash(user.id(), "another-hash");

        assertThat(users.findById(user.id()).orElseThrow().passwordHash()).isEqualTo("another-hash");
    }
}
