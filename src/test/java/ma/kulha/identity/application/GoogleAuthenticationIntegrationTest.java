package ma.kulha.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ma.kulha.identity.domain.model.EmailTokenType;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.EmailTokenRepository;
import ma.kulha.identity.domain.repository.ExternalIdentityRepository;
import ma.kulha.identity.domain.repository.RefreshTokenRepository;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every database branch of the Google sign-in (ADR-003), against the real PostgreSQL schema.
 * Only Google's token decoder is replaced: signature checking against Google's keys cannot run offline,
 * and it is covered separately by GoogleIdTokenDecodingTest.
 */
class GoogleAuthenticationIntegrationTest extends AbstractApiIntegrationTest {

    private static final String BFF_SECRET = "integration-test-bff-secret";
    private static final String CLIENT_ID = "google-client-id";
    private static final String NONCE = "expected-nonce";

    @Autowired
    ExternalIdentityRepository identities;
    @Autowired
    RefreshTokenRepository refreshTokens;
    @Autowired
    EmailTokenRepository emailTokens;
    @Autowired
    AuthenticationService authentication;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    Clock clock;

    // ---------- helpers ----------

    private GoogleAuthenticationService serviceReturning(Jwt claims) {
        JwtDecoder decoder = token -> claims;
        return new GoogleAuthenticationService(decoder, users, identities, refreshTokens, emailTokens,
                authentication, passwordEncoder, tx, clock, BFF_SECRET, CLIENT_ID);
    }

    private static Jwt googleToken(String subject, String email, boolean emailVerified, String authorizedParty) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("google-test-token")
                .header("alg", "RS256")
                .issuer("https://accounts.google.com")
                .audience(List.of(CLIENT_ID))
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("name", "Google Person")
                .claim("nonce", NONCE);
        if (authorizedParty != null) {
            builder.claim("azp", authorizedParty);
        }
        return builder.build();
    }

    private static Jwt googleToken(String subject, String email) {
        return googleToken(subject, email, true, null);
    }

    private TokenPair signIn(Jwt claims) {
        return serviceReturning(claims).login("id-token", NONCE, BFF_SECRET);
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void disable(UUID userId) {
        jdbc.sql("UPDATE identity.users SET status = 'DISABLED' WHERE id = :id").param("id", userId).update();
    }

    private void storeToken(UUID userId, EmailTokenType type, String rawToken) {
        Instant now = Instant.now();
        emailTokens.insert(UUID.randomUUID(), userId, type, OpaqueTokens.hash(rawToken),
                now.plus(Duration.ofHours(1)), now);
    }

    // ---------- new accounts ----------

    @Test
    void aBrandNewGoogleUserGetsOnlyTheCustomerRoleAVerifiedEmailAndNoUsablePassword() throws Exception {
        TokenPair pair = signIn(googleToken("subject-new", "new.person@example.com"));

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        User created = users.findByEmail("new.person@example.com").orElseThrow();
        assertThat(created.roles()).isEqualTo(Set.of(Role.CUSTOMER));
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(identities.findUserId("GOOGLE", "subject-new")).contains(created.id());

        // The random password nobody knows cannot be guessed, whatever is tried.
        MockHttpServletResponse attempt = doPost("/api/v1/auth/login",
                loginJson("new.person@example.com", PASSWORD), null);
        assertThat(attempt.getStatus()).isEqualTo(401);
    }

    @Test
    void theSecondSignInIsFoundBySubjectEvenWhenTheEmailInTheTokenChanged() {
        signIn(googleToken("subject-stable", "first@example.com"));

        TokenPair second = signIn(googleToken("subject-stable", "renamed@example.com"));

        assertThat(second.accessToken()).isNotBlank();
        assertThat(count("identity.users")).isEqualTo(1);
        assertThat(count("identity.external_identities")).isEqualTo(1);
    }

    // ---------- linking to an existing account ----------

    @Test
    void aVerifiedPasswordAccountIsLinkedAndItsPasswordKeepsWorking() throws Exception {
        User owner = createUser("owner@example.com", true);

        TokenPair pair = signIn(googleToken("subject-owner", "owner@example.com"));

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(identities.findUserId("GOOGLE", "subject-owner")).contains(owner.id());
        assertThat(count("identity.users")).isEqualTo(1);
        MockHttpServletResponse passwordLogin = doPost("/api/v1/auth/login",
                loginJson("owner@example.com", PASSWORD), null);
        assertThat(passwordLogin.getStatus()).isEqualTo(200);
    }

    /** N1: the attacker registers the victim's address first, then the victim signs in with Google. */
    @Test
    void anUnverifiedPasswordAccountIsNeutralisedBeforeGoogleTakesItOver() throws Exception {
        User preRegistered = createUser("victim@example.com", false);
        MockHttpServletResponse attackerLogin = doPost("/api/v1/auth/login",
                loginJson("victim@example.com", PASSWORD), null);
        assertThat(attackerLogin.getStatus()).isEqualTo(200);
        String attackerRefreshToken = field(attackerLogin, "refreshToken");
        String oldVerifyLink = OpaqueTokens.generate();
        String oldResetLink = OpaqueTokens.generate();
        storeToken(preRegistered.id(), EmailTokenType.VERIFY, oldVerifyLink);
        storeToken(preRegistered.id(), EmailTokenType.RESET, oldResetLink);

        TokenPair pair = signIn(googleToken("subject-victim", "victim@example.com"));

        assertThat(pair.accessToken()).isNotBlank();
        User afterLinking = users.findById(preRegistered.id()).orElseThrow();
        assertThat(afterLinking.isEmailVerified()).isTrue();
        assertThat(identities.findUserId("GOOGLE", "subject-victim")).contains(preRegistered.id());

        // Every door the pre-registrant held is closed.
        assertThat(doPost("/api/v1/auth/login", loginJson("victim@example.com", PASSWORD), null).getStatus())
                .as("the attacker's password").isEqualTo(401);
        assertThat(doPost("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(attackerRefreshToken), null)
                .getStatus()).as("the attacker's old session").isEqualTo(401);
        assertThat(doPost("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(oldVerifyLink), null)
                .getStatus()).as("an old verification link").isEqualTo(400);
        assertThat(doPost("/api/v1/auth/reset-password",
                "{\"token\":\"%s\",\"newPassword\":\"a-brand-new-password\"}".formatted(oldResetLink), null)
                .getStatus()).as("an old reset link").isEqualTo(400);

        // The real owner still gets in with Google.
        assertThat(signIn(googleToken("subject-victim", "victim@example.com")).accessToken()).isNotBlank();
    }

    // ---------- refusals ----------

    @Test
    void aDisabledAccountFoundByEmailIsRefusedAndNotReactivated() {
        User user = createUser("disabled@example.com", true);
        disable(user.id());

        assertThatThrownBy(() -> signIn(googleToken("subject-disabled", "disabled@example.com")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(users.findById(user.id()).orElseThrow().status()).isEqualTo(UserStatus.DISABLED);
        assertThat(count("identity.external_identities")).isZero();
    }

    @Test
    void aDisabledAccountAlreadyLinkedToGoogleIsRefused() {
        signIn(googleToken("subject-linked", "linked@example.com"));
        User linked = users.findByEmail("linked@example.com").orElseThrow();
        disable(linked.id());

        assertThatThrownBy(() -> signIn(googleToken("subject-linked", "linked@example.com")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(users.findById(linked.id()).orElseThrow().status()).isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void anAccountAlreadyLinkedToAnotherGoogleSubjectGetsA401NotAServerError() {
        signIn(googleToken("subject-one", "shared@example.com"));

        assertThatThrownBy(() -> signIn(googleToken("subject-two", "shared@example.com")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(count("identity.external_identities")).isEqualTo(1);
    }

    @Test
    void aTokenIssuedToAnotherGoogleClientIsRefused() {
        assertThatThrownBy(() -> signIn(googleToken("subject-azp", "azp@example.com", true, "someone-elses-client")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(count("identity.users")).isZero();
    }

    @Test
    void anEmailGoogleHasNotVerifiedCreatesNothing() {
        assertThatThrownBy(() -> signIn(googleToken("subject-unverified", "nope@example.com", false, null)))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(count("identity.users")).isZero();
        assertThat(count("identity.external_identities")).isZero();
    }
}
