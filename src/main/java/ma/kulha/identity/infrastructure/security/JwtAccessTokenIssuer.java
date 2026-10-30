package ma.kulha.identity.infrastructure.security;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.kulha.identity.application.AccessToken;
import ma.kulha.identity.application.AccessTokenIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Signs short-lived access tokens. The token carries only the user id (subject): no email, no name,
 * no roles. Roles are read from the database on every request.
 */
@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final String keyId;
    private final String issuer;
    private final String audience;
    private final Duration ttl;

    JwtAccessTokenIssuer(JwtEncoder encoder,
                         RSAKey jwtSigningKey,
                         @Value("${app.security.jwt.issuer:kulha-api}") String issuer,
                         @Value("${app.security.jwt.audience:kulha-clients}") String audience,
                         @Value("${app.security.jwt.access-ttl-minutes:10}") long ttlMinutes) {
        this.encoder = encoder;
        this.keyId = jwtSigningKey.getKeyID();
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    @Override
    public AccessToken issue(UUID userId, Instant now) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, ttl.toSeconds());
    }
}
