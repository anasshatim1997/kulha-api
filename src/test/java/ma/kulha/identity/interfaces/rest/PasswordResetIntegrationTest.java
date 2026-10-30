package ma.kulha.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ma.kulha.identity.application.OpaqueTokens;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import ma.kulha.notification.application.TemplateKey;
import ma.kulha.support.RecordingNotificationApi;
import ma.kulha.support.TestNotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestNotificationConfig.class)
class PasswordResetIntegrationTest {

    private static final String EMAIL = "jane@example.com";
    private static final String OLD_PASSWORD = "the-old-password-123";
    private static final String NEW_PASSWORD = "the-brand-new-password-456";

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

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

    // ---------- helpers ----------

    private User createUser(String email) {
        User user = User.register(email, passwordEncoder.encode(OLD_PASSWORD), "Test User", null, Instant.now());
        users.insert(user);
        users.markEmailVerified(user.id(), Instant.now());
        return users.findById(user.id()).orElseThrow();
    }

    private MockHttpServletResponse postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse forgot(String email) throws Exception {
        return postJson("/api/v1/auth/forgot-password", "{\"email\":\"" + email + "\"}");
    }

    private MockHttpServletResponse reset(String token, String newPassword) throws Exception {
        return postJson("/api/v1/auth/reset-password",
                "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, newPassword));
    }

    private MockHttpServletResponse login(String email, String password) throws Exception {
        return postJson("/api/v1/auth/login",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private static String field(MockHttpServletResponse response, String name) throws Exception {
        String body = response.getContentAsString();
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("field '%s' in response: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private String lastResetToken() {
        var sent = emails.sent();
        return RecordingNotificationApi.tokenFrom(sent.get(sent.size() - 1));
    }

    // ---------- request ----------

    @Test
    void forgotPasswordForAnExistingUserSendsAResetEmail() throws Exception {
        createUser(EMAIL);

        assertThat(forgot(EMAIL).getStatus()).isEqualTo(202);

        assertThat(emails.sent()).hasSize(1);
        assertThat(emails.sent().get(0).to()).isEqualTo(EMAIL);
        assertThat(emails.sent().get(0).template()).isEqualTo(TemplateKey.RESET_PASSWORD);
        assertThat(emails.sent().get(0).params().get("link")).contains("/reset-password?token=");
    }

    @Test
    void forgotPasswordForAnUnknownEmailReturns202AndSendsNothing() throws Exception {
        assertThat(forgot("nobody@example.com").getStatus()).isEqualTo(202);

        assertThat(emails.sent()).isEmpty();
    }

    // ---------- reset ----------

    @Test
    void resetPasswordChangesThePasswordAndTheTokenWorksOnlyOnce() throws Exception {
        createUser(EMAIL);
        forgot(EMAIL);
        String token = lastResetToken();

        assertThat(reset(token, NEW_PASSWORD).getStatus()).isEqualTo(204);

        assertThat(login(EMAIL, NEW_PASSWORD).getStatus()).isEqualTo(200);
        assertThat(login(EMAIL, OLD_PASSWORD).getStatus()).isEqualTo(401);
        assertThat(emails.sent().get(emails.sent().size() - 1).template()).isEqualTo(TemplateKey.PASSWORD_CHANGED);

        assertThat(reset(token, "yet-another-password-789").getStatus()).isEqualTo(400);
    }

    @Test
    void resetPasswordClosesAllExistingSessions() throws Exception {
        createUser(EMAIL);
        String refreshToken = field(login(EMAIL, OLD_PASSWORD), "refreshToken");
        forgot(EMAIL);

        assertThat(reset(lastResetToken(), NEW_PASSWORD).getStatus()).isEqualTo(204);

        MockHttpServletResponse refresh = postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
        assertThat(refresh.getStatus()).isEqualTo(401);
    }

    @Test
    void resetPasswordWithAnExpiredTokenReturns400() throws Exception {
        User user = createUser(EMAIL);
        String raw = OpaqueTokens.generate();
        jdbc.sql("""
                INSERT INTO identity.email_tokens (id, user_id, type, token_hash, expires_at)
                VALUES (:id, :userId, 'RESET', :hash, now() - interval '1 hour')
                """)
                .param("id", UUID.randomUUID())
                .param("userId", user.id())
                .param("hash", OpaqueTokens.hash(raw))
                .update();

        assertThat(reset(raw, NEW_PASSWORD).getStatus()).isEqualTo(400);
        assertThat(login(EMAIL, OLD_PASSWORD).getStatus()).isEqualTo(200);
    }

    @Test
    void resetPasswordWithAnUnknownTokenReturns400() throws Exception {
        assertThat(reset(OpaqueTokens.generate(), NEW_PASSWORD).getStatus()).isEqualTo(400);
    }

    @Test
    void resetPasswordWithATooShortPasswordReturns400AndKeepsTheTokenUsable() throws Exception {
        createUser(EMAIL);
        forgot(EMAIL);
        String token = lastResetToken();

        assertThat(reset(token, "short").getStatus()).isEqualTo(400);

        assertThat(reset(token, NEW_PASSWORD).getStatus()).isEqualTo(204);
    }

    @Test
    void anEmailVerificationTokenCannotResetAPassword() throws Exception {
        postJson("/api/v1/auth/register", """
                {"email":"%s","password":"%s","fullName":"Test User"}
                """.formatted("fresh@example.com", OLD_PASSWORD));
        String verificationToken = RecordingNotificationApi.tokenFrom(emails.sent().get(0));

        assertThat(reset(verificationToken, NEW_PASSWORD).getStatus()).isEqualTo(400);
        assertThat(login("fresh@example.com", OLD_PASSWORD).getStatus()).isEqualTo(200);
    }

    @Test
    void aNewResetRequestCancelsThePreviousLink() throws Exception {
        createUser(EMAIL);
        forgot(EMAIL);
        String firstToken = lastResetToken();
        forgot(EMAIL);
        String secondToken = lastResetToken();

        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(reset(firstToken, NEW_PASSWORD).getStatus()).isEqualTo(400);
        assertThat(reset(secondToken, NEW_PASSWORD).getStatus()).isEqualTo(204);
    }
}
