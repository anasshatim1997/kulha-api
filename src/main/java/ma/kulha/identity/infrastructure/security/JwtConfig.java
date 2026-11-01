package ma.kulha.identity.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
class JwtConfig {

    /**
     * Loads a stable RSA key pair from environment-backed Base64 DER values. Ephemeral keys are
     * available only when explicitly enabled for local development and tests. Production must
     * inject a persistent key pair through its secret manager; a process restart must not silently
     * invalidate every outstanding access token.
     */
    @Bean
    RSAKey jwtSigningKey(
            @Value("${app.security.jwt.private-key-base64:}") String privateKeyBase64,
            @Value("${app.security.jwt.public-key-base64:}") String publicKeyBase64,
            @Value("${app.security.jwt.key-id:kulha-primary}") String keyId,
            @Value("${app.security.jwt.allow-ephemeral-key:false}") boolean allowEphemeralKey)
            throws GeneralSecurityException {
        boolean hasPrivate = privateKeyBase64 != null && !privateKeyBase64.isBlank();
        boolean hasPublic = publicKeyBase64 != null && !publicKeyBase64.isBlank();

        if (hasPrivate != hasPublic) {
            throw new IllegalStateException(
                    "Both APP_SECURITY_JWT_PRIVATE_KEY_BASE64 and APP_SECURITY_JWT_PUBLIC_KEY_BASE64 must be configured.");
        }
        if (!hasPrivate) {
            if (!allowEphemeralKey) {
                throw new IllegalStateException(
                        "Persistent JWT keys are required. Configure APP_SECURITY_JWT_PRIVATE_KEY_BASE64 and "
                                + "APP_SECURITY_JWT_PUBLIC_KEY_BASE64. For local development only, explicitly set "
                                + "APP_SECURITY_JWT_ALLOW_EPHEMERAL_KEY=true.");
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("local-" + UUID.randomUUID())
                    .build();
        }

        if (keyId == null || keyId.isBlank() || keyId.length() > 100) {
            throw new IllegalStateException("APP_SECURITY_JWT_KEY_ID must be between 1 and 100 characters.");
        }

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(
                new PKCS8EncodedKeySpec(decodeBase64Der(privateKeyBase64, "private")));
        RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                new X509EncodedKeySpec(decodeBase64Der(publicKeyBase64, "public")));

        if (privateKey.getModulus().bitLength() < 2048 || publicKey.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("JWT RSA keys must be at least 2048 bits.");
        }
        verifyKeyPair(privateKey, publicKey);

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyId)
                .build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey)));
    }

    /** Accepts only RS256 tokens signed by our key, with the right issuer, audience and valid time window. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey jwtSigningKey,
                          @Value("${app.security.jwt.issuer:kulha-api}") String issuer,
                          @Value("${app.security.jwt.audience:kulha-clients}") String audience) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey(jwtSigningKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerAndTime = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceCheck = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTime, audienceCheck));
        return decoder;
    }

    static byte[] decodeBase64Der(String encoded, String keyName) {
        try {
            return Base64.getDecoder().decode(encoded.replaceAll("\\s", ""));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("JWT " + keyName + " key must be Base64-encoded DER.", invalid);
        }
    }

    private static void verifyKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey)
            throws GeneralSecurityException {
        byte[] challenge = (UUID.randomUUID() + ":kulha-jwt-key-check").getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(challenge);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(challenge);
        if (!verifier.verify(signature)) {
            throw new IllegalStateException("Configured JWT public and private keys do not form a pair.");
        }
    }
}
