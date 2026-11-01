package ma.kulha.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();

    @Test
    void configuredPersistentKeyPairLoadsWithStableKeyId() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();

        RSAKey key = config.jwtSigningKey(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                "kulha-production-2026-11",
                false);

        assertThat(key.getKeyID()).isEqualTo("kulha-production-2026-11");
        assertThat(key.isPrivate()).isTrue();
        assertThat(key.toRSAPublicKey().getModulus().bitLength()).isGreaterThanOrEqualTo(2048);
    }

    @Test
    void refusesToStartWithoutKeysWhenEphemeralKeysAreDisabled() {
        assertThatThrownBy(() -> config.jwtSigningKey("", "", "kulha-primary", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Persistent JWT keys are required");
    }

    @Test
    void refusesWhenOnlyOneHalfOfTheKeyPairIsConfigured() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();

        assertThatThrownBy(() -> config.jwtSigningKey(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                "", "kulha-primary", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Both APP_SECURITY_JWT_PRIVATE_KEY_BASE64");
    }

    @Test
    void refusesMismatchedPublicAndPrivateKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var first = generator.generateKeyPair();
        var second = generator.generateKeyPair();

        assertThatThrownBy(() -> config.jwtSigningKey(
                Base64.getEncoder().encodeToString(first.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(second.getPublic().getEncoded()),
                "kulha-primary", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not form a pair");
    }

    @Test
    void ephemeralKeysCanOnlyBeCreatedWhenExplicitlyEnabled() throws Exception {
        RSAKey key = config.jwtSigningKey("", "", "kulha-primary", true);
        assertThat(key.getKeyID()).startsWith("local-");
        assertThat(key.isPrivate()).isTrue();
    }
}
