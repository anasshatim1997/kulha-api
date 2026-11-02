package ma.kulha.identity.infrastructure.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
class GoogleIdentityConfig {
    @Bean("googleIdTokenDecoder")
    JwtDecoder googleIdTokenDecoder(@Value("${app.security.google.client-id:}") String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
        decoder.setJwtValidator(googleTokenValidator(clientId));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> googleTokenValidator(String clientId) {
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefault();
        OAuth2TokenValidator<Jwt> issuer = new JwtClaimValidator<String>(
                "iss", value -> "https://accounts.google.com".equals(value) || "accounts.google.com".equals(value));
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                "aud", values -> !clientId.isBlank() && values != null && values.contains(clientId));
        return new DelegatingOAuth2TokenValidator<>(defaults, issuer, audience);
    }
}
