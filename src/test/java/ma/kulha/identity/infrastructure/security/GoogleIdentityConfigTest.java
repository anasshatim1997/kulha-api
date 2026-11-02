package ma.kulha.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class GoogleIdentityConfigTest {

    private static final String CLIENT_ID = "kulha-google-client";

    @Test
    void acceptsGoogleIssuerAndConfiguredAudience() {
        assertThat(GoogleIdentityConfig.googleTokenValidator(CLIENT_ID).validate(claims(
                "https://accounts.google.com", CLIENT_ID)).hasErrors()).isFalse();
        assertThat(GoogleIdentityConfig.googleTokenValidator(CLIENT_ID).validate(claims(
                "accounts.google.com", CLIENT_ID)).hasErrors()).isFalse();
    }

    @Test
    void rejectsWrongIssuer() {
        assertThat(GoogleIdentityConfig.googleTokenValidator(CLIENT_ID).validate(claims(
                "https://attacker.example", CLIENT_ID)).hasErrors()).isTrue();
    }

    @Test
    void rejectsWrongOrUnconfiguredAudience() {
        assertThat(GoogleIdentityConfig.googleTokenValidator(CLIENT_ID).validate(claims(
                "https://accounts.google.com", "another-client")).hasErrors()).isTrue();
        assertThat(GoogleIdentityConfig.googleTokenValidator("").validate(claims(
                "https://accounts.google.com", CLIENT_ID)).hasErrors()).isTrue();
    }

    private static Jwt claims(String issuer, String audience) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("google-subject")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
