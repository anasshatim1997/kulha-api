package ma.kulha.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Signs tokens shaped like the ones Google sends (a single-string "aud", a boolean "email_verified") and
 * decodes them with the same validator the application uses, so claim-conversion surprises show up here
 * and not only against the real Google. Only the key source differs: a local key instead of Google's JWKS.
 */
class GoogleIdTokenDecodingTest {

    private static final String CLIENT_ID = "1234567890-abc.apps.googleusercontent.com";
    private static RSAKey key;
    private static JwtDecoder decoder;

    @BeforeAll
    static void createKeyAndDecoder() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-google-key").generate();
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        nimbus.setJwtValidator(GoogleIdentityConfig.googleTokenValidator(CLIENT_ID));
        decoder = nimbus;
    }

    private static String sign(String issuer, String audience, Instant expiresAt) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience) // one audience is serialised as a plain string, like Google does
                .subject("110169484474386276334")
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(expiresAt))
                .claim("azp", CLIENT_ID)
                .claim("email", "person@example.com")
                .claim("email_verified", true)
                .claim("name", "Test Person")
                .claim("nonce", "expected-nonce")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void acceptsAGoogleShapedTokenWhoseAudienceIsASingleString() throws Exception {
        Jwt jwt = decoder.decode(sign("https://accounts.google.com", CLIENT_ID, Instant.now().plusSeconds(300)));

        assertThat(jwt.getAudience()).containsExactly(CLIENT_ID);
        assertThat(jwt.getSubject()).isEqualTo("110169484474386276334");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("person@example.com");
        assertThat(jwt.<Object>getClaim("email_verified")).isEqualTo(true);
        assertThat(jwt.getClaimAsString("nonce")).isEqualTo("expected-nonce");
    }

    @Test
    void acceptsTheIssuerWithoutTheScheme() throws Exception {
        assertThat(decoder.decode(sign("accounts.google.com", CLIENT_ID, Instant.now().plusSeconds(300)))).isNotNull();
    }

    @Test
    void rejectsATokenIssuedForAnotherGoogleClient() throws Exception {
        String token = sign("https://accounts.google.com", "someone-elses-client.apps.googleusercontent.com",
                Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        String token = sign("https://accounts.google.com", CLIENT_ID, Instant.now().minusSeconds(3600));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenFromAnotherIssuer() throws Exception {
        String token = sign("https://attacker.example", CLIENT_ID, Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
