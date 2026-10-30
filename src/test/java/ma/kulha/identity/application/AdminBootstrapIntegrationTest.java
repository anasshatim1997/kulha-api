package ma.kulha.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import ma.kulha.identity.application.AdminBootstrapService.Result;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AdminBootstrapIntegrationTest {

    private static final String PASSWORD = "a-strong-admin-password";

    @Autowired
    AdminBootstrapService bootstrap;

    @Autowired
    AuthenticationService authentication;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.sql("DELETE FROM identity.users").update();
    }

    @Test
    void createsAVerifiedAdminWhoCanLogIn() {
        assertThat(bootstrap.ensureAdmin("Admin@Example.com", PASSWORD)).isEqualTo(Result.CREATED);

        User admin = users.findByEmail("admin@example.com").orElseThrow();
        assertThat(admin.roles()).containsExactly(Role.ADMIN);
        assertThat(admin.isEmailVerified()).isTrue();
        assertThat(admin.passwordHash()).startsWith("$argon2id$").doesNotContain(PASSWORD);
        assertThat(authentication.login("admin@example.com", PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        bootstrap.ensureAdmin("admin@example.com", PASSWORD);

        assertThat(bootstrap.ensureAdmin("second@example.com", PASSWORD)).isEqualTo(Result.ADMIN_EXISTS);

        assertThat(users.findByEmail("second@example.com")).isEmpty();
    }

    @Test
    void promotesAnExistingAccountWithThatEmailWithoutChangingItsPassword() {
        User customer = User.register("owner@example.com", passwordEncoder.encode("the-customer-password"),
                "Owner", null, Instant.now());
        users.insert(customer);

        assertThat(bootstrap.ensureAdmin("owner@example.com", PASSWORD)).isEqualTo(Result.PROMOTED);

        User promoted = users.findByEmail("owner@example.com").orElseThrow();
        assertThat(promoted.roles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.ADMIN);
        assertThat(promoted.isEmailVerified()).isTrue();
        assertThat(promoted.passwordHash()).isEqualTo(customer.passwordHash());
    }

    @Test
    void rejectsAWeakPasswordABadEmailAndAMissingConfiguration() {
        assertThat(bootstrap.ensureAdmin("admin@example.com", "short")).isEqualTo(Result.WEAK_PASSWORD);
        assertThat(bootstrap.ensureAdmin("not-an-email", PASSWORD)).isEqualTo(Result.INVALID_EMAIL);
        assertThat(bootstrap.ensureAdmin("", PASSWORD)).isEqualTo(Result.NOT_CONFIGURED);

        assertThat(users.existsByRole(Role.ADMIN)).isFalse();
    }
}
