package ma.kulha.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import ma.kulha.identity.application.OpaqueTokens;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import ma.kulha.notification.application.TemplateKey;
import ma.kulha.support.RecordingNotificationApi;
import ma.kulha.support.RecordingNotificationApi.SentEmail;
import ma.kulha.support.TestNotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestNotificationConfig.class)
class AuthRegistrationIntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RecordingNotificationApi emails;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM identity.users").update(); // tokens and roles go with it (ON DELETE CASCADE)
        emails.clear();
    }

    private int postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
    }

    private int register(String email) throws Exception {
        return postJson("/api/v1/auth/register", """
                {"email":"%s","password":"%s","fullName":"Test User"}
                """.formatted(email, PASSWORD));
    }

    private long userCount() {
        return jdbc.sql("SELECT count(*) FROM identity.users").query(Long.class).single();
    }

    @Test
    void registerCreatesAnUnverifiedUserAndSendsOneVerificationEmail() throws Exception {
        assertThat(register("alice@example.com")).isEqualTo(202);

        User user = users.findByEmail("alice@example.com").orElseThrow();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(emails.sent()).hasSize(1);
        SentEmail email = emails.sent().get(0);
        assertThat(email.to()).isEqualTo("alice@example.com");
        assertThat(email.template()).isEqualTo(TemplateKey.VERIFY_EMAIL);
        assertThat(email.params().get("link")).contains("/verify-email?token=");
    }

    @Test
    void registerStoresAnArgon2idHashAndNeverThePassword() throws Exception {
        register("bob@example.com");

        String stored = users.findByEmail("bob@example.com").orElseThrow().passwordHash();
        assertThat(stored).startsWith("$argon2id$").doesNotContain(PASSWORD);
    }

    @Test
    void registerWithInvalidEmailReturns400AndCreatesNothing() throws Exception {
        int status = postJson("/api/v1/auth/register", """
                {"email":"not-an-email","password":"%s","fullName":"Test User"}
                """.formatted(PASSWORD));

        assertThat(status).isEqualTo(400);
        assertThat(userCount()).isZero();
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    void registerWithShortPasswordReturns400AndCreatesNothing() throws Exception {
        int status = postJson("/api/v1/auth/register", """
                {"email":"carol@example.com","password":"short","fullName":"Test User"}
                """);

        assertThat(status).isEqualTo(400);
        assertThat(userCount()).isZero();
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    void registeringTheSameEmailAgainReturns202ButSendsNoSecondEmail() throws Exception {
        assertThat(register("dave@example.com")).isEqualTo(202);
        assertThat(register("dave@example.com")).isEqualTo(202);
        assertThat(register("DAVE@Example.com")).isEqualTo(202);

        assertThat(userCount()).isEqualTo(1);
        assertThat(emails.sent()).hasSize(1);
    }

    @Test
    void verifyEmailWithAValidTokenVerifiesTheUserAndTheTokenWorksOnlyOnce() throws Exception {
        register("erin@example.com");
        String token = RecordingNotificationApi.tokenFrom(emails.sent().get(0));
        String body = "{\"token\":\"" + token + "\"}";

        assertThat(postJson("/api/v1/auth/verify-email", body)).isEqualTo(204);
        assertThat(users.findByEmail("erin@example.com").orElseThrow().isEmailVerified()).isTrue();

        assertThat(postJson("/api/v1/auth/verify-email", body)).isEqualTo(400);
    }

    @Test
    void verifyEmailWithAnUnknownTokenReturns400() throws Exception {
        int status = postJson("/api/v1/auth/verify-email", "{\"token\":\"" + OpaqueTokens.generate() + "\"}");

        assertThat(status).isEqualTo(400);
    }

    @Test
    void verifyEmailWithAnExpiredTokenReturns400AndLeavesTheUserUnverified() throws Exception {
        register("frank@example.com");
        User user = users.findByEmail("frank@example.com").orElseThrow();
        String rawToken = OpaqueTokens.generate();
        jdbc.sql("""
                INSERT INTO identity.email_tokens (id, user_id, type, token_hash, expires_at)
                VALUES (:id, :userId, 'VERIFY', :hash, now() - interval '1 hour')
                """)
                .param("id", UUID.randomUUID())
                .param("userId", user.id())
                .param("hash", OpaqueTokens.hash(rawToken))
                .update();

        int status = postJson("/api/v1/auth/verify-email", "{\"token\":\"" + rawToken + "\"}");

        assertThat(status).isEqualTo(400);
        assertThat(users.findByEmail("frank@example.com").orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void resendVerificationForAnUnverifiedUserSendsANewWorkingEmail() throws Exception {
        register("gina@example.com");
        emails.clear();

        assertThat(postJson("/api/v1/auth/resend-verification", "{\"email\":\"gina@example.com\"}")).isEqualTo(202);

        assertThat(emails.sent()).hasSize(1);
        String token = RecordingNotificationApi.tokenFrom(emails.sent().get(0));
        assertThat(postJson("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}")).isEqualTo(204);
    }

    @Test
    void resendVerificationForUnknownOrAlreadyVerifiedUsersReturns202ButSendsNothing() throws Exception {
        assertThat(postJson("/api/v1/auth/resend-verification", "{\"email\":\"nobody@example.com\"}")).isEqualTo(202);

        register("hana@example.com");
        String token = RecordingNotificationApi.tokenFrom(emails.sent().get(0));
        postJson("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}");
        emails.clear();

        assertThat(postJson("/api/v1/auth/resend-verification", "{\"email\":\"hana@example.com\"}")).isEqualTo(202);
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    void everythingElseStaysDenied() throws Exception {
        // 401 for an anonymous caller (resource server) or 403, depending on the entry point: both mean "denied".
        int status = mvc.perform(get("/api/v1/anything")).andReturn().getResponse().getStatus();

        assertThat(status).isIn(401, 403);
    }
}
