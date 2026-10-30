package ma.kulha.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ma.kulha.identity.application.AccessTokenIssuer;
import ma.kulha.identity.application.OpaqueTokens;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class AuthenticationIntegrationTest {

    private static final String ISSUER = "kulha-api";
    private static final String AUDIENCE = "kulha-clients";
    private static final String PASSWORD = "a-long-enough-password";
    private static final String EMAIL = "jane@example.com";

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    AccessTokenIssuer accessTokenIssuer;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM identity.users").update(); // tokens and roles go with it (ON DELETE CASCADE)
    }

    // ---------- helpers ----------

    private User createUser(String email) {
        User user = User.register(email, passwordEncoder.encode(PASSWORD), "Test User", null, Instant.now());
        users.insert(user);
        users.markEmailVerified(user.id(), Instant.now());
        return users.findById(user.id()).orElseThrow();
    }

    private MockHttpServletResponse postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse login(String email, String password) throws Exception {
        return postJson("/api/v1/auth/login",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private MockHttpServletResponse refresh(String refreshToken) throws Exception {
        return postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private MockHttpServletResponse getWithToken(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn().getResponse();
    }

    private static String field(MockHttpServletResponse response, String name) throws Exception {
        String body = response.getContentAsString();
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("field '%s' in response: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private String accessTokenFor(String email) throws Exception {
        createUser(email);
        return field(login(email, PASSWORD), "accessToken");
    }

    private static String encode(JwtEncoder encoder, String issuer, String audience,
                                 Instant issuedAt, Instant expiresAt, String subject) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String tokenFromTheAppKey(String issuer, String audience, Instant expiresAt, String subject) {
        return encode(jwtEncoder, issuer, audience, expiresAt.minus(1, ChronoUnit.HOURS), expiresAt, subject);
    }

    // ---------- login ----------

    @Test
    void loginWithCorrectCredentialsReturnsAnAccessAndARefreshToken() throws Exception {
        createUser(EMAIL);

        MockHttpServletResponse response = login(EMAIL, PASSWORD);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(field(response, "accessToken")).isNotBlank();
        assertThat(field(response, "refreshToken")).hasSize(43);
        assertThat(field(response, "tokenType")).isEqualTo("Bearer");
    }

    @Test
    void loginWithAWrongPasswordReturns401() throws Exception {
        createUser(EMAIL);

        assertThat(login(EMAIL, "not-the-password").getStatus()).isEqualTo(401);
    }

    @Test
    void anUnknownEmailGetsExactlyTheSameAnswerAsAWrongPassword() throws Exception {
        createUser(EMAIL);

        MockHttpServletResponse wrongPassword = login(EMAIL, "not-the-password");
        MockHttpServletResponse unknownEmail = login("nobody@example.com", "not-the-password");

        assertThat(unknownEmail.getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getContentAsString()).isEqualTo(wrongPassword.getContentAsString());
    }

    @Test
    void aDisabledAccountCannotLogIn() throws Exception {
        User user = createUser(EMAIL);
        jdbc.sql("UPDATE identity.users SET status = 'DISABLED' WHERE id = :id").param("id", user.id()).update();

        assertThat(login(EMAIL, PASSWORD).getStatus()).isEqualTo(401);
    }

    @Test
    void loginWithBlankFieldsReturns400() throws Exception {
        assertThat(postJson("/api/v1/auth/login", "{\"email\":\"\",\"password\":\"\"}").getStatus()).isEqualTo(400);
    }

    // ---------- /me and JWT validation ----------

    @Test
    void meWithAValidAccessTokenReturnsTheProfile() throws Exception {
        String token = accessTokenFor(EMAIL);

        MockHttpServletResponse response = getWithToken("/api/v1/me", token);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("\"email\":\"" + EMAIL + "\"")
                .contains("\"emailVerified\":true")
                .contains("CUSTOMER");
    }

    @Test
    void rolesComeFromTheDatabaseSoARoleChangeAppliesToTheSameToken() throws Exception {
        String token = accessTokenFor(EMAIL);
        User user = users.findByEmail(EMAIL).orElseThrow();
        assertThat(getWithToken("/api/v1/me", token).getContentAsString()).doesNotContain("MERCHANT");

        users.addRole(user.id(), Role.MERCHANT);

        assertThat(getWithToken("/api/v1/me", token).getContentAsString()).contains("MERCHANT");
    }

    @Test
    void meWithoutATokenReturns401() throws Exception {
        assertThat(mvc.perform(get("/api/v1/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void meWithAGarbageTokenReturns401() throws Exception {
        assertThat(getWithToken("/api/v1/me", "this.is.not-a-jwt").getStatus()).isEqualTo(401);
    }

    @Test
    void meWithATokenSignedByAnotherKeyReturns401() throws Exception {
        User user = createUser(EMAIL);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey otherKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate()).keyID("other").build();
        NimbusJwtEncoder otherEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(otherKey)));
        Instant now = Instant.now();
        String forged = encode(otherEncoder, ISSUER, AUDIENCE, now, now.plus(1, ChronoUnit.HOURS),
                user.id().toString());

        assertThat(getWithToken("/api/v1/me", forged).getStatus()).isEqualTo(401);
    }

    @Test
    void meWithAnUnsignedAlgNoneTokenReturns401() throws Exception {
        User user = createUser(EMAIL);
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long exp = Instant.now().plus(1, ChronoUnit.HOURS).getEpochSecond();
        String payload = base64.encodeToString(
                ("{\"sub\":\"%s\",\"iss\":\"%s\",\"aud\":[\"%s\"],\"exp\":%d}"
                        .formatted(user.id(), ISSUER, AUDIENCE, exp)).getBytes(StandardCharsets.UTF_8));

        assertThat(getWithToken("/api/v1/me", header + "." + payload + ".").getStatus()).isEqualTo(401);
    }

    @Test
    void meWithTheWrongIssuerReturns401() throws Exception {
        User user = createUser(EMAIL);
        String token = tokenFromTheAppKey("someone-else", AUDIENCE,
                Instant.now().plus(1, ChronoUnit.HOURS), user.id().toString());

        assertThat(getWithToken("/api/v1/me", token).getStatus()).isEqualTo(401);
    }

    @Test
    void meWithTheWrongAudienceReturns401() throws Exception {
        User user = createUser(EMAIL);
        String token = tokenFromTheAppKey(ISSUER, "another-audience",
                Instant.now().plus(1, ChronoUnit.HOURS), user.id().toString());

        assertThat(getWithToken("/api/v1/me", token).getStatus()).isEqualTo(401);
    }

    @Test
    void meWithAnExpiredTokenReturns401() throws Exception {
        User user = createUser(EMAIL);
        String token = tokenFromTheAppKey(ISSUER, AUDIENCE,
                Instant.now().minus(2, ChronoUnit.HOURS), user.id().toString());

        assertThat(getWithToken("/api/v1/me", token).getStatus()).isEqualTo(401);
    }

    @Test
    void aValidTokenForAnAccountThatDoesNotExistReturns401() throws Exception {
        String token = accessTokenIssuer.issue(UUID.randomUUID(), Instant.now()).value();

        assertThat(getWithToken("/api/v1/me", token).getStatus()).isEqualTo(401);
    }

    @Test
    void theAccessTokenContainsNoPersonalData() throws Exception {
        String token = accessTokenFor(EMAIL);

        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(payload).contains("\"sub\"").doesNotContain(EMAIL).doesNotContain("Test User");
    }

    @Test
    void anAuthenticatedUserStillCannotReachRoutesThatAreNotOpened() throws Exception {
        String token = accessTokenFor(EMAIL);

        assertThat(getWithToken("/api/v1/anything", token).getStatus()).isEqualTo(403);
    }

    // ---------- refresh and logout ----------

    @Test
    void refreshGivesANewPairAndAReusedOldTokenEndsTheWholeSession() throws Exception {
        createUser(EMAIL);
        String first = field(login(EMAIL, PASSWORD), "refreshToken");

        MockHttpServletResponse rotated = refresh(first);
        assertThat(rotated.getStatus()).isEqualTo(200);
        String second = field(rotated, "refreshToken");
        assertThat(second).isNotEqualTo(first);

        // The old token is reused: refused, and the family is revoked.
        assertThat(refresh(first).getStatus()).isEqualTo(401);
        // So the newer token no longer works either.
        assertThat(refresh(second).getStatus()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        createUser(EMAIL);
        String refreshToken = field(login(EMAIL, PASSWORD), "refreshToken");

        MockHttpServletResponse logout =
                postJson("/api/v1/auth/logout", "{\"refreshToken\":\"" + refreshToken + "\"}");

        assertThat(logout.getStatus()).isEqualTo(204);
        assertThat(refresh(refreshToken).getStatus()).isEqualTo(401);
    }

    @Test
    void refreshWithAnUnknownTokenReturns401() throws Exception {
        assertThat(refresh(OpaqueTokens.generate()).getStatus()).isEqualTo(401);
    }

    @Test
    void refreshWithAnExpiredTokenReturns401() throws Exception {
        User user = createUser(EMAIL);
        String raw = OpaqueTokens.generate();
        jdbc.sql("""
                INSERT INTO identity.refresh_tokens (id, user_id, family_id, token_hash, expires_at)
                VALUES (:id, :userId, :familyId, :hash, now() - interval '1 hour')
                """)
                .param("id", UUID.randomUUID())
                .param("userId", user.id())
                .param("familyId", UUID.randomUUID())
                .param("hash", OpaqueTokens.hash(raw))
                .update();

        assertThat(refresh(raw).getStatus()).isEqualTo(401);
    }

    @Test
    void refreshTokensAreStoredOnlyAsHashes() throws Exception {
        User user = createUser(EMAIL);
        String raw = field(login(EMAIL, PASSWORD), "refreshToken");

        String stored = jdbc.sql("SELECT token_hash FROM identity.refresh_tokens WHERE user_id = :id")
                .param("id", user.id()).query(String.class).single();

        assertThat(stored).isNotEqualTo(raw).isEqualTo(OpaqueTokens.hash(raw));
    }
}
