package ma.kulha.identity.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class GoogleAuthenticationServiceTest {

    private static final String BFF_SECRET = "test-bff-secret";
    private static final String CLIENT_ID = "google-client-id";

    @Test
    void rejectsRequestsWithoutTheBffSecretBeforeDecodingTheGoogleToken() {
        GoogleAuthenticationService service = service(token -> {
            throw new AssertionError("The Google token must not be decoded before BFF authentication");
        }, BFF_SECRET);

        assertThatThrownBy(() -> service.login("not-a-token", "nonce", null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsAnIdTokenWithAMismatchedNonce() {
        Jwt claims = claims(true, "google-nonce");
        GoogleAuthenticationService service = service(token -> claims, BFF_SECRET);

        assertThatThrownBy(() -> service.login("id-token", "different-nonce", BFF_SECRET))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsAnIdTokenWhoseEmailIsNotVerified() {
        Jwt claims = claims(false, "expected-nonce");
        GoogleAuthenticationService service = service(token -> claims, BFF_SECRET);

        assertThatThrownBy(() -> service.login("id-token", "expected-nonce", BFF_SECRET))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    private static GoogleAuthenticationService service(
            org.springframework.security.oauth2.jwt.JwtDecoder decoder, String secret) {
        return new GoogleAuthenticationService(decoder, null, null, null, null, null, null, null,
                Clock.systemUTC(), secret, CLIENT_ID);
    }

    private static Jwt claims(boolean emailVerified, String nonce) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer("https://accounts.google.com")
                .audience(List.of(CLIENT_ID))
                .subject("google-subject")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("email", "person@example.com")
                .claim("email_verified", emailVerified)
                .claim("nonce", nonce)
                .build();
    }
}
